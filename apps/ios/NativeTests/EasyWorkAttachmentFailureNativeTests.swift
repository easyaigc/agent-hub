import XCTest
import Foundation
import UIKit
import QuickLook
import CryptoKit
import Darwin
#if canImport(CodexManage_com_easyai_agenthub_easyworkacceptance)
@testable import CodexManage_com_easyai_agenthub_easyworkacceptance
#elseif canImport(CodexManage_com_codexmanage_ios)
@testable import CodexManage_com_codexmanage_ios
#else
#error("Build this driver with the independent EasyWork acceptance bundle or the default local compiler target")
#endif

/// One explicit attachment attempt per frozen case. Never retries an unknown write.
final class EasyWorkAttachmentFailureNativeTests: XCTestCase {
  private let issueLock = NSLock()
  private var recordedLayoutIssues: [[String: Any]] = []
  override func record(_ issue: XCTIssue) {
    super.record(issue)
    guard issue.compactDescription.contains("Invalid frame dimension"),
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = ProcessInfo.processInfo.environment["EASYWORK_HOSTED_NATIVE_CASE"],
      UUID(uuidString: run)?.uuidString.lowercased() == run else { return }
    let frames = issue.sourceCodeContext.callStack.prefix(64).map { frame -> [String: Any] in
      var value: [String: Any] = ["addressHex": String(frame.address, radix: 16)]
      if let symbol = frame.symbolInfo {
        value["image"] = symbol.imageName; value["symbol"] = symbol.symbolName
        if let location = symbol.location { value["sourceURL"] = location.fileURL.absoluteString; value["line"] = location.lineNumber }
      }
      return value
    }
    issueLock.lock(); defer { issueLock.unlock() }
    guard recordedLayoutIssues.count < 8 else { return }
    recordedLayoutIssues.append(["recordedAtEpoch": Date().timeIntervalSince1970,
      "message": issue.compactDescription, "detail": issue.detailedDescription ?? "",
      "originalIssueSourceFrames": frames, "recordCallbackStackOnlyNotTheOriginalSource": Array(Thread.callStackSymbols.prefix(32))])
    let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    if let bytes = try? easyWorkJSON(["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier,
      "officialWarningNotSuppressed": true, "issues": recordedLayoutIssues]), bytes.count <= 2 * 1024 * 1024 {
      try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      try? bytes.write(to: directory.appendingPathComponent(run + "-original-layout-issues.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
  }

  @MainActor func testOwnedAttachmentFailure() async throws {
    try await examineOriginalAttachments(normalRetry: false)
  }

  @MainActor func testRetainedOriginalAttachmentsSendOnce() async throws {
    try await examineOriginalAttachments(normalRetry: true)
  }

  @MainActor private func examineOriginalAttachments(normalRetry: Bool) async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original paired physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
      c["caseId"] as? String == run, c["newModelsAuthorized"] as? Int == (normalRetry ? 1 : 0),
      (c["normalRetainedSend"] as? Bool ?? false) == normalRetry,
      !normalRetry || c["resumeOriginalFailedThread"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      c["newChatsAuthorized"] as? Int == (c["resumeOriginalFailedThread"] == nil ? 1 : 0), c["newProjectsAuthorized"] as? Int == 0,
      c["localAttachmentsAuthorized"] as? Int == 2, c["newUploadsAuthorized"] as? Int == (normalRetry || c["resumeOriginalFailedThread"] == nil ? 1 : 0),
      c["deviceUDID"] as? String == "00008130-001C48620120001C",
      c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      c["bundleId"] as? String == "com.easyai.agenthub.easyworkacceptance",
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance" else { throw XCTSkip("Explicit frozen attachment configuration required") }
    let folder = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first).appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let reportURL = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: reportURL.path) else { throw NSError(domain: "F31ExistingCase", code: 1) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "status": "awaiting-original-app", "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: reportURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ ready: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !ready() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard ready() else { throw NSError(domain: "F31Wait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func permit(_ suffix: String) throws -> [String: Any] { try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent(run + suffix))) as? [String: Any]) }
    func sha(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    try save("awaiting-original-app")
    var restoreURL: URL?; var restoreBytes: Data?; var truncatedBytes: Data?
    var storeToRestore: SessionStore?; var original: Conversation?
    var oldProductUI = false
    var oldProject: String?
    var protectedCheck: (() throws -> Bool)?
    var newSharingGrantIds: [String] = []
    var restoreOriginalNavigation: (() -> Void)?
    var ownedKey: String?
    var ownedThread: String?
    var ownedReply: String?
    var caught: Error?
    do {
      try await wait("original app-owned store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
      let activationURL = folder.appendingPathComponent(run + "-activation-permit.json")
      try await wait("official same-process foreground permit", seconds: 120) { FileManager.default.fileExists(atPath: activationURL.path) }
      let activation = try permit("-activation-permit.json")
      guard activation["caseId"] as? String == run,
        activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
        activation["bundleId"] as? String == Bundle.main.bundleIdentifier,
        activation["deviceUDID"] as? String == c["deviceUDID"] as? String,
        activation["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F31Activation", code: 1) }
      let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore); storeToRestore = store
      let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
      let client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
      guard target.agentId == "easywork", connection.target == target, connection.preference == .lanOnly,
        !client.isDemo, !store.localRecoveryRequired, store.sheet == nil, !store.drawerOpen,
        store.error == nil else { throw NSError(domain: "F31Target", code: 1) }
      let endpoint = try XCTUnwrap(c["originalLiveLANEndpoint"] as? String), pin = try XCTUnwrap(c["expectedExistingLANCertificateSHA256"] as? String)
      let endpointURL = try XCTUnwrap(URL(string: endpoint))
      guard endpointURL.scheme == "https", endpointURL.host?.hasPrefix("192.168.") == true,
        endpointURL.port == c["expectedOriginalLANPort"] as? Int, endpointURL.path.isEmpty,
        connection.candidates.contains(where: { $0.transportId == "lan-wss" && $0.endpoint == endpoint && $0.peerFingerprint == pin }) else { throw NSError(domain: "F31LAN", code: 1) }
      let rawCredential = try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef))
      let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(rawCredential.utf8))
      let descriptor = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin,
        target: credential.binding.target, publicKey: credential.binding.desktopPublicKey, timeout: 10)
      guard descriptor.target == credential.binding.target, descriptor.publicKey == credential.binding.desktopPublicKey,
        descriptor.fingerprint == pin else { throw NSError(domain: "F31LAN", code: 2) }
      report["originalPinnedLANFullTargetPublicKeyVerified"] = true
      var interface: UnsafeMutablePointer<ifaddrs>?
      guard getifaddrs(&interface) == 0 else { throw NSError(domain: "F31LAN", code: 3) }
      var address = interface, wifi: [String] = []
      while let item = address {
        address = item.pointee.ifa_next
        guard String(cString: item.pointee.ifa_name) == "en0", let value = item.pointee.ifa_addr,
          value.pointee.sa_family == UInt8(AF_INET) else { continue }
        var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(value, socklen_t(value.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { freeifaddrs(interface); throw NSError(domain: "F31LAN", code: 4) }
        wifi.append(String(cString: buffer))
      }
      freeifaddrs(interface)
      guard wifi.count == 1, wifi[0].hasPrefix("192.168.2.") else { throw NSError(domain: "F31LAN", code: 5) }
      report["physicalIPhoneWiFiIPv4"] = wifi[0]
      // Hosted tests disable automatic refresh. Open the original production channel
      // with a read before asking the parent to witness its live LAN socket.
      let channel = try await EasyWorkSessions.shared.session(client)
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(credential.binding.target.dictionary),
        (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F31LAN", code: 7) }
      report["freshOriginalRuntimeContextVerified"] = true
      report["localTransportEpochNotDesktopHostEpoch"] = await channel.epoch
      let oldThread = try XCTUnwrap(store.selectedThreadId)
      original = try XCTUnwrap(store.saved.workspaces[client.id]?.threads.first { $0.id == oldThread })
      oldProject = store.selectedProjectId; oldProductUI = store.hostedEasyWorkProductUIPresented
      let oldLastThread = store.saved.lastThreads[client.id]
      let oldLastProject = store.saved.lastThreadProjects?[client.id]
      let oldNewProject = store.saved.newProjects[client.id]
      restoreOriginalNavigation = {
        if let original { store.openThread(original, loadAutomatically: false) }
        store.selectedProjectId = oldProject
        store.saved.lastThreads[client.id] = oldLastThread
        store.saved.lastThreadProjects?[client.id] = oldLastProject
        store.saved.newProjects[client.id] = oldNewProject
        store.hostedEasyWorkProductUIPresented = oldProductUI
        store.persist(reconcileUnread: false)
      }
      let oldDrafts = store.drafts.values, oldRecovery = store.saved.replyRecovery ?? [:]
      let resumeThread = c["resumeOriginalFailedThread"] as? String
      let expectedDraft: Draft?
      if let resumeThread {
        guard resumeThread == "10bee47f-8aef-421f-8a94-2378d6bbd6e8" else { throw NSError(domain: "F31ResumeScope", code: 1) }
        let expected = try JSONDecoder().decode(Draft.self, from: easyWorkJSON(try XCTUnwrap(c["expectedOriginalDraft"])))
        let matches = oldDrafts.filter { key, value in
          guard value == expected, key.hasPrefix("agent-v1:"),
            let data = Data(base64Encoded: String(key.dropFirst("agent-v1:".count))),
            let tuple = try? JSONSerialization.jsonObject(with: data) as? [Any], tuple.count == 8 else { return false }
          return tuple[0] as? String == "easywork" && tuple[3] as? String == target.hostId
            && tuple[4] as? String == target.workspaceId && tuple[5] as? String == target.runtimeId
            && tuple[6] as? String == "thread" && tuple[7] as? String == resumeThread
        }
        guard matches.count == 1 else { throw NSError(domain: "F31ResumeDraft", code: 1) }
        ownedKey = matches.first!.key; ownedThread = resumeThread; expectedDraft = expected
      } else { expectedDraft = nil }
      let identity = try CredentialVault.existingToken("easywork-device-identity")
      let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
      let voice = try encoder.encode(VoiceMigrationSafety.snapshot())
      let oldSharingIds = Set((store.saved.aiSharingGrants ?? []).map(\.id))
      func userState() throws -> String {
        var saved = store.saved; saved.drafts = store.drafts.values
        for index in saved.clients.indices { saved.clients[index].online = false }
        var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
        for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
        object["aiSharingGrants"] = try JSONSerialization.jsonObject(with: encoder.encode(store.saved.aiSharingGrants ?? []))
        func removeOwned(_ value: Any) -> Any {
          if let dictionary = value as? [String: Any] {
            return dictionary.filter { key, _ in !(ownedKey.map { key == $0 } ?? false) && !(ownedReply.map { key == $0 } ?? false) && !(ownedThread.map { key == $0 } ?? false) }.mapValues(removeOwned)
          }
          if let array = value as? [Any] { return array.map(removeOwned) }
          return value
        }
        return sha(try easyWorkJSON(removeOwned(object)))
      }
      let baseline = try userState()
      protectedCheck = {
        let stateMatches = try userState() == baseline
        let voiceMatches = try encoder.encode(VoiceMigrationSafety.snapshot()) == voice
        let identityMatches = try CredentialVault.existingToken("easywork-device-identity") == identity
        let credentialMatches = try CredentialVault.existingToken(connection.credentialRef) == rawCredential
        return stateMatches && voiceMatches && identityMatches && credentialMatches
          && store.client?.id == client.id && store.selectedThreadId == oldThread && store.selectedProjectId == oldProject
      }
      let project = try XCTUnwrap(c["projectId"] as? String), operation = try XCTUnwrap(c["createOperationId"] as? String)
      guard store.projects.contains(where: { $0.id == project }), UUID(uuidString: operation)?.uuidString.lowercased() == operation,
        UIDevice.current.userInterfaceIdiom == .phone else { throw NSError(domain: "F31Config", code: 1) }
      report["createOperationId"] = operation; report["target"] = c["target"]; report["projectId"] = project
      report["originalProtectedStateSHA"] = baseline
      try save("awaiting-precreate-LAN-peer-witness")
      let peerURL = folder.appendingPathComponent(run + "-LAN-peer-witness.json")
      try await wait("current physical iPhone to original Electron LAN peer", seconds: 90) { FileManager.default.fileExists(atPath: peerURL.path) }
      let peer = try permit("-LAN-peer-witness.json")
      guard peer["caseId"] as? String == run, peer["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
        peer["originalElectronLANPort"] as? Int == endpointURL.port, peer["originalElectronPID"] as? Int == c["expectedOriginalElectronPID"] as? Int,
        peer["physicalIPhoneWifiPeerConnectedToOriginalElectronLAN"] as? Bool == true else { throw NSError(domain: "F31LAN", code: 6) }
      report["independentSameCasePrecreateLANPeerVerified"] = true
      let created: Conversation
      if let resumeThread {
        created = try XCTUnwrap(store.threads.first { $0.id == resumeThread && $0.projectId == project })
        report["existingOriginalFailedDraftExplicitlyRechecked"] = true
      } else {
        try save("ready-to-create-empty-thread")
        let createURL = folder.appendingPathComponent(run + "-create-permit.json")
        try await wait("create permit", seconds: 600) { FileManager.default.fileExists(atPath: createURL.path) }
        let cp = try permit("-create-permit.json")
        guard cp["caseId"] as? String == run, cp["createOperationId"] as? String == operation,
          cp["projectId"] as? String == project, try easyWorkJSON(cp["target"] as Any) == easyWorkJSON(c["target"] as Any), cp["allowCreate"] as? Bool == true else { throw NSError(domain: "F31CreatePermit", code: 1) }
        let originalThreadIds = Set(store.threads.map(\.id))
        report["createAttempted"] = true; try save("creating-empty-thread")
        created = try await AgentClients.resolve(client).createThread(client, projectId: project, operationId: operation)
        guard !originalThreadIds.contains(created.id), !created.id.isEmpty else { throw NSError(domain: "F31NewThread", code: 1) }
      }
      ownedThread = created.id
      report["threadId"] = created.id; try save("created-empty-thread")
      store.hostedEasyWorkProductUIPresented = true
      store.openThread(created, loadAutomatically: false)
      await store.refresh(includeHistory: false); await store.loadHistory(threadId: created.id)
      guard store.selectedThreadId == created.id, store.selectedProjectId == project, store.canCompose, !store.isRunning else { throw NSError(domain: "F31Navigation", code: 1) }
      let key = store.targetKey
      if let expectedDraft {
        guard key == ownedKey, store.draft == expectedDraft else { throw NSError(domain: "F31ResumeDraft", code: 2) }
      } else {
        ownedKey = key
        guard oldDrafts[key] == nil, store.draft.text.isEmpty, store.draft.attachments.isEmpty else { throw NSError(domain: "F31DraftNotEmpty", code: 1) }
      }
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      let body = try XCTUnwrap(c["body"] as? String)
      if expectedDraft == nil {
      var field: UIView?
      try await wait("unique composer") {
        let candidates = views(window).filter { $0.window === window && !$0.isHidden && $0.alpha > 0.99 && !$0.bounds.isEmpty && (($0 as? UITextView)?.isEditable == true || ($0 as? UITextField)?.isEnabled == true) }
        field = candidates.count == 1 ? candidates.first : nil; return field != nil
      }
      let inputView = try XCTUnwrap(field), input = try XCTUnwrap(inputView as? any UITextInput)
      guard !body.isEmpty, input.text(in: input.textRange(from: input.beginningOfDocument, to: input.endOfDocument)!)?.isEmpty == true else { throw NSError(domain: "F31Input", code: 1) }
      guard inputView.becomeFirstResponder() else { throw NSError(domain: "F31Input", code: 2) }; input.insertText(body)
      try await wait("composer binding") { store.draft.text == body }; inputView.resignFirstResponder()
      let seed = try XCTUnwrap(c["textSeed"] as? String), repeats = try XCTUnwrap(c["textRepeats"] as? Int)
      guard !seed.isEmpty, repeats > 0, repeats <= 1_100_000, seed.utf8.count * repeats >= 900_000, seed.utf8.count * repeats <= 1_200_000 else { throw NSError(domain: "F31Text", code: 1) }
      let text = Data(String(repeating: seed, count: repeats).utf8)
      let png = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(c["validPNGBase64"] as? String)))
      guard UIImage(data: png) != nil else { throw NSError(domain: "F31PNG", code: 1) }
      try store.importData(text, name: "f31-\(run.prefix(8)).txt", isImage: false, target: key)
      try store.importData(png, name: "f31-\(run.prefix(8)).png", isImage: true, target: key)
      }
      let draft = store.draft; guard draft.text == body, draft.attachments.count == 2 else { throw NSError(domain: "F31Manifest", code: 1) }
      let draftObject = try JSONSerialization.jsonObject(with: encoder.encode(draft)), frozenSHA = sha(try easyWorkJSON(draftObject))
      var manifest: [[String: Any]] = []
      for (i, file) in draft.attachments.enumerated() {
        let url = store.attachmentURL(file), values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else { throw NSError(domain: "F31File", code: 1) }
        let bytes = try Data(contentsOf: url); guard bytes.count == file.size else { throw NSError(domain: "F31File", code: 2) }
        let copy = run + "-source-\(i + 1).bin"; try bytes.write(to: folder.appendingPathComponent(copy), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        manifest.append(["attachment": try JSONSerialization.jsonObject(with: encoder.encode(file)), "sourceCopy": copy, "sha256": sha(bytes), "size": bytes.count])
        if i == 1 { restoreURL = url; restoreBytes = bytes }
      }
      report["frozenDraft"] = draftObject; report["frozenDraftSHA"] = frozenSHA; report["manifest"] = manifest
      try save("ready-frozen-before-send")
      let permitSuffix = normalRetry ? "-normal-send-permit.json" : "-failure-send-permit.json"
      let sendURL = folder.appendingPathComponent(run + permitSuffix)
      try await wait("single exact original draft send permit", seconds: 600) { FileManager.default.fileExists(atPath: sendURL.path) }
      let sp = try permit(permitSuffix)
      guard sp["caseId"] as? String == run, sp["threadId"] as? String == created.id, sp["frozenDraftSHA"] as? String == frozenSHA, sp[normalRetry ? "allowSingleNormalSend" : "allowSingleFailureSend"] as? Bool == true, store.draft == draft, store.selectedThreadId == created.id else { throw NSError(domain: "F31SendPermit", code: 1) }
      let secondURL = try XCTUnwrap(restoreURL), bytes = try XCTUnwrap(restoreBytes)
      guard bytes.count > 1, try Data(contentsOf: secondURL) == bytes else { throw NSError(domain: "F31File", code: 3) }
      let buttons = views(window).compactMap { $0 as? UIButton }.filter { $0.accessibilityIdentifier == "send-message" && $0.window === window && $0.isEnabled && !$0.isHidden }
      guard buttons.count == 1 else { throw NSError(domain: "F31SendControl", code: 1) }
      if normalRetry {
        // Successful submission owns source retention/cleanup from this point.
        // Do not restore or rewrite a source that production legitimately releases.
        restoreURL = nil
        let token = try XCTUnwrap(c["replyToken"] as? String)
        guard !token.isEmpty, token.hasPrefix("F31_26b995b4_"), body.contains(token) else { throw NSError(domain: "F31ReplyToken", code: 1) }
        let beforeEvents = await channel.eventSequence
        report["incomingEventSequenceBeforeSend"] = beforeEvents
        report["normalSendActionAttempted"] = true; try save("single-normal-send-issued")
        buttons[0].sendActions(for: .touchUpInside)
        let deadline = Date().addingTimeInterval(300)
        var disclosureHandled = false, samples: [[String: Any]] = [], previous = "", lastSave = Date.distantPast
        while Date() < deadline {
          if let request = store.aiSharingRequest, !disclosureHandled {
            guard request.targetKey == key, store.selectedThreadId == created.id else { throw NSError(domain: "F31Disclosure", code: 3) }
            store.allowAISharing(); disclosureHandled = true
            try await wait("owned fixture consent context closed") { store.aiSharingRequest == nil && store.sheet == nil }
            newSharingGrantIds = (store.saved.aiSharingGrants ?? []).filter { !oldSharingIds.contains($0.id) }.map(\.id)
            let again = views(window).compactMap { $0 as? UIButton }.filter { $0.accessibilityIdentifier == "send-message" && $0.window === window && $0.isEnabled && !$0.isHidden }
            guard again.count == 1, store.draft == draft else { throw NSError(domain: "F31Disclosure", code: 4) }
            report["temporaryOwnedFixtureConsentContextOnlyNotF43GUI"] = true
            again[0].sendActions(for: .touchUpInside)
          }
          if report["actualTurnStartOperationId"] == nil {
            // Observe the production recovery journal. No local-dev UI mode,
            // injected operation ID or replay endpoint is needed.
            let fresh = (store.saved.replyRecovery ?? [:]).filter { oldRecovery[$0.key] == nil && $0.value.original.threadId == created.id }
            guard fresh.count <= 1 else { throw NSError(domain: "F31ReplyOperation", code: 1) }
            if let (id, recovery) = fresh.first {
              let reply = recovery.original
              guard reply.clientMessageId == id, reply.hostId == client.id, reply.text == body,
                reply.sourceAttachments == draft.attachments, recovery.target == target,
                recovery.fingerprint == (try ReplyRecoveryPolicy.fingerprint(reply, target: target)) else { throw NSError(domain: "F31ReplyOperation", code: 2) }
              ownedReply = id
              let name = run + "-actual-reply-intent-private.json"
              try encoder.encode(recovery).write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
              report["actualTurnStartOperationId"] = id; report["actualReplyIntentFile"] = name
            }
          }
          if store.isRunning, let partial = store.messages.last(where: { $0.role == "assistant" && !$0.text.isEmpty }), partial.text != previous, samples.count < 32 {
            let rendered = views(window).compactMap { $0 as? HostedNativeMarkdownFormattingStateView }.filter { $0.window === window && !$0.sourceText.isEmpty && partial.text.contains($0.sourceText) }
            if !rendered.isEmpty { samples.append(["characters": partial.text.count, "sha256": sha(Data(partial.text.utf8)), "messageId": partial.id, "runId": partial.runId ?? "", "running": true, "productionMarkdownViews": rendered.count]); previous = partial.text }
          }
          if Date().timeIntervalSince(lastSave) >= 2 {
            report["actualCurrentReadLoopState"] = store.hostedEasyWorkReadLoopState
            report["renderedRunningSamples"] = samples; try save("observing-original-normal-send-without-manual-refresh"); lastSave = Date()
          }
          if let reply = store.messages.first(where: { $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == token }),
            !store.isRunning, !store.sendingKeys.contains(key), ["complete", "completed"].contains(reply.status ?? "") {
            report["actualFinalRunId"] = reply.runId; report["actualFinalMessageId"] = reply.id; report["actualFinalReply"] = reply.text
            break
          }
          if !store.isRunning, !store.sendingKeys.contains(key), store.runtime?.turnId != nil,
            ["failed", "cancelled", "interrupted"].contains(store.runtime?.state ?? "") {
            report["actualTerminalFailureState"] = store.runtime?.state; report["actualTerminalError"] = store.runtime?.error
            throw NSError(domain: "F31ActualModelFailure", code: 1)
          }
          try await Task.sleep(for: .milliseconds(20))
        }
        guard report["actualFinalReply"] != nil, store.selectedThreadId == created.id, store.draft.text.isEmpty, store.draft.attachments.isEmpty,
          store.messages.filter({ $0.role == "user" && $0.text == body }).count == 1 else { throw NSError(domain: "F31ActualReplyWait", code: 1) }
        report["incomingEventSequenceAfterSend"] = await channel.eventSequence
        report["renderedRunningSamples"] = samples
        report["realTimeGrowingStreamQualified"] = samples.count >= 2 && Set(samples.compactMap { $0["sha256"] as? String }).count >= 2
        report["noExaminerManualRefreshAfterSend"] = true
        report["normalDraftClearedByProduction"] = true
        for (k, v) in oldDrafts where k != key { guard store.drafts.values[k] == v else { throw NSError(domain: "F31OldDraft", code: 2) } }
        try await wait("actual final production Markdown view rendered", seconds: 20) {
          views(window).compactMap { $0 as? HostedNativeMarkdownFormattingStateView }.contains { $0.window === window && $0.sourceText.trimmingCharacters(in: .whitespacesAndNewlines) == token }
        }
        try await Task.sleep(for: .milliseconds(350))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        let data = try XCTUnwrap(image.pngData()), name = run + "-normal-completed-reply.png"
        try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        report["normalReplyScreenshot"] = name; report["normalReplyScreenshotSHA"] = sha(data)
        report["normalOriginalAttachmentSendObservedRequiresIndependentDesktopAndPixels"] = true
        try save("normal-original-send-terminal-await-independent-desktop-and-pixels")
      } else {
      truncatedBytes = Data(bytes.dropLast())
      try XCTUnwrap(truncatedBytes).write(to: secondURL, options: [.atomic, .completeFileProtectionUnlessOpen])
      report["sendActionAttempted"] = true; try save("failure-send-issued")
      buttons[0].sendActions(for: .touchUpInside)
      let attemptDeadline = Date().addingTimeInterval(120)
      var progressCaptured = false, disclosureHandled = false
      while Date() < attemptDeadline && (store.error == nil || store.sendingKeys.contains(key)) {
        if let request = store.aiSharingRequest, !disclosureHandled {
          guard request.targetKey == key, store.selectedThreadId == created.id else { throw NSError(domain: "F31Disclosure", code: 1) }
          report["temporaryOwnedFixtureConsentContextOnlyNotF43GUI"] = true
          store.allowAISharing(); disclosureHandled = true
          try await wait("fixture consent context closed") { store.aiSharingRequest == nil && store.sheet == nil }
          newSharingGrantIds = (store.saved.aiSharingGrants ?? []).filter { !oldSharingIds.contains($0.id) }.map(\.id)
          let again = views(window).compactMap { $0 as? UIButton }.filter { $0.accessibilityIdentifier == "send-message" && $0.window === window && $0.isEnabled && !$0.isHidden }
          guard again.count == 1, store.draft == draft else { throw NSError(domain: "F31Disclosure", code: 2) }
          again[0].sendActions(for: .touchUpInside)
        }
        if !progressCaptured, store.targetKey == key, let progress = store.transferStatus, progress.contains(draft.attachments[0].name) {
          let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
          let data = try XCTUnwrap(image.pngData()), name = run + "-first-upload-progress.png"
          try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
          report["actualRenderedProgressState"] = progress; report["progressScreenshot"] = name
          report["progressScreenshotSHA"] = sha(data); progressCaptured = true
        }
        try await Task.sleep(for: .milliseconds(10))
      }
      guard store.error != nil, !store.sendingKeys.contains(key) else { throw NSError(domain: "F31Wait", code: 2) }
      let actualError = try XCTUnwrap(store.error)
      func alerts(_ controller: UIViewController) -> [UIAlertController] {
        let current = (controller as? UIAlertController).map { [$0] } ?? []
        return current + controller.children.flatMap(alerts) + (controller.presentedViewController.map(alerts) ?? [])
      }
      try await wait("actual production error alert rendered", seconds: 20) {
        guard let root = window.rootViewController else { return false }
        return alerts(root).contains { $0.message == actualError && $0.viewIfLoaded?.window != nil && !$0.isBeingPresented && !$0.isBeingDismissed }
      }
      try await Task.sleep(for: .milliseconds(350))
      report["actualErrorAlertRenderedBeforeScreenshot"] = true
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      let screenshot = try XCTUnwrap(image.pngData()), screenshotName = run + "-failure.png"
      try screenshot.write(to: folder.appendingPathComponent(screenshotName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["failureScreenshot"] = screenshotName; report["failureScreenshotSHA"] = sha(screenshot); report["error"] = store.error
      guard store.draft == draft, try Data(contentsOf: secondURL).count == bytes.count - 1 else { throw NSError(domain: "F31FailureProtection", code: 1) }
      try bytes.write(to: secondURL, options: [.atomic, .completeFileProtectionUnlessOpen]); guard try Data(contentsOf: secondURL) == bytes else { throw NSError(domain: "F31Restore", code: 1) }; restoreURL = nil
      for (k, v) in oldDrafts { guard store.drafts.values[k] == v else { throw NSError(domain: "F31OldDraft", code: 1) } }
      for (k, v) in oldRecovery { guard try encoder.encode(store.saved.replyRecovery?[k]) == encoder.encode(v) else { throw NSError(domain: "F31OldRecovery", code: 1) } }
      guard try CredentialVault.existingToken("easywork-device-identity") == identity else { throw NSError(domain: "F31Identity", code: 1) }
      report["secondSourceRestoredSHA"] = sha(bytes); report["ownedFailedDraftRetainedForExplicitFutureRetry"] = true
      report["progressScreenshotRequiresIndependentPixelReview"] = true
      guard progressCaptured || resumeThread != nil else { throw NSError(domain: "F31ProgressNotCaptured", code: 1) }
      if resumeThread != nil { report["originalProgressAlreadyQualifiedFromR3968"] = true }
      try save("failure-observed-source-restored-awaiting-independent-verification")
      }
      for (k, v) in oldRecovery {
        guard try encoder.encode(store.saved.replyRecovery?[k]) == encoder.encode(v) else { throw NSError(domain: "F31OldRecovery", code: 2) }
      }
    } catch {
      caught = error; report["failureDescription"] = error.localizedDescription
    }
    if let url = restoreURL, let bytes = restoreBytes {
      do {
        let current = try Data(contentsOf: url)
        guard current == bytes || current == truncatedBytes,
          (try url.resourceValues(forKeys: [.isSymbolicLinkKey])).isSymbolicLink != true else { throw NSError(domain: "F31UnknownOwnedFileChange", code: 1) }
        if current != bytes { try bytes.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen]) }
        report["catchRestoreVerified"] = (try Data(contentsOf: url)) == bytes
      } catch { report["catchRestoreError"] = error.localizedDescription; if caught == nil { caught = error } }
    }
    if let store = storeToRestore, let thread = original {
      for id in newSharingGrantIds { store.revokeAISharing(id) }
      if let ownError = report["error"] as? String, store.error == ownError { store.error = nil }
      _ = thread
      restoreOriginalNavigation?()
      do {
        guard try protectedCheck?() == true else { throw NSError(domain: "F31OriginalProtection", code: 1) }
        report["allOriginalProtectedStateMatchedFinally"] = true
      } catch { report["originalProtectionError"] = error.localizedDescription; if caught == nil { caught = error } }
    }
    if !normalRetry { report["newModelsOrBusinessSubmissions"] = 0 }
    report["maximumNewModelInputsAuthorized"] = normalRetry ? 1 : 0
    try save(caught == nil ? (normalRetry ? "passed-native-normal-original-retry-await-independent-Desktop-and-pixels" : "passed-native-failure-phase-only-await-independent-Desktop-and-pixels") : "failed-prefix-retained-do-not-replay")
    if let caught { throw caught }
  }

  @MainActor func testOriginalCompletedAttachmentRowsWithoutEmptyBubbles() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled, ProcessInfo.processInfo.arguments.contains("-history-scroll-probe"),
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      c["originalOperationId"] as? String == "4DBE6F6A-828F-48C1-A3A7-6C5144709246" else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: activated)) as? [String: Any])
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation], let after = saved.replyRecovery?[ownOperation] {
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    var caught: Error?
    do {
      let channel = try await EasyWorkSessions.shared.session(client), context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary), (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F31RenderLAN", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread });store.hostedEasyWorkProductUIPresented = true;store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false);await store.loadHistory(threadId: ownThread)
      let body = try XCTUnwrap(c["originalBody"] as? String), token = try XCTUnwrap(c["replyToken"] as? String)
      try await wait("original completed history and two remote attachment rows", seconds: 90) { store.selectedThreadId == ownThread && !store.historyLoading && store.messages.contains { $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == token } }
      let attachmentRows = store.messages.filter { $0.role == "user" && $0.text.isEmpty && $0.files.isEmpty && !($0.attachments ?? []).isEmpty }
      guard attachmentRows.count == 2, store.messages.filter({ $0.role == "user" && $0.text == body }).count == 1 else { throw NSError(domain: "F31RenderHistory", code: 1) }
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      let ids = Set(attachmentRows.map(\.id))
      try await wait("real body bubble probe and final Markdown rendered", seconds: 20) {
        let bubbles = views(window).compactMap { $0 as? HostedNativeUserBubbleStateView }.filter { $0.window === window }
        let replies = views(window).compactMap { $0 as? HostedNativeMarkdownFormattingStateView }.filter { $0.window === window }
        return bubbles.contains { $0.sourceText == body } && replies.contains { $0.sourceText.trimmingCharacters(in: .whitespacesAndNewlines) == token }
      }
      try await Task.sleep(for: .milliseconds(350))
      let bubbles = views(window).compactMap { $0 as? HostedNativeUserBubbleStateView }.filter { $0.window === window }
      guard bubbles.filter({ ids.contains($0.messageId) }).isEmpty, bubbles.contains(where: { $0.sourceText == body }) else { throw NSError(domain: "F31UnexpectedEmptyBubble", code: 1) }
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      let data = try XCTUnwrap(image.pngData()), name = run + "-completed-attachments-without-empty-bubbles.png"
      try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["screenshot"] = name;report["screenshotSHA256"] = sha(data);report["attachmentOnlyMessageIds"] = Array(ids).sorted()
      report["actualBodyBubbleProbePresent"] = true;report["actualAttachmentOnlyEmptyBubbleProbes"] = 0;report["originalCompletedReplyRendered"] = true
      report["originalReplyRunId"] = store.messages.first { $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == token }?.runId
      try save("original-completed-attachments-rendered-await-independent-pixels")
    } catch { caught = error;report["failureDescription"] = error.localizedDescription }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values == oldDrafts, store.selectedThreadId == originalThread else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "passed-original-completed-attachment-rows-no-empty-bubbles" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

  /// Current generated sources; local production controls only, never sends.
  @MainActor func testCurrentGeneratedSuggestionAdoptUndoAndTargetInvalidation() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Paired physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["newImportsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C",
      c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      let fixtures = c["fixtures"] as? [[String: Any]], fixtures.count == 2,
      fixtures[0]["threadId"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      fixtures[1]["threadId"] as? String == "4938d960-8f13-4367-9811-9d11556c6d09"
    else { throw XCTSkip("Explicit current two-source zero-input configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F69ExistingCase", code: 1) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false, "navigationRoute": "normal Store.openThread; no sidebar touch claimed"]
    func save(_ stage: String) throws { report["status"] = stage;report["stage"] = stage;try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { report["failedWait"] = label;try save("failed-wait");throw NSError(domain: "F69Wait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    try save("awaiting-original-app")
    try await wait("original App foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let permit = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("same original PID activation permit", seconds: 120) { FileManager.default.fileExists(atPath: permit.path) }
    let activated = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: permit)) as? [String: Any])
    guard activated["caseId"] as? String == run, activated["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activated["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F69Activation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen, store.drafts.suggestionUndo == nil else { throw NSError(domain: "F69Target", code: 1) }
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity"), voice = try encoder.encode(VoiceMigrationSafety.snapshot())
    let old = store.saved, oldDrafts = store.drafts.values, oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let priorThread = try XCTUnwrap(store.selectedThreadId), prior = try XCTUnwrap(store.threads.first { $0.id == priorThread })
    var ownKeys = Set<String>(), screenshots: [String] = [], sources: [[String: Any]] = [], caught: Error?
    func protectedState(_ savedValue: SavedSession, drafts: [String: Draft]) throws -> String {
      var saved = savedValue;saved.drafts = drafts
      for key in ownKeys {
        let value = drafts[key] ?? Draft(), original = oldDrafts[key] ?? Draft()
        guard value == original, value.text.isEmpty, value.attachments.isEmpty else { throw NSError(domain: "F69OwnDraftProtection", code: 1) }
        if oldDrafts[key] == nil { saved.drafts.removeValue(forKey: key) }
      }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
    func windows() -> [UIWindow] { window.windowScene?.windows.filter { !$0.isHidden && $0.alpha > 0.01 } ?? [window] }
    func settled() -> Bool {
      var seen = Set<ObjectIdentifier>()
      func inspect(_ controller: UIViewController) -> Bool {
        guard seen.insert(ObjectIdentifier(controller)).inserted else { return true }
        return !controller.isBeingPresented && !controller.isBeingDismissed && controller.transitionCoordinator == nil && controller.children.allSatisfy(inspect) && (controller.presentedViewController.map(inspect) ?? true)
      }
      return windows().compactMap(\.rootViewController).allSatisfy(inspect)
    }
    func button(_ id: String) -> UIButton? {
      guard settled() else { return nil }
      let candidates = windows().flatMap(views).compactMap { $0 as? UIButton }.filter { b in
        guard b.accessibilityIdentifier == id, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01, let owner = b.window, owner.windowScene?.activationState == .foregroundActive else { return false }
        let center = b.convert(CGPoint(x: b.bounds.midX, y: b.bounds.midY), to: owner)
        guard owner.bounds.contains(center), let hit = owner.hitTest(center, with: nil) else { return false }
        return hit === b || hit.isDescendant(of: b)
      }
      return candidates.count == 1 ? candidates[0] : nil
    }
    func activate(_ id: String) async throws { try await wait("actual production button " + id) { button(id) != nil };try XCTUnwrap(button(id)).sendActions(for: .touchUpInside);report["lastProductionButton"] = id }
    func picture(_ suffix: String) async throws {
      try await wait("presentation settled before screenshot") { settled() };try await Task.sleep(for: .milliseconds(350));window.layoutIfNeeded()
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      let name = run + "-" + suffix + ".png";try XCTUnwrap(image.pngData()).write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]);screenshots.append(name);report["screenshots"] = screenshots
    }
    func expected(_ fixture: [String: Any]) throws -> DesktopSuggestion {
      guard let scope = store.nextTurnSuggestionScope, let value = store.desktopSuggestionReader.visibleSuggestion(for: scope) else { throw NSError(domain: "F69SourceNotYetAvailable", code: 1) }
      guard scope.requiresRuntimeSource, scope.threadId == fixture["threadId"] as? String, scope.sourceTurnId == fixture["sourceTurnId"] as? String,
        value.id == fixture["suggestionId"] as? String, value.text == fixture["text"] as? String, value.contentSha256 == fixture["contentSha256"] as? String, value.isRuntimeBound, value.isValid(at: Date()) else { throw NSError(domain: "F69Source", code: 1) }
      return value
    }
    do {
      let channel = try await EasyWorkSessions.shared.session(client), context = try easyWorkObject(await channel.request("runtime/context", [:])), channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary), (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F69LAN", code: 1) }
      await store.refresh(includeHistory: false)
      let entries = try fixtures.map { f in try XCTUnwrap(store.threads.first { $0.id == f["threadId"] as? String && $0.hostId == client.id }) }
      // Inspect both actual target keys before any adoption or draft edit.
      for entry in entries { store.openThread(entry, loadAutomatically: false);let key = store.targetKey;ownKeys.insert(key);guard (oldDrafts[key] ?? Draft()).text.isEmpty, (oldDrafts[key] ?? Draft()).attachments.isEmpty, store.draft.text.isEmpty, store.draft.attachments.isEmpty else { throw NSError(domain: "F69ExistingDraft", code: 1) } }
      report["bothOriginalDraftsEmptyBeforeAnyAdoption"] = true
      report["originalProtectedSHA"] = try protectedState(old, drafts: oldDrafts)
      store.openThread(entries[0], loadAutomatically: false);await store.loadHistory(threadId: entries[0].id);store.hostedEasyWorkProductUIPresented = true
      try await wait("fresh actual generated source and ghost controls", seconds: 90) { (try? expected(fixtures[0])) != nil && button("suggestion-adopt") != nil && button("suggestion-details") != nil }
      let first = try expected(fixtures[0]), beforeRevision = store.suggestionDraftSnapshot.revision
      sources.append(["threadId": entries[0].id, "sourceTurnId": fixtures[0]["sourceTurnId"]!, "suggestionId": first.id, "text": first.text, "sha256": first.contentSha256, "expiresAt": first.expiresAt])
      try await picture("actual-ghost");try await activate("suggestion-details")
      try await wait("actual full preview") { button("suggestion-details-close") != nil && button("suggestion-apply") != nil };try await picture("actual-full-preview")
      try await activate("suggestion-details-close");try await wait("preview normally dismissed") { window.rootViewController?.presentedViewController == nil && button("suggestion-adopt") != nil }
      try await activate("suggestion-adopt");try await wait("actual adoption changes local draft") { store.draft.text == first.text && button("suggestion-undo") != nil }
      guard store.draft.attachments.isEmpty else { throw NSError(domain: "F69Adoption", code: 1) };try await picture("actual-adopted")
      try await activate("suggestion-undo");try await wait("actual undo returns empty local draft") { store.draft.text.isEmpty && store.drafts.suggestionUndo == nil && button("suggestion-adopt") != nil };try await picture("actual-undone")
      report["localDraftRevisionBefore"] = beforeRevision;report["localDraftRevisionAfter"] = store.suggestionDraftSnapshot.revision
      let oldScope = try XCTUnwrap(store.nextTurnSuggestionScope), oldValue = try expected(fixtures[0]), oldAction = DesktopSuggestionDraftAction(scope: oldScope, suggestion: oldValue, draft: store.suggestionDraftSnapshot)
      store.openThread(entries[1], loadAutomatically: false)
      guard store.desktopSuggestionReader.suggestion == nil, store.draft.text.isEmpty else { throw NSError(domain: "F69NavigationClear", code: 1) }
      report["oldReaderClearedSynchronouslyOnProductionNavigation"] = true
      await store.loadHistory(threadId: entries[1].id)
      try await wait("other original generated source", seconds: 90) { (try? expected(fixtures[1])) != nil && button("suggestion-adopt") != nil }
      let second = try expected(fixtures[1]);sources.append(["threadId": entries[1].id, "sourceTurnId": fixtures[1]["sourceTurnId"]!, "suggestionId": second.id, "text": second.text, "sha256": second.contentSha256, "expiresAt": second.expiresAt])
      guard !store.adoptDesktopSuggestion(oldAction, mode: .fill, currentSuggestion: second), store.draft.text.isEmpty else { throw NSError(domain: "F69OldAction", code: 1) }
      report["oldSourceActionRejectedByProductionAdoptionBoundary"] = true;try await picture("actual-new-source")
      store.openThread(entries[0], loadAutomatically: false);guard store.desktopSuggestionReader.suggestion == nil else { throw NSError(domain: "F69ReturnClear", code: 1) }
      await store.loadHistory(threadId: entries[0].id);try await wait("original current source returned", seconds: 90) { (try? expected(fixtures[0])) != nil && button("suggestion-adopt") != nil };try await picture("actual-source-returned")
      report["actualSources"] = sources;report["actualPreviewAdoptUndoAndTargetInvalidation"] = true
    } catch { caught = error;report["failureDescription"] = error.localizedDescription }
    // Only undo this case's exact unchanged local adoption. Never overwrite a draft snapshot.
    if let undo = store.drafts.suggestionUndo, ownKeys.contains(undo.applied.targetKey), undo.originalText.isEmpty {
      if !store.undoDesktopSuggestion(undo), caught == nil { caught = NSError(domain: "F69CleanupUndo", code: 1) }
      report["cleanupUsedProductionUndoOnly"] = true
    }
    store.openThread(prior, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState(store.saved, drafts: store.drafts.values) == protectedState(old, drafts: oldDrafts), try encoder.encode(VoiceMigrationSafety.snapshot()) == voice,
        try CredentialVault.existingToken(connection.credentialRef) == credential, try CredentialVault.existingToken("easywork-device-identity") == identity,
        store.selectedThreadId == priorThread, store.drafts.suggestionUndo == nil else { throw NSError(domain: "F69Protection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true;report["ownNormalEmptyDraftAndRevisionAdvancementRecordedNotRestored"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "passed-current-generated-suggestion-controls-and-target-invalidation" : "failed-current-suggestion-no-input")
    if let caught { throw caught }
  }

}

import QuickLook

extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testOriginalTextResourceDownloadQuickLook() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      c["originalOperationId"] as? String == "4DBE6F6A-828F-48C1-A3A7-6C5144709246",
      c["newImportsAuthorized"] as? Int == 0, c["resourceId"] as? String != nil else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: activated)) as? [String: Any])
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation], let after = saved.replyRecovery?[ownOperation] {
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    var caught: Error?
    var casePreview: QLPreviewController?
    do {
      let channel = try await EasyWorkSessions.shared.session(client), context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary), (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F31RenderLAN", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread });store.hostedEasyWorkProductUIPresented = true;store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false);await store.loadHistory(threadId: ownThread)
      let filename = "f31-26b995b4.txt", expected = "ab3f88a1fcb91d627e098d24ec22471ab86a19b1c53591aa43abacb8365eee6c"
      let resource = try XCTUnwrap(c["resourceId"] as? String)
      func matchesOriginal(_ value: RemoteAttachment) -> Bool {
        guard value.resourceIdentity == resource, value.threadId == ownThread, value.name == filename else { return false }
        return value.sha256 == expected && value.sizeBytes == 1048628
      }
      func originalAttachments() -> [RemoteAttachment] { store.messages.flatMap { (message: ChatMessage) -> [RemoteAttachment] in message.attachments ?? [] } }
      try await wait("original attachment metadata", seconds: 90) { !store.historyLoading && originalAttachments().contains(where: matchesOriginal) }
      let file: RemoteAttachment = try XCTUnwrap(originalAttachments().first(where: matchesOriginal))
      let provider = try AgentClients.resolve(client)
      let previousCache = try await provider.cachedDownload(client, file: file)
      report["cacheAlreadyPresentBeforeCase"] = previousCache != nil
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      func controllers() -> [UIViewController] {
        var seen = Set<ObjectIdentifier>()
        func visit(_ v: UIViewController) -> [UIViewController] { guard seen.insert(ObjectIdentifier(v)).inserted else { return [] }; return [v] + v.children.flatMap(visit) + (v.presentedViewController.map(visit) ?? []) }
        return (window.windowScene?.windows ?? [window]).compactMap(\.rootViewController).flatMap(visit)
      }
      func settled() -> Bool { controllers().allSatisfy { !$0.isBeingPresented && !$0.isBeingDismissed && $0.transitionCoordinator == nil } }
      func button(_ id: String) -> UIButton? {
        let matches = views(window).compactMap { $0 as? UIButton }.filter { b in
          guard settled(), b.accessibilityIdentifier == id, b.window === window, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
          let rect = b.convert(b.bounds, to: window); guard window.bounds.contains(CGPoint(x: rect.midX, y: rect.midY)), let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) else { return false }
          return hit === b || hit.isDescendant(of: b)
        }; return matches.count == 1 ? matches[0] : nil
      }
      func activate(_ id: String) async throws { try await wait("actual production control " + id) { button(id) != nil }; try XCTUnwrap(button(id)).sendActions(for: .touchUpInside); report["lastProductionControl"] = id }
      // Search only the real mounted message list; no model input or replay.
      if button("download-" + filename) == nil && button("preview-" + filename) == nil {
        let lists = views(window).compactMap { $0 as? UIScrollView }.filter { $0.accessibilityIdentifier == "message-list" && $0.isScrollEnabled }
        let list = try XCTUnwrap(lists.count == 1 ? lists[0] : nil)
        list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
        for _ in 0..<80 {
          window.layoutIfNeeded(); await Task.yield(); try await Task.sleep(for: .milliseconds(100))
          if button("download-" + filename) != nil || button("preview-" + filename) != nil { break }
          if button("history-local-more") != nil { try await activate("history-local-more"); continue }
          let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
          let next = min(bottom, list.contentOffset.y + list.bounds.height * 0.65); if next <= list.contentOffset.y + 1 { break }; list.setContentOffset(CGPoint(x: list.contentOffset.x, y: next), animated: false)
        }
      }
      if previousCache == nil { try await activate("download-" + filename) }
      try await activate("preview-" + filename)
      try await wait("visible real QuickLook", seconds: 90) { settled() && controllers().compactMap { $0 as? QLPreviewController }.filter { $0.viewIfLoaded?.window != nil }.count == 1 }
      let preview = try XCTUnwrap(controllers().compactMap { $0 as? QLPreviewController }.first { $0.viewIfLoaded?.window != nil }), source = try XCTUnwrap(preview.dataSource)
      casePreview = preview
      let url = try XCTUnwrap(source.previewController(preview, previewItemAt: preview.currentPreviewItemIndex).previewItemURL)
      let bytes = try Data(contentsOf: url); guard bytes.count == 1048628, sha(bytes) == expected else { throw NSError(domain: "F32PreviewBytes", code: 1) }
      let cacheValue: URL? = try await provider.cachedDownload(client, file: file)
      let cache: URL = try XCTUnwrap(cacheValue)
      let cacheBytes: Data = try Data(contentsOf: cache)
      let expectedSnapshot = try VerifiedResourceExport(file: file, url: cache).validatedSnapshot()
      guard cacheBytes.count == bytes.count, sha(cacheBytes) == expected, expectedSnapshot.standardizedFileURL == url.standardizedFileURL else { throw NSError(domain: "F32CacheOwnership", code: 1) }
      report["actualQuickLookOriginalTextBytesVerified"] = true; report["actualCacheAndQuickLookSnapshotBytesMatch"] = true; report["resourceId"] = resource; report["sizeBytes"] = bytes.count; report["sha256"] = expected
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }, data = try XCTUnwrap(image.pngData()), name = run + "-actual-text-quicklook.png"
      try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); report["screenshots"] = [name]; report["screenshotSHA256"] = sha(data)
      try save("quicklook-visible-await-independent-system-pixels")
      let pixelPermit = folder.appendingPathComponent(run + "-quicklook-system-pixels.json")
      try await wait("official same-case system screenshot receipt", seconds: 90) { FileManager.default.fileExists(atPath: pixelPermit.path) }
      let pixels = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: pixelPermit)) as? [String: Any])
      guard pixels["caseId"] as? String == run, pixels["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), pixels["officialCoreDeviceScreenshot"] as? Bool == true,
        let pixelSHA = pixels["sha256"] as? String, pixelSHA.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw NSError(domain: "F32SystemPixels", code: 1) }
      report["officialSystemScreenshotSHA256"] = pixelSHA
      let items = controllers().flatMap { ($0.navigationItem.leftBarButtonItems ?? []) + ($0.navigationItem.rightBarButtonItems ?? []) }
      let done = items.filter { $0.isEnabled && ["完成", "Done"].contains($0.title ?? $0.accessibilityLabel ?? "") && $0.action != nil }
      if done.count == 1 {
        guard UIApplication.shared.sendAction(try XCTUnwrap(done[0].action), to: done[0].target, from: done[0], for: nil) else { throw NSError(domain: "F32Close", code: 1) }; report["closeRoute"] = "actual system Done bar item"
      } else {
        preview.dismiss(animated: true); report["closeRoute"] = "controller dismiss fallback; no user click claimed"
      }
      try await wait("QuickLook dismissed") { preview.viewIfLoaded?.window == nil && !controllers().contains { $0 is QLPreviewController } && settled() }
    } catch { caught = error;report["failureDescription"] = error.localizedDescription }
    if let preview = casePreview, preview.viewIfLoaded?.window != nil { preview.dismiss(animated: false); report["failureCleanupRoute"] = "controller dismiss; no user click claimed" }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values == oldDrafts, store.selectedThreadId == originalThread else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "passed-original-text-download-quicklook-await-independent-pixels" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

}

extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testOriginalTextResourceProductionShareSurface() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      c["originalOperationId"] as? String == "4DBE6F6A-828F-48C1-A3A7-6C5144709246",
      c["newImportsAuthorized"] as? Int == 0, c["resourceId"] as? String != nil else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: activated)) as? [String: Any])
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation], let after = saved.replyRecovery?[ownOperation] {
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    var caught: Error?
    var caseActivity: UIActivityViewController?
    do {
      let channel = try await EasyWorkSessions.shared.session(client), context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary), (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F31RenderLAN", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread });store.hostedEasyWorkProductUIPresented = true;store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false);await store.loadHistory(threadId: ownThread)
      let filename = "f31-26b995b4.txt", expected = "ab3f88a1fcb91d627e098d24ec22471ab86a19b1c53591aa43abacb8365eee6c"
      let resource = try XCTUnwrap(c["resourceId"] as? String)
      func matchesOriginal(_ value: RemoteAttachment) -> Bool {
        guard value.resourceIdentity == resource, value.threadId == ownThread, value.name == filename else { return false }
        return value.sha256 == expected && value.sizeBytes == 1048628
      }
      func originalAttachments() -> [RemoteAttachment] { store.messages.flatMap { (message: ChatMessage) -> [RemoteAttachment] in message.attachments ?? [] } }
      try await wait("original attachment metadata", seconds: 90) { !store.historyLoading && originalAttachments().contains(where: matchesOriginal) }
      let file: RemoteAttachment = try XCTUnwrap(originalAttachments().first(where: matchesOriginal))
      let provider = try AgentClients.resolve(client)
      let previousCache = try await provider.cachedDownload(client, file: file)
      report["cacheAlreadyPresentBeforeCase"] = previousCache != nil
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      func controllers() -> [UIViewController] {
        var seen = Set<ObjectIdentifier>()
        func visit(_ v: UIViewController) -> [UIViewController] { guard seen.insert(ObjectIdentifier(v)).inserted else { return [] }; return [v] + v.children.flatMap(visit) + (v.presentedViewController.map(visit) ?? []) }
        return (window.windowScene?.windows ?? [window]).compactMap(\.rootViewController).flatMap(visit)
      }
      func settled() -> Bool { controllers().allSatisfy { !$0.isBeingPresented && !$0.isBeingDismissed && $0.transitionCoordinator == nil } }
      func controls() -> [NSObject] {
        var seen = Set<ObjectIdentifier>(), result: [NSObject] = []
        func visit(_ object: NSObject) {
          guard result.count < 8000, seen.insert(ObjectIdentifier(object)).inserted else { return }
          result.append(object)
          if let view = object as? UIView { view.subviews.forEach(visit) }
          object.accessibilityElements?.compactMap { $0 as? NSObject }.forEach(visit)
          let count = object.accessibilityElementCount()
          if count > 0 && count < 1000 { for index in 0..<count { if let element = object.accessibilityElement(at: index) as? NSObject { visit(element) } } }
        }
        (window.windowScene?.windows ?? [window]).forEach(visit)
        controllers().filter(\.isViewLoaded).forEach { visit($0.view) }
        return result
      }
      // SwiftUI AX objects can expose the public getter without adopting the optional protocol.
      func identifier(_ object: NSObject) -> String {
        if let value = (object as? any UIAccessibilityIdentification)?.accessibilityIdentifier { return value }
        let getter = NSSelectorFromString("accessibilityIdentifier")
        return object.responds(to: getter) ? (object.value(forKey: "accessibilityIdentifier") as? String ?? "") : ""
      }
      func button(_ id: String) -> NSObject? {
        guard settled() else { return nil }
        let matches = controls().filter { object in
          guard identifier(object) == id,
            !object.accessibilityTraits.contains(.notEnabled) else { return false }
          if let b = object as? UIButton {
            guard b.window === window, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
            let rect = b.convert(b.bounds, to: window)
            guard window.bounds.contains(CGPoint(x: rect.midX, y: rect.midY)), let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) else { return false }
            return hit === b || hit.isDescendant(of: b)
          }
          let rect = window.convert(object.accessibilityFrame, from: nil), visible = rect.intersection(window.bounds)
          return rect.width > 1 && rect.height > 1 && visible.width >= rect.width * 0.8 && visible.height >= rect.height * 0.8 && window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) != nil
        }
        let elements = matches.filter(\.isAccessibilityElement)
        return elements.count == 1 ? elements[0] : (matches.count == 1 ? matches[0] : nil)
      }
      func activate(_ id: String) async throws {
        let until = Date().addingTimeInterval(60)
        while button(id) == nil && Date() < until {
          let candidates = controls().filter { identifier($0) == id }
          let lists = views(window).compactMap { $0 as? UIScrollView }.filter { $0.accessibilityIdentifier == "message-list" && $0.isScrollEnabled }
          if candidates.count == 1, lists.count == 1 {
            let rect = window.convert(candidates[0].accessibilityFrame, from: nil), scroll = lists[0]
            let viewport = scroll.convert(scroll.bounds, to: window)
            let bottom = max(-scroll.adjustedContentInset.top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
            let offset = min(bottom, max(-scroll.adjustedContentInset.top, scroll.contentOffset.y + rect.midY - viewport.midY))
            if rect.width > 1 && rect.height > 1 && abs(offset - scroll.contentOffset.y) > 1 { scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: offset), animated: false); window.layoutIfNeeded() }
          }
          await Task.yield(); try await Task.sleep(for: .milliseconds(100))
        }
        report["actualShareControlDiagnostics"] = controls().filter { object in
          let value = identifier(object)
          return value == id || value == "preview-f31-26b995b4.txt" || value == "download-f31-26b995b4.txt"
        }.map { object in ["class": String(describing: type(of: object)), "id": identifier(object), "frame": String(describing: object.accessibilityFrame), "element": object.isAccessibilityElement, "traits": String(object.accessibilityTraits.rawValue)] as [String: Any] }
        report["actualCurrentControllerTransitions"] = controllers().map { ["class": String(describing: type(of: $0)), "presenting": $0.isBeingPresented, "dismissing": $0.isBeingDismissed, "transition": $0.transitionCoordinator != nil] as [String: Any] }
        let diagnosticName = run + "-share-control-diagnostic.png"
        let diagnosticImage = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        try XCTUnwrap(diagnosticImage.pngData()).write(to: folder.appendingPathComponent(diagnosticName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        report["screenshots"] = [diagnosticName]; try save("actual-share-control-search-observed")
        guard let control = button(id) else { throw NSError(domain: "F31ShareControlUnavailable", code: 1, userInfo: [NSLocalizedDescriptionKey: "Actual production share control unavailable after bounded observed scroll; diagnostics saved"]) }
        report["productionControlClass"] = String(describing: type(of: control))
        if let native = control as? UIButton { native.sendActions(for: .touchUpInside); report["productionControlRoute"] = "UIButton touchUpInside" }
        else { guard control.accessibilityActivate() else { throw NSError(domain: "F31ShareActivation", code: 1) }; report["productionControlRoute"] = "accessibilityActivate" }
        report["lastProductionControl"] = id
      }
      // Search only the real mounted message list; no model input or replay.
      if button("share-resource-" + filename) == nil {
        let lists = views(window).compactMap { $0 as? UIScrollView }.filter { $0.accessibilityIdentifier == "message-list" && $0.isScrollEnabled }
        let list = try XCTUnwrap(lists.count == 1 ? lists[0] : nil)
        list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
        for _ in 0..<80 {
          window.layoutIfNeeded(); await Task.yield(); try await Task.sleep(for: .milliseconds(100))
          if button("share-resource-" + filename) != nil { break }
          if button("history-local-more") != nil { try await activate("history-local-more"); continue }
          let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
          let next = min(bottom, list.contentOffset.y + list.bounds.height * 0.65); if next <= list.contentOffset.y + 1 { break }; list.setContentOffset(CGPoint(x: list.contentOffset.x, y: next), animated: false)
        }
      }
      guard let cache = previousCache else { throw NSError(domain: "F31ShareRequiresCache", code: 1) }
      let cacheBytes = try Data(contentsOf: cache)
      guard cacheBytes.count == 1048628, sha(cacheBytes) == expected else { throw NSError(domain: "F31ShareCache", code: 1) }
      report["originalCacheBytesVerified"] = true; report["resourceId"] = resource; report["sizeBytes"] = cacheBytes.count; report["sha256"] = expected
      try await activate("share-resource-" + filename)
      try await wait("visible actual system activity controller", seconds: 90) { settled() && controllers().compactMap { $0 as? UIActivityViewController }.filter { $0.viewIfLoaded?.window != nil }.count == 1 }
      let activity = try XCTUnwrap(controllers().compactMap { $0 as? UIActivityViewController }.first { $0.viewIfLoaded?.window != nil }); caseActivity = activity
      report["actualActivityControllerClass"] = String(describing: type(of: activity))
      report["actualControllerClasses"] = controllers().map { String(describing: type(of: $0)) }
      report["actualSystemWindowClasses"] = (window.windowScene?.windows ?? [window]).map { String(describing: type(of: $0)) }
      report["actualSystemAXCancelCandidates"] = controls().filter { ["Cancel", "取消"].contains($0.accessibilityLabel ?? "") }.map { ["class": String(describing: type(of: $0)), "label": $0.accessibilityLabel ?? "", "frame": String(describing: $0.accessibilityFrame), "enabled": !$0.accessibilityTraits.contains(.notEnabled)] as [String: Any] }
      try save("share-visible-await-independent-system-pixels")
      let pixelPermit = folder.appendingPathComponent(run + "-share-system-pixels.json")
      try await wait("official same-case system screenshot receipt", seconds: 90) { FileManager.default.fileExists(atPath: pixelPermit.path) }
      let pixels = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: pixelPermit)) as? [String: Any])
      guard pixels["caseId"] as? String == run, pixels["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), pixels["officialCoreDeviceScreenshot"] as? Bool == true,
        let pixelSHA = pixels["sha256"] as? String, pixelSHA.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw NSError(domain: "F31ShareSystemPixels", code: 1) }
      report["officialSystemScreenshotSHA256"] = pixelSHA
      let candidates = controls().filter { object in
        guard ["Cancel", "取消"].contains(object.accessibilityLabel ?? ""), !object.accessibilityTraits.contains(.notEnabled) else { return false }
        let frame = window.convert(object.accessibilityFrame, from: nil), visible = frame.intersection(window.bounds)
        guard frame.width > 1, frame.height > 1, visible.width >= frame.width * 0.8, visible.height >= frame.height * 0.8 else { return false }
        if let view = object as? UIView { return view.window != nil && !view.isHidden && view.alpha > 0.01 && view.isDescendant(of: activity.view) }
        return false // Only an observed view inside this actual controller can be activated safely.
      }
      if candidates.count == 1, candidates[0].accessibilityActivate() {
        report["closeRoute"] = "observed system Cancel accessibilityActivate"; report["actualSystemCancelActivated"] = true
      } else {
        activity.dismiss(animated: true); report["closeRoute"] = "controller dismiss fallback; no user cancel claimed"; report["actualSystemCancelActivated"] = false
      }
      try await wait("activity dismissed") { activity.viewIfLoaded?.window == nil && !controllers().contains { $0 is UIActivityViewController } && settled() }
    } catch { caught = error;report["failureDescription"] = error.localizedDescription }
    if let activity = caseActivity, activity.viewIfLoaded?.window != nil { activity.dismiss(animated: false); report["failureCleanupRoute"] = "controller dismiss; no user cancel claimed" }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values == oldDrafts, store.selectedThreadId == originalThread else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "passed-original-text-share-surface-await-independent-pixels" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

}

// R4081 is a read-only observer. It never creates a conversation or submits input.
extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testControlledNewRunStreamAndGeneratedResource() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Paired physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
      c["caseId"] as? String == run, c["newModelsAuthorized"] as? Int == 0,
      c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C",
      c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      c["bundleId"] as? String == "com.easyai.agenthub.easyworkacceptance",
      Bundle.main.bundleIdentifier == c["bundleId"] as? String,
      UIDevice.current.userInterfaceIdiom == .phone,
      let thread = c["threadId"] as? String, !thread.isEmpty,
      let project = c["projectId"] as? String, !project.isEmpty,
      let start = c["replyStartMarker"] as? String, !start.isEmpty,
      let end = c["replyEndMarker"] as? String, !end.isEmpty, start != end,
      let name = c["outputName"] as? String, !name.isEmpty, !name.contains("/") else {
      throw XCTSkip("Explicit R4081 read-only configuration required")
    }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "R4081ExistingCase", code: 1) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": Int(ProcessInfo.processInfo.processIdentifier), "bundleId": Bundle.main.bundleIdentifier!, "threadId": thread, "projectId": project, "target": c["target"] as Any, "newModels": 0, "newChats": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws {
      report["stage"] = stage; report["status"] = stage
      try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func abortRequested() throws -> Bool {
      let file = folder.appendingPathComponent(run + "-local-observation-abort.json")
      guard FileManager.default.fileExists(atPath: file.path) else { return false }
      let value = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
      guard value["caseId"] as? String == run, value["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), value["stopOnlyLocalObservation"] as? Bool == true, value["cancelRemote"] as? Bool == false else { throw NSError(domain: "R4081InvalidLocalAbort", code: 1) }
      return true
    }
    func wait(_ label: String, seconds: Double, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { if try abortRequested() { throw NSError(domain: "R4081LocalObservationEnded", code: 1) }; try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "R4081Wait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    try save("awaiting-original-app")
    try await wait("foreground original app", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let permitURL = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official same-process activation", seconds: 120) { FileManager.default.fileExists(atPath: permitURL.path) }
    let permit = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: permitURL)) as? [String: Any])
    guard permit["caseId"] as? String == run, permit["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
      permit["bundleId"] as? String == Bundle.main.bundleIdentifier, permit["deviceUDID"] as? String == c["deviceUDID"] as? String,
      permit["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "R4081Activation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork", connection.target == target, connection.preference == .lanOnly,
      !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "R4081Target", code: 1) }
    let identity = try CredentialVault.existingToken("easywork-device-identity"), credential = try CredentialVault.existingToken(connection.credentialRef)
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, drafts = store.drafts.values
    let oldThread = try XCTUnwrap(store.selectedThreadId), original = try XCTUnwrap(store.threads.first { $0.id == oldThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    func protectedState() throws -> String {
      var saved = store.saved; saved.drafts = store.drafts.values
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState()
    var failure: Error?
    var ownedPreview: QLPreviewController?
    do {
      let channel = try await EasyWorkSessions.shared.session(client)
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary),
        (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "R4081Runtime", code: 1) }
      await store.refresh(includeHistory: false)
      let entry = try XCTUnwrap(store.threads.first { $0.id == thread && $0.projectId == project })
      store.hostedEasyWorkProductUIPresented = true; store.openThread(entry)
      await store.loadHistory(threadId: thread)
      try await wait("production navigation event reader started", seconds: 30) {
        store.selectedThreadId == thread && store.hostedEasyWorkReadLoopState["eventTaskActive"] as? Bool == true
      }
      report["productionNavigationStartsItsOwnEventReader"] = true
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      // SwiftUI's accessibility identifier need not be attached to the backing
      // UIScrollView. Resolve the real chat scroll through its production pull
      // control, as the physical pagination examiner already does.
      func mountedMessageList() -> UIScrollView? {
        let pulls = views(window).filter { String(describing: type(of: $0)) == "EarlierHistoryPullView" && $0.window === window }
        guard pulls.count == 1 else { return nil }
        var ancestor = pulls[0].superview
        while let view = ancestor {
          if let scroll = view as? UIScrollView {
            return scroll.window === window && !scroll.isHidden && scroll.bounds.height > 200 ? scroll : nil
          }
          ancestor = view.superview
        }
        return nil
      }
      report["selectedThreadBeforeMount"] = store.selectedThreadId
      report["selectedProjectBeforeMount"] = store.selectedProjectId
      report["historyLoadingBeforeMount"] = store.historyLoading
      try save("mounting-original-empty-thread-ui")
      try await wait("exact thread and mounted message list", seconds: 60) {
        store.selectedThreadId == thread && store.selectedProjectId == project && !store.historyLoading &&
          mountedMessageList() != nil
      }
      report["runtimeContext"] = context
      report["eventSequence"] = await channel.eventSequence
      report["sourceHistoryIds"] = store.messages.map(\.id)
      report["readLoopState"] = store.hostedEasyWorkReadLoopState
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      let png = try XCTUnwrap(image.pngData()), screenshot = run + "-preflight.png"
      try png.write(to: folder.appendingPathComponent(screenshot), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["preflightScreenshot"] = screenshot; report["preflightScreenshotSHA256"] = sha(png)
      guard store.messages.isEmpty, store.draft.text.isEmpty, store.draft.attachments.isEmpty else { throw NSError(domain: "R4081NewThreadNotEmpty", code: 1) }
      report["listenerReadyForOneOtherDeviceInput"] = true
      try save("listener-ready")
      let began = ProcessInfo.processInfo.systemUptime
      var samples: [[String: Any]] = [], previous = "", lastSaved = Date.distantPast, lastPicture = Date.distantPast
      var completed: ChatMessage?
      let list = try XCTUnwrap(mountedMessageList())
      report["messageListResolvedThroughActualProductionEarlierHistoryPullView"] = true
      // In-progress MessageView is real SwiftUI Text(verbatim:), not MarkdownBody.
      // Capture its actual window pixels. Independent Vision OCR must prove visible
      // changed lines for >=2 frames; Store growth alone never qualifies this clause.
      while ProcessInfo.processInfo.systemUptime - began < 600 {
        if try abortRequested() { throw NSError(domain: "R4081LocalObservationEnded", code: 1) }
        guard store.selectedThreadId == thread, store.client?.id == client.id, store.client?.agentConnection?.target == target, UIApplication.shared.applicationState == .active,
          store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "R4081ListenerTargetChanged", code: 1) }
        if let partial = store.messages.last(where: { $0.role == "assistant" && $0.text.contains(start) }) {
          if store.isRunning, partial.text != previous, samples.count < 8, Date().timeIntervalSince(lastPicture) >= 1.25,
            partial.text.utf16.count >= 120, let runId = partial.runId, !runId.isEmpty {
            if !previous.isEmpty, !partial.text.hasPrefix(previous) { throw NSError(domain: "R4081NonAppendBody", code: 1) }
            let viewport = list.convert(list.bounds, to: window).intersection(CGRect(x: 8, y: 135, width: window.bounds.width - 16, height: window.bounds.height - 395))
            guard viewport.width > 100, viewport.height > 100 else { throw NSError(domain: "R4081Viewport", code: 1) }
            let sampleTime = ProcessInfo.processInfo.systemUptime
            let pixels = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
            if store.isRunning, store.messages.contains(where: { $0.id == partial.id && $0.text == partial.text && $0.runId == runId }) {
              let png = try XCTUnwrap(pixels.pngData()), index = samples.count, imageName = run + "-actual-running-\(index).png", partialName = run + "-partial-\(index).txt"
              try png.write(to: folder.appendingPathComponent(imageName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
              try Data(partial.text.utf8).write(to: folder.appendingPathComponent(partialName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
              samples.append(["uptimeSeconds": sampleTime, "messageId": partial.id, "runId": runId, "runningBeforeAndAfterSynchronousPixelCapture": true,
                "partialUTF16Length": partial.text.utf16.count, "partialUTF8SHA256": sha(Data(partial.text.utf8)), "partialTextFile": partialName,
                "screenshot": imageName, "screenshotSHA256": sha(png), "windowPoints": [window.bounds.width, window.bounds.height],
                "conservativeMessageViewportPoints": [viewport.minX, viewport.minY, viewport.width, viewport.height],
                "visibleTextQualification": "pending independent OCR of these actual pixels; no Store/transparent-view visible claim"])
              previous = partial.text; lastPicture = Date()
            }
          }
          if !store.isRunning, ["complete", "completed"].contains(partial.status ?? ""), partial.text.contains(end) { completed = partial; break }
        }
        if Date().timeIntervalSince(lastSaved) >= 1.5 {
          report["runningPixelCandidates"] = samples; report["readLoopState"] = store.hostedEasyWorkReadLoopState
          try save("observing-one-real-run-no-manual-refresh"); lastSaved = Date()
        }
        if !store.isRunning, ["failed", "interrupted", "cancelled"].contains(store.runtime?.state ?? "") { throw NSError(domain: "R4081ActualRunFailed", code: 1, userInfo: [NSLocalizedDescriptionKey: store.runtime?.error ?? "Original run failed; never send or replay"]) }
        try await Task.sleep(for: .milliseconds(120))
      }
      let final = try XCTUnwrap(completed), finalRun = try XCTUnwrap(final.runId)
      guard !finalRun.isEmpty else { throw NSError(domain: "R4081MissingFinalRun", code: 1) }
      let finalName = run + "-actual-final-reply.txt"
      try Data(final.text.utf8).write(to: folder.appendingPathComponent(finalName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["actualRunId"] = finalRun; report["finalMessageId"] = final.id; report["finalTextFile"] = finalName
      report["finalUTF16Length"] = final.text.utf16.count; report["finalUTF8SHA256"] = sha(Data(final.text.utf8)); report["runningPixelCandidates"] = samples
      report["eventSequenceAfterRun"] = await channel.eventSequence
      try save("completed-run-await-resource-proof")
      let resourcePermit = folder.appendingPathComponent(run + "-resource-read-permit.json")
      try await wait("parent independently verified actual generated file and resource", seconds: 180) { FileManager.default.fileExists(atPath: resourcePermit.path) }
      let approved = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: resourcePermit)) as? [String: Any])
      guard approved["caseId"] as? String == run, approved["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
        approved["threadId"] as? String == thread, approved["runId"] as? String == finalRun, approved["name"] as? String == name,
        let resourceId = approved["resourceId"] as? String, let digest = approved["sha256"] as? String,
        let size = approved["size"] as? Int, size > 0, size <= 2048 else { throw NSError(domain: "R4081ResourcePermit", code: 1) }
      func matches(_ value: RemoteAttachment) -> Bool { value.resourceIdentity == resourceId && value.threadId == thread && value.hostId == client.id && value.name == name && value.sha256 == digest && value.sizeBytes == size }
      try await wait("actual model-registered attachment metadata", seconds: 90) { store.messages.flatMap { $0.attachments ?? [] }.contains(where: matches) }
      let file = try XCTUnwrap(store.messages.flatMap { $0.attachments ?? [] }.first(where: matches)), provider = try AgentClients.resolve(client)
      guard try await provider.cachedDownload(client, file: file) == nil else { throw NSError(domain: "R4081UnexpectedExistingGeneratedCache", code: 1) }
      func identifier(_ object: NSObject) -> String {
        if let value = (object as? any UIAccessibilityIdentification)?.accessibilityIdentifier { return value }
        let getter = NSSelectorFromString("accessibilityIdentifier")
        return object.responds(to: getter) ? (object.value(forKey: "accessibilityIdentifier") as? String ?? "") : ""
      }
      func controls() -> [NSObject] {
        var seen = Set<ObjectIdentifier>(), result: [NSObject] = []
        func visit(_ object: NSObject) {
          guard result.count < 8000, seen.insert(ObjectIdentifier(object)).inserted else { return }; result.append(object)
          if let v = object as? UIView { v.subviews.forEach(visit) }
          object.accessibilityElements?.compactMap { $0 as? NSObject }.forEach(visit)
          let count = object.accessibilityElementCount()
          if count > 0 && count < 1000 { for i in 0..<count { if let value = object.accessibilityElement(at: i) as? NSObject { visit(value) } } }
        }
        (window.windowScene?.windows ?? [window]).forEach(visit); return result
      }
      func button(_ id: String) -> UIButton? {
        let values = views(window).compactMap { $0 as? UIButton }.filter { b in
          guard identifier(b) == id, b.window === window, b.isEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
          let rect = b.convert(b.bounds, to: window), center = CGPoint(x: rect.midX, y: rect.midY)
          guard window.bounds.contains(center), let hit = window.hitTest(center, with: nil) else { return false }; return hit === b || hit.isDescendant(of: b)
        }; return values.count == 1 ? values[0] : nil
      }
      func activate(_ id: String) async throws {
        let until = Date().addingTimeInterval(60)
        while button(id) == nil && Date() < until {
          if let candidate = views(window).compactMap({ $0 as? UIButton }).first(where: { identifier($0) == id }) {
            let frame = candidate.convert(candidate.bounds, to: list)
            let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
            let offset = min(bottom, max(-list.adjustedContentInset.top, frame.midY - list.bounds.height * 0.5))
            list.setContentOffset(CGPoint(x: list.contentOffset.x, y: offset), animated: false); window.layoutIfNeeded()
          } else {
            let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
            list.setContentOffset(CGPoint(x: list.contentOffset.x, y: bottom), animated: false); window.layoutIfNeeded()
          }
          await Task.yield(); try await Task.sleep(for: .milliseconds(100))
        }
        guard let value = button(id) else { throw NSError(domain: "R4081GeneratedControl", code: 1, userInfo: [NSLocalizedDescriptionKey: id]) }
        value.sendActions(for: .touchUpInside)
      }
      try await activate("download-" + name); try await activate("preview-" + name)
      func controllers(_ v: UIViewController) -> [UIViewController] { [v] + v.children.flatMap(controllers) + (v.presentedViewController.map(controllers) ?? []) }
      let root = try XCTUnwrap(window.rootViewController)
      try await wait("actual generated QuickLook", seconds: 90) { controllers(root).contains { $0 is QLPreviewController && $0.viewIfLoaded?.window != nil && !$0.isBeingPresented } }
      let preview = try XCTUnwrap(controllers(root).compactMap { $0 as? QLPreviewController }.first { $0.viewIfLoaded?.window != nil }), source = try XCTUnwrap(preview.dataSource)
      let url = try XCTUnwrap(source.previewController(preview, previewItemAt: preview.currentPreviewItemIndex).previewItemURL)
      ownedPreview = preview
      let bytes = try Data(contentsOf: url), cached = try await provider.cachedDownload(client, file: file), cache = try XCTUnwrap(cached)
      guard bytes.count == size, sha(bytes) == digest, try Data(contentsOf: cache) == bytes,
        try VerifiedResourceExport(file: file, url: cache).validatedSnapshot().standardizedFileURL == url.standardizedFileURL else { throw NSError(domain: "R4081GeneratedBytes", code: 1) }
      let generatedName = run + "-actual-generated-download.bin"; try bytes.write(to: folder.appendingPathComponent(generatedName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["actualGeneratedResourceId"] = resourceId; report["actualGeneratedBytes"] = bytes.count; report["actualGeneratedSHA256"] = digest
      report["actualGeneratedDownloadFile"] = generatedName; report["actualQuickLookOriginalModelBytesVerified"] = true
      try save("generated-quicklook-visible-await-system-pixels")
      let pixelPermit = folder.appendingPathComponent(run + "-generated-resource-system-pixels.json")
      try await wait("official same-case generated-file system screenshot", seconds: 90) { FileManager.default.fileExists(atPath: pixelPermit.path) }
      let receipt = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: pixelPermit)) as? [String: Any])
      guard receipt["caseId"] as? String == run, receipt["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), receipt["officialCoreDeviceScreenshot"] as? Bool == true else { throw NSError(domain: "R4081GeneratedPixels", code: 1) }
      report["officialGeneratedSystemScreenshotSHA256"] = receipt["sha256"]
      preview.dismiss(animated: true); report["closeRoute"] = "controller dismiss; no system Done click claimed"
      try await wait("case QuickLook closed", seconds: 30) { preview.viewIfLoaded?.window == nil }
      report["pixelOCRQualificationStillRequired"] = true
      guard samples.count >= 2 else { throw NSError(domain: "R4081TooFewNonterminalPixelCandidates", code: 1) }
      report["oneOriginalRunResourceAndPixelCandidatesObserved"] = true
    } catch { failure = error; report["failureDescription"] = error.localizedDescription }
    if let preview = ownedPreview, preview.viewIfLoaded?.window != nil {
      preview.dismiss(animated: false); report["failureCleanupRoute"] = "exact case-owned QuickLook controller dismiss; no system gesture claim"
      try? await Task.sleep(for: .milliseconds(300))
    }
    store.openThread(original, loadAutomatically: false); store.selectedProjectId = oldProject; store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id]; store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id]; store.saved.newProjects[client.id] = old.newProjects[client.id]; store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, store.drafts.values == drafts,
        try encoder.encode(VoiceMigrationSafety.snapshot()) == voice,
        try CredentialVault.existingToken("easywork-device-identity") == identity,
        try CredentialVault.existingToken(connection.credentialRef) == credential,
        store.selectedThreadId == oldThread, store.selectedProjectId == oldProject else { throw NSError(domain: "R4081Protection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription; if failure == nil { failure = error } }
    try save(failure == nil ? "completed-controlled-stream-generated-resource-OCR-pending" : "failed-controlled-listener-preserve-no-send")
    if let failure { throw failure }
  }
}

extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testGeneratedTextResourceDownloadQuickLook() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "e03ef1b1-13dc-48cf-8ab2-3c256dc8ea2f",
      c["originalOperationId"] as? String == "5286da2e-5ebd-424f-adbb-8f5a8dca6b1b",
      c["newImportsAuthorized"] as? Int == 0, c["originalRunId"] as? String == "45643148-44a6-4aa3-add0-28487b55080d" else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: activated)) as? [String: Any])
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation], let after = saved.replyRecovery?[ownOperation] {
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    var caught: Error?
    var casePreview: QLPreviewController?
    do {
      let channel = try await EasyWorkSessions.shared.session(client), context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary), (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F31RenderLAN", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread });store.hostedEasyWorkProductUIPresented = true;store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false);await store.loadHistory(threadId: ownThread)
      let filename = try XCTUnwrap(c["name"] as? String), expected = try XCTUnwrap(c["sha256"] as? String), expectedSize = try XCTUnwrap(c["size"] as? Int)
      guard filename == "ew-generated-b9356d6c.txt", expectedSize == 102 else { throw NSError(domain: "GeneratedResourceConfig", code: 1) }
      var resource = ""
      func matchesOriginal(_ value: RemoteAttachment) -> Bool {
        guard value.threadId == ownThread, value.name == filename else { return false }
        return value.sha256 == expected && value.sizeBytes == expectedSize
      }
      func originalAttachments() -> [RemoteAttachment] { store.messages.flatMap { (message: ChatMessage) -> [RemoteAttachment] in message.attachments ?? [] } }
      try await wait("original attachment metadata", seconds: 90) { !store.historyLoading && originalAttachments().contains(where: matchesOriginal) }
      let file: RemoteAttachment = try XCTUnwrap(originalAttachments().first(where: matchesOriginal))
      resource = file.resourceIdentity
      report["originalRunId"] = c["originalRunId"]
      guard store.messages.contains(where: { $0.role == "assistant" && $0.runId == c["originalRunId"] as? String && $0.text.contains(c["replyEndMarker"] as? String ?? "") }) else { throw NSError(domain: "GeneratedResourceOriginalRun", code: 1) }
      let provider = try AgentClients.resolve(client)
      let previousCache = try await provider.cachedDownload(client, file: file)
      report["cacheAlreadyPresentBeforeCase"] = previousCache != nil
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      func controllers() -> [UIViewController] {
        var seen = Set<ObjectIdentifier>()
        func visit(_ v: UIViewController) -> [UIViewController] { guard seen.insert(ObjectIdentifier(v)).inserted else { return [] }; return [v] + v.children.flatMap(visit) + (v.presentedViewController.map(visit) ?? []) }
        return (window.windowScene?.windows ?? [window]).compactMap(\.rootViewController).flatMap(visit)
      }
      func settled() -> Bool { controllers().allSatisfy { !$0.isBeingPresented && !$0.isBeingDismissed && $0.transitionCoordinator == nil } }
      func button(_ id: String) -> UIButton? {
        let matches = views(window).compactMap { $0 as? UIButton }.filter { b in
          guard settled(), b.accessibilityIdentifier == id, b.window === window, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
          let rect = b.convert(b.bounds, to: window); guard window.bounds.contains(CGPoint(x: rect.midX, y: rect.midY)), let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) else { return false }
          return hit === b || hit.isDescendant(of: b)
        }; return matches.count == 1 ? matches[0] : nil
      }
      func activate(_ id: String) async throws { try await wait("actual production control " + id) { button(id) != nil }; try XCTUnwrap(button(id)).sendActions(for: .touchUpInside); report["lastProductionControl"] = id }
      // Search only the real mounted message list; no model input or replay.
      if button("download-" + filename) == nil && button("preview-" + filename) == nil {
        let pull = try XCTUnwrap(views(window).first { String(describing: type(of: $0)) == "EarlierHistoryPullView" })
        var ancestor = pull.superview, chatList: UIScrollView?
        while let view = ancestor { if let list = view as? UIScrollView { chatList = list; break }; ancestor = view.superview }
        let list = try XCTUnwrap(chatList)
        list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
        for _ in 0..<80 {
          window.layoutIfNeeded(); await Task.yield(); try await Task.sleep(for: .milliseconds(100))
          if button("download-" + filename) != nil || button("preview-" + filename) != nil { break }
          if button("history-local-more") != nil { try await activate("history-local-more"); continue }
          let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
          let next = min(bottom, list.contentOffset.y + list.bounds.height * 0.65); if next <= list.contentOffset.y + 1 { break }; list.setContentOffset(CGPoint(x: list.contentOffset.x, y: next), animated: false)
        }
      }
      if previousCache == nil { try await activate("download-" + filename) }
      try await activate("preview-" + filename)
      try await wait("visible real QuickLook", seconds: 90) { settled() && controllers().compactMap { $0 as? QLPreviewController }.filter { $0.viewIfLoaded?.window != nil }.count == 1 }
      let preview = try XCTUnwrap(controllers().compactMap { $0 as? QLPreviewController }.first { $0.viewIfLoaded?.window != nil }), source = try XCTUnwrap(preview.dataSource)
      casePreview = preview
      let url = try XCTUnwrap(source.previewController(preview, previewItemAt: preview.currentPreviewItemIndex).previewItemURL)
      let bytes = try Data(contentsOf: url); guard bytes.count == expectedSize, sha(bytes) == expected else { throw NSError(domain: "F32PreviewBytes", code: 1) }
      let cacheValue: URL? = try await provider.cachedDownload(client, file: file)
      let cache: URL = try XCTUnwrap(cacheValue)
      let cacheBytes: Data = try Data(contentsOf: cache)
      let expectedSnapshot = try VerifiedResourceExport(file: file, url: cache).validatedSnapshot()
      guard cacheBytes.count == bytes.count, sha(cacheBytes) == expected, expectedSnapshot.standardizedFileURL == url.standardizedFileURL else { throw NSError(domain: "F32CacheOwnership", code: 1) }
      let downloadedName = run + "-actual-generated-download.bin"; try bytes.write(to: folder.appendingPathComponent(downloadedName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); report["actualGeneratedDownloadFile"] = downloadedName
      report["actualQuickLookOriginalTextBytesVerified"] = true; report["actualCacheAndQuickLookSnapshotBytesMatch"] = true; report["resourceId"] = resource; report["sizeBytes"] = bytes.count; report["sha256"] = expected
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }, data = try XCTUnwrap(image.pngData()), name = run + "-actual-text-quicklook.png"
      try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); report["screenshots"] = [name]; report["screenshotSHA256"] = sha(data)
      try save("quicklook-visible-await-independent-system-pixels")
      let pixelPermit = folder.appendingPathComponent(run + "-quicklook-system-pixels.json")
      try await wait("official same-case system screenshot receipt", seconds: 90) { FileManager.default.fileExists(atPath: pixelPermit.path) }
      let pixels = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: pixelPermit)) as? [String: Any])
      guard pixels["caseId"] as? String == run, pixels["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), pixels["officialCoreDeviceScreenshot"] as? Bool == true,
        let pixelSHA = pixels["sha256"] as? String, pixelSHA.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw NSError(domain: "F32SystemPixels", code: 1) }
      report["officialSystemScreenshotSHA256"] = pixelSHA
      let items = controllers().flatMap { ($0.navigationItem.leftBarButtonItems ?? []) + ($0.navigationItem.rightBarButtonItems ?? []) }
      let done = items.filter { $0.isEnabled && ["完成", "Done"].contains($0.title ?? $0.accessibilityLabel ?? "") && $0.action != nil }
      if done.count == 1 {
        guard UIApplication.shared.sendAction(try XCTUnwrap(done[0].action), to: done[0].target, from: done[0], for: nil) else { throw NSError(domain: "F32Close", code: 1) }; report["closeRoute"] = "actual system Done bar item"
      } else {
        preview.dismiss(animated: true); report["closeRoute"] = "controller dismiss fallback; no user click claimed"
      }
      try await wait("QuickLook dismissed") { preview.viewIfLoaded?.window == nil && !controllers().contains { $0 is QLPreviewController } && settled() }
    } catch { caught = error;report["failureDescription"] = error.localizedDescription }
    if let preview = casePreview, preview.viewIfLoaded?.window != nil { preview.dismiss(animated: false); report["failureCleanupRoute"] = "controller dismiss; no user click claimed" }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values == oldDrafts, store.selectedThreadId == originalThread else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "passed-generated-text-download-quicklook-await-independent-pixels" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

}

// Prepared excerpt only; integrate only after review. Existing file imports apply.
extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testGeneratedResourceCurrentVersionAndLivePreview() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "e03ef1b1-13dc-48cf-8ab2-3c256dc8ea2f",
      c["originalOperationId"] as? String == "5286da2e-5ebd-424f-adbb-8f5a8dca6b1b",
      c["newImportsAuthorized"] as? Int == 0, c["originalRunId"] as? String == "45643148-44a6-4aa3-add0-28487b55080d" else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    // CoreDevice may expose a destination while a copy is still in progress.
    // Wait for two identical, complete local JSON reads; validate the full case
    // and process-bound permit below. This never retries a remote operation.
    func deliveredObject(_ url: URL, label: String, seconds: Double) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(seconds)
      var prior: Data?
      while Date() < deadline {
        if let data = try? Data(contentsOf: url), !data.isEmpty,
          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
          if prior == data { report[label + "DeliveredBytes"] = data.count; return value }
          prior = data
        } else { prior = nil }
        try await Task.sleep(for: .milliseconds(150))
      }
      throw NSError(domain: "R4147IncompleteLocalDelivery", code: 1, userInfo: [NSLocalizedDescriptionKey: label])
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try await deliveredObject(activated, label: "activationPermit", seconds: 120)
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation] {
        guard let after = saved.replyRecovery?[ownOperation] else { throw NSError(domain: "R4140OriginalIntentMissing", code: 1) }
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      else if saved.replyRecovery?[ownOperation] != nil { throw NSError(domain: "R4140UnexpectedOriginalIntent", code: 1) }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    var caught: Error?
    var casePreview: QLPreviewController?
    do {
      let channel = try await EasyWorkSessions.shared.session(client), context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary), (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true else { throw NSError(domain: "F31RenderLAN", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread });store.hostedEasyWorkProductUIPresented = true;store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false);await store.loadHistory(threadId: ownThread)
      let filename = try XCTUnwrap(c["name"] as? String), expected = try XCTUnwrap(c["expectedV1SHA"] as? String), expectedSize = try XCTUnwrap(c["expectedV1Size"] as? Int)
      let v2SHA = try XCTUnwrap(c["expectedV2SHA"] as? String), v2Size = try XCTUnwrap(c["expectedV2Size"] as? Int)
      guard expected.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
        v2SHA.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil, expected != v2SHA,
        v2Size > 0, let marker = c["replyEndMarker"] as? String, !marker.isEmpty else { throw NSError(domain: "R4140VersionConfig", code: 1) }
      guard filename == "ew-generated-b9356d6c.txt", expectedSize == 102 else { throw NSError(domain: "GeneratedResourceConfig", code: 1) }
      var resource = ""
      func matchesOriginal(_ value: RemoteAttachment) -> Bool {
        guard value.threadId == ownThread, value.name == filename else { return false }
        return value.sha256 == expected && value.sizeBytes == expectedSize
      }
      func originalAttachments() -> [RemoteAttachment] { store.messages.flatMap { (message: ChatMessage) -> [RemoteAttachment] in message.attachments ?? [] } }
      try await wait("original attachment metadata", seconds: 90) { !store.historyLoading && originalAttachments().contains(where: matchesOriginal) }
      let file: RemoteAttachment = try XCTUnwrap(originalAttachments().first(where: matchesOriginal))
      resource = file.resourceIdentity
      report["originalRunId"] = c["originalRunId"]
      guard store.messages.contains(where: { $0.role == "assistant" && $0.runId == c["originalRunId"] as? String && $0.text.contains(c["replyEndMarker"] as? String ?? "") }) else { throw NSError(domain: "GeneratedResourceOriginalRun", code: 1) }
      let provider = try AgentClients.resolve(client)
      let previousCache = try await provider.cachedDownload(client, file: file)
      guard let v1Cache = previousCache else { throw NSError(domain: "R4140MissingV1Cache", code: 1) }
      let cachedV1 = try Data(contentsOf: v1Cache)
      guard cachedV1.count == expectedSize, sha(cachedV1) == expected else { throw NSError(domain: "R4140InvalidV1Cache", code: 1) }
      report["cacheAlreadyPresentBeforeCase"] = true
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      func controllers() -> [UIViewController] {
        var seen = Set<ObjectIdentifier>()
        func visit(_ v: UIViewController) -> [UIViewController] { guard seen.insert(ObjectIdentifier(v)).inserted else { return [] }; return [v] + v.children.flatMap(visit) + (v.presentedViewController.map(visit) ?? []) }
        return (window.windowScene?.windows ?? [window]).compactMap(\.rootViewController).flatMap(visit)
      }
      func settled() -> Bool { controllers().allSatisfy { !$0.isBeingPresented && !$0.isBeingDismissed && $0.transitionCoordinator == nil } }
      func button(_ id: String) -> UIButton? {
        let matches = views(window).compactMap { $0 as? UIButton }.filter { b in
          guard settled(), b.accessibilityIdentifier == id, b.window === window, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
          let rect = b.convert(b.bounds, to: window); guard window.bounds.contains(CGPoint(x: rect.midX, y: rect.midY)), let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) else { return false }
          return hit === b || hit.isDescendant(of: b)
        }; return matches.count == 1 ? matches[0] : nil
      }
      func activate(_ id: String) async throws { try await wait("actual production control " + id) { button(id) != nil }; try XCTUnwrap(button(id)).sendActions(for: .touchUpInside); report["lastProductionControl"] = id }
      // Search only the real mounted message list; no model input or replay.
      if button("download-" + filename) == nil && button("preview-" + filename) == nil {
        let pull = try XCTUnwrap(views(window).first { String(describing: type(of: $0)) == "EarlierHistoryPullView" })
        var ancestor = pull.superview, chatList: UIScrollView?
        while let view = ancestor { if let list = view as? UIScrollView { chatList = list; break }; ancestor = view.superview }
        let list = try XCTUnwrap(chatList)
        list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
        for _ in 0..<80 {
          window.layoutIfNeeded(); await Task.yield(); try await Task.sleep(for: .milliseconds(100))
          if button("download-" + filename) != nil || button("preview-" + filename) != nil { break }
          if button("history-local-more") != nil { try await activate("history-local-more"); continue }
          let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
          let next = min(bottom, list.contentOffset.y + list.bounds.height * 0.65); if next <= list.contentOffset.y + 1 { break }; list.setContentOffset(CGPoint(x: list.contentOffset.x, y: next), animated: false)
        }
      }
      try await activate("preview-" + filename)
      try await wait("visible real QuickLook", seconds: 90) { settled() && controllers().compactMap { $0 as? QLPreviewController }.filter { $0.viewIfLoaded?.window != nil }.count == 1 }
      let preview = try XCTUnwrap(controllers().compactMap { $0 as? QLPreviewController }.first { $0.viewIfLoaded?.window != nil }), source = try XCTUnwrap(preview.dataSource)
      casePreview = preview
      let url = try XCTUnwrap(source.previewController(preview, previewItemAt: preview.currentPreviewItemIndex).previewItemURL)
      let bytes = try Data(contentsOf: url); guard bytes.count == expectedSize, sha(bytes) == expected else { throw NSError(domain: "F32PreviewBytes", code: 1) }
      let cacheValue: URL? = try await provider.cachedDownload(client, file: file)
      let cache: URL = try XCTUnwrap(cacheValue)
      let cacheBytes: Data = try Data(contentsOf: cache)
      let expectedSnapshot = try VerifiedResourceExport(file: file, url: cache).validatedSnapshot()
      guard cacheBytes.count == bytes.count, sha(cacheBytes) == expected, expectedSnapshot.standardizedFileURL == url.standardizedFileURL else { throw NSError(domain: "F32CacheOwnership", code: 1) }
      report["resourceId"] = resource; report["v1SHA256"] = expected; report["v1Size"] = bytes.count
      report["v1SnapshotURL"] = url.absoluteString
      report["v1ControllerIdentity"] = String(describing: ObjectIdentifier(preview))
      report["actualV1CacheAndImmutableSnapshotMatch"] = true
      try save("awaiting-parent-owned-source-v2")
      let sourcePermit = folder.appendingPathComponent(run + "-source-v2-permit.json")
      try await wait("parent source V2 permit", seconds: 300) { FileManager.default.fileExists(atPath: sourcePermit.path) }
      let permit = try await deliveredObject(sourcePermit, label: "sourceV2Permit", seconds: 300)
      try save("complete-source-v2-permit-received")
      guard
        permit["caseId"] as? String == run, permit["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
        permit["threadId"] as? String == ownThread, permit["runId"] as? String == c["originalRunId"] as? String,
        permit["resourceId"] as? String == resource, permit["name"] as? String == filename,
        permit["expectedV1SHA"] as? String == expected, permit["expectedV1Size"] as? Int == expectedSize,
        permit["expectedV2SHA"] as? String == v2SHA, permit["expectedV2Size"] as? Int == v2Size,
        permit["parentOwnedSourceV2Ready"] as? Bool == true else { throw NSError(domain: "R4140SourcePermit", code: 1) }
      guard preview.viewIfLoaded?.window != nil, controllers().contains(where: { $0 === preview }), settled() else { throw NSError(domain: "R4140PreviewLostBeforeRefresh", code: 1) }
      await store.loadHistory(threadId: ownThread)
      guard store.selectedThreadId == ownThread, !store.historyLoading else { throw NSError(domain: "R4140RefreshTarget", code: 1) }
      let current = originalAttachments().filter { $0.threadId == ownThread && $0.resourceIdentity == resource && $0.name == filename && $0.sha256 == v2SHA && $0.sizeBytes == v2Size }
      guard current.count == 1, let v2 = current.first else { throw NSError(domain: "R4140CurrentVersion", code: 1) }
      guard preview.viewIfLoaded?.window != nil, controllers().contains(where: { $0 === preview }), settled(),
        let stillSource = preview.dataSource,
        let stillURL = stillSource.previewController(preview, previewItemAt: preview.currentPreviewItemIndex).previewItemURL,
        stillURL.standardizedFileURL == url.standardizedFileURL,
        try Data(contentsOf: stillURL) == bytes, bytes.count == expectedSize, sha(bytes) == expected else { throw NSError(domain: "R4140LivePreviewChanged", code: 1) }
      report["backgroundHistoryRefreshObservedV2"] = true; report["sameControllerAndV1SnapshotPreserved"] = true
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      guard let png = image.pngData() else { throw NSError(domain: "R4140WindowPNG", code: 1) }
      let imageName = run + "-v1-preview-after-v2-refresh.png"
      try png.write(to: folder.appendingPathComponent(imageName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["liveV1WindowPNG"] = imageName; report["liveV1WindowPNGSHA256"] = sha(png)
      try save("v2-history-refreshed-v1-preview-preserved")
      let livePixelsPermit = folder.appendingPathComponent(run + "-live-v1-system-pixels.json")
      try await wait("official V1 preview pixels after actual V2 history refresh", seconds: 90) { FileManager.default.fileExists(atPath: livePixelsPermit.path) }
      let livePixels = try await deliveredObject(livePixelsPermit, label: "liveV1PixelsPermit", seconds: 90)
      guard
        livePixels["caseId"] as? String == run, livePixels["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
        livePixels["officialCoreDeviceScreenshot"] as? Bool == true,
        let livePixelSHA = livePixels["sha256"] as? String, livePixelSHA.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
        preview.viewIfLoaded?.window != nil, controllers().contains(where: { $0 === preview }),
        try Data(contentsOf: url) == bytes else { throw NSError(domain: "R4142LivePreviewPixels", code: 1) }
      report["officialLiveV1SystemScreenshotSHA256"] = livePixelSHA
      func close(_ owned: QLPreviewController, key: String) async throws {
        let items = ([owned] + owned.children).flatMap { ($0.navigationItem.leftBarButtonItems ?? []) + ($0.navigationItem.rightBarButtonItems ?? []) }
        let done = items.filter { $0.isEnabled && ["完成", "Done"].contains($0.title ?? $0.accessibilityLabel ?? "") && $0.action != nil }
        if done.count == 1, let action = done[0].action {
          guard UIApplication.shared.sendAction(action, to: done[0].target, from: done[0], for: nil) else { throw NSError(domain: "R4140CloseAction", code: 1) }
          report[key] = "system Done bar item action"
        } else { owned.dismiss(animated: true); report[key] = "controller dismiss fallback; no system Done click claimed" }
        try await wait("owned QuickLook closed") { owned.viewIfLoaded?.window == nil && settled() }
      }
      try await close(preview, key: "v1CloseRoute"); casePreview = nil
      guard try await provider.cachedDownload(client, file: v2) == nil else { throw NSError(domain: "R4140V2UnexpectedCache", code: 1) }
      report["v2CacheInitiallyAbsent"] = true
      try await activate("download-" + filename)
      try await wait("production V2 preview control") { button("preview-" + filename) != nil }
      try await activate("preview-" + filename)
      try await wait("actual V2 QuickLook", seconds: 90) { settled() && controllers().compactMap { $0 as? QLPreviewController }.filter { $0.viewIfLoaded?.window != nil }.count == 1 }
      guard let v2Preview = controllers().compactMap({ $0 as? QLPreviewController }).first(where: { $0.viewIfLoaded?.window != nil }),
        let v2Source = v2Preview.dataSource,
        let v2URL = v2Source.previewController(v2Preview, previewItemAt: v2Preview.currentPreviewItemIndex).previewItemURL else { throw NSError(domain: "R4140V2Preview", code: 1) }
      casePreview = v2Preview
      let v2Bytes = try Data(contentsOf: v2URL)
      guard let v2Cache = try await provider.cachedDownload(client, file: v2), v2Bytes.count == v2Size, sha(v2Bytes) == v2SHA,
        try Data(contentsOf: v2Cache) == v2Bytes,
        try VerifiedResourceExport(file: v2, url: v2Cache).validatedSnapshot().standardizedFileURL == v2URL.standardizedFileURL else { throw NSError(domain: "R4140V2Bytes", code: 1) }
      let v2Name = run + "-actual-v2-download.bin"
      try v2Bytes.write(to: folder.appendingPathComponent(v2Name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["v2DownloadFile"] = v2Name; report["v2SHA256"] = v2SHA; report["v2Size"] = v2Size; report["v2SnapshotURL"] = v2URL.absoluteString
      report["v2ControllerIdentity"] = String(describing: ObjectIdentifier(v2Preview)); report["actualV2CacheAndImmutableSnapshotMatch"] = true
      try save("generated-v2-quicklook-await-system-pixels")
      let pixelPermit = folder.appendingPathComponent(run + "-generated-v2-system-pixels.json")
      try await wait("official V2 system screenshot receipt", seconds: 90) { FileManager.default.fileExists(atPath: pixelPermit.path) }
      let pixels = try await deliveredObject(pixelPermit, label: "v2PixelsPermit", seconds: 90)
      guard
        pixels["caseId"] as? String == run, pixels["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
        pixels["officialCoreDeviceScreenshot"] as? Bool == true, let pixelSHA = pixels["sha256"] as? String,
        pixelSHA.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
        v2Preview.viewIfLoaded?.window != nil, controllers().contains(where: { $0 === v2Preview }) else { throw NSError(domain: "R4140SystemPixels", code: 1) }
      report["officialV2SystemScreenshotSHA256"] = pixelSHA
      try await close(v2Preview, key: "v2CloseRoute"); casePreview = nil

    } catch { caught = error;report["failureDescription"] = error.localizedDescription; report["failureDomain"] = (error as NSError).domain; report["failureCode"] = (error as NSError).code }
    if let preview = casePreview, preview.viewIfLoaded?.window != nil { preview.dismiss(animated: false); report["failureCleanupRoute"] = "controller dismiss; no user click claimed" }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values == oldDrafts, store.selectedThreadId == originalThread, store.selectedProjectId == oldProject else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "completed-version-preview-await-independent-pixel-review" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

}

// Prepared excerpt only; integrate only after review. Existing file imports apply.
extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testOriginalImagePreviewCacheAndResourceScope() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      c["newImportsAuthorized"] as? Int == 0 else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    // CoreDevice may expose a destination while a copy is still in progress.
    // Wait for two identical, complete local JSON reads; validate the full case
    // and process-bound permit below. This never retries a remote operation.
    func deliveredObject(_ url: URL, label: String, seconds: Double) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(seconds)
      var prior: Data?
      while Date() < deadline {
        if let data = try? Data(contentsOf: url), !data.isEmpty,
          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
          if prior == data { report[label + "DeliveredBytes"] = data.count; return value }
          prior = data
        } else { prior = nil }
        try await Task.sleep(for: .milliseconds(150))
      }
      throw NSError(domain: "R4147IncompleteLocalDelivery", code: 1, userInfo: [NSLocalizedDescriptionKey: label])
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try await deliveredObject(activated, label: "activationPermit", seconds: 120)
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true, activation["bundleId"] as? String == Bundle.main.bundleIdentifier, activation["deviceUDID"] as? String == c["deviceUDID"] as? String else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation] {
        guard let after = saved.replyRecovery?[ownOperation] else { throw NSError(domain: "R4140OriginalIntentMissing", code: 1) }
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      else if saved.replyRecovery?[ownOperation] != nil { throw NSError(domain: "R4140UnexpectedOriginalIntent", code: 1) }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    var caught: Error?
    var caseCoverOpen = false
    var alteredCache: URL?; var originalBytes: Data?; var corruptBytes: Data?
    do {
      let channel = try await EasyWorkSessions.shared.session(client)
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary),
        (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true,
        let endpoint = c["originalLiveLANEndpoint"] as? String, let pin = c["expectedExistingLANCertificateSHA256"] as? String,
        URL(string: endpoint)?.scheme == "https", URL(string: endpoint)?.port == c["expectedOriginalLANPort"] as? Int,
        connection.candidates.contains(where: { $0.transportId == "lan-wss" && $0.endpoint == endpoint && $0.peerFingerprint == pin }) else { throw NSError(domain: "R4155Target", code: 1) }
      let binding = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(credential).utf8)).binding
      guard try easyWorkJSON(binding.target.dictionary) == easyWorkJSON(channelTarget.dictionary) else { throw NSError(domain: "R4155Binding", code: 1) }
      let descriptor = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: binding.target, publicKey: binding.desktopPublicKey, timeout: 10)
      guard descriptor.target == binding.target, descriptor.publicKey == binding.desktopPublicKey, descriptor.fingerprint == pin else { throw NSError(domain: "R4155Target", code: 2) }
      let filename = "f31-26b995b4.png", expected = "e5ab5b57d54731e65104bbb52c4dc3974d8d52b5f0b1fd9dc3feb43ca0594cf0", resource = "res_b1612bbd25d1192c"
      guard let rgba = c["expectedPixelRGBA"] as? [Int], rgba == [49, 115, 169, 255],
        let unreferenced = c["unreferencedPath"] as? String, !unreferenced.isEmpty,
        let other = c["otherThreadId"] as? String, UUID(uuidString: other) != nil, other != ownThread,
        let resolveCode = c["expectedResolveRejectionCode"] as? String, resolveCode == "runtime_error",
        let readCode = c["expectedReadRejectionCode"] as? String, readCode == "resource_not_found" else { throw NSError(domain: "R4155Config", code: 1) }
      func rejection(_ method: String, _ params: [String: Any], expected: String) async throws {
        do { _ = try await channel.request(method, params); throw NSError(domain: "R4155UnexpectedSuccess", code: 1) }
        catch let e as EasyWorkRemoteRejection { guard e.code == expected else { throw e } }
      }
      try await rejection("resource/resolve", ["threadId": ownThread, "reference": unreferenced], expected: resolveCode)
      try await rejection("resource/read", ["threadId": other, "resourceId": resource], expected: readCode)
      report["resolveRejectionCode"] = resolveCode; report["wrongThreadReadRejectionCode"] = readCode
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread }); store.hostedEasyWorkProductUIPresented = true; store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false); await store.loadHistory(threadId: ownThread)
      func attachments() -> [RemoteAttachment] { store.messages.flatMap { $0.attachments ?? [] } }
      func matches(_ f: RemoteAttachment) -> Bool { f.threadId == ownThread && f.id == resource && f.name == filename && f.sizeBytes == 247 && f.sha256 == expected }
      try await wait("original image metadata", seconds: 90) { !store.historyLoading && attachments().contains(where: matches) }
      let matching = attachments().filter(matches); guard matching.count == 1 else { throw NSError(domain: "R4155Attachment", code: 1) }
      let file = try XCTUnwrap(matching.first), provider = try AgentClients.resolve(client)
      let previous = try await provider.cachedDownload(client, file: file); report["cacheAlreadyPresentBeforeCase"] = previous != nil
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      func controllers() -> [UIViewController] {
        var seen = Set<ObjectIdentifier>()
        func visit(_ v: UIViewController) -> [UIViewController] { guard seen.insert(ObjectIdentifier(v)).inserted else { return [] }; return [v] + v.children.flatMap(visit) + (v.presentedViewController.map(visit) ?? []) }
        return (window.windowScene?.windows ?? [window]).compactMap(\.rootViewController).flatMap(visit)
      }
      func settled() -> Bool { controllers().allSatisfy { !$0.isBeingPresented && !$0.isBeingDismissed && $0.transitionCoordinator == nil } }
      func button(_ id: String) -> UIButton? {
        let matches = views(window).compactMap { $0 as? UIButton }.filter { b in
          guard settled(), b.accessibilityIdentifier == id, b.window === window, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
          let rect = b.convert(b.bounds, to: window); guard window.bounds.contains(CGPoint(x: rect.midX, y: rect.midY)), let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) else { return false }
          return hit === b || hit.isDescendant(of: b)
        }; return matches.count == 1 ? matches[0] : nil
      }
      func activate(_ id: String) async throws { try await wait("actual production control " + id) { button(id) != nil }; try XCTUnwrap(button(id)).sendActions(for: .touchUpInside); report["lastProductionControl"] = id }
      // Search only the real mounted message list; no model input or replay.
      if button("download-" + filename) == nil && button("preview-" + filename) == nil {
        let pull = try XCTUnwrap(views(window).first { String(describing: type(of: $0)) == "EarlierHistoryPullView" })
        var ancestor = pull.superview, chatList: UIScrollView?
        while let view = ancestor { if let list = view as? UIScrollView { chatList = list; break }; ancestor = view.superview }
        let list = try XCTUnwrap(chatList)
        list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
        for _ in 0..<80 {
          window.layoutIfNeeded(); await Task.yield(); try await Task.sleep(for: .milliseconds(100))
          if button("download-" + filename) != nil || button("preview-" + filename) != nil { break }
          if button("history-local-more") != nil { try await activate("history-local-more"); continue }
          let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
          let next = min(bottom, list.contentOffset.y + list.bounds.height * 0.65); if next <= list.contentOffset.y + 1 { break }; list.setContentOffset(CGPoint(x: list.contentOffset.x, y: next), animated: false)
        }
      }
      // The mounted image card's task downloads automatically when cache is absent or invalid.
      var cacheCandidate: URL?
      let cacheDeadline = Date().addingTimeInterval(90)
      while Date() < cacheDeadline {
        cacheCandidate = try await provider.cachedDownload(client, file: file)
        if cacheCandidate != nil && button("preview-" + filename) != nil { break }
        try await Task.sleep(for: .milliseconds(150))
      }
      guard button("preview-" + filename) != nil, let cache = cacheCandidate else { throw NSError(domain: "R4158CacheWait", code: 1) }
      let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath().appendingPathComponent("Downloads", isDirectory: true).standardizedFileURL
      let safe = cache.standardizedFileURL
      let scope = HostService.hash(try easyWorkJSON([target.agentId, target.account?.authority ?? "", target.account?.subject ?? "", target.hostId, target.workspaceId, target.runtimeId, file.threadId]))
      let exact = root.appendingPathComponent("agent-" + scope, isDirectory: true).appendingPathComponent(resource, isDirectory: true).appendingPathComponent(filename).standardizedFileURL
      guard safe == exact, safe.deletingLastPathComponent().lastPathComponent == resource,
        safe.lastPathComponent == filename, safe.path == safe.resolvingSymlinksInPath().standardizedFileURL.path else { throw NSError(domain: "R4155UnsafeCache", code: 1) }
      let bytes = try Data(contentsOf: safe)
      guard bytes.count == 247, sha(bytes) == expected, let cg = UIImage(data: bytes)?.cgImage, cg.width == 1, cg.height == 1 else { throw NSError(domain: "R4155Image", code: 1) }
      var pixel = [UInt8](repeating: 0, count: 4)
      let rendered = pixel.withUnsafeMutableBytes { raw -> Bool in
        guard let ctx = CGContext(data: raw.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: 1, height: 1)); return true
      }
      guard rendered, pixel.map(Int.init) == rgba else { throw NSError(domain: "R4155Pixel", code: 1) }
      report["decodedPremultipliedRGBA"] = pixel.map(Int.init)
      // Traverse the public accessibility tree including SwiftUI accessibility elements.
      func objects() -> [NSObject] {
        var result: [NSObject] = [], seen = Set<ObjectIdentifier>()
        func visit(_ object: NSObject) {
          guard seen.insert(ObjectIdentifier(object)).inserted else { return }
          result.append(object)
          (object as? UIView)?.subviews.forEach(visit)
          object.accessibilityElements?.compactMap { $0 as? NSObject }.forEach(visit)
          let count = object.accessibilityElementCount()
          if count > 0 && count < 10000 { for i in 0..<count { if let child = object.accessibilityElement(at: i) as? NSObject { visit(child) } } }
        }
        (window.windowScene?.windows ?? [window]).forEach(visit)
        return result
      }
      func axID(_ o: NSObject) -> String? {
        if let view = o as? UIView, let value = view.accessibilityIdentifier { return value }
        let getter = NSSelectorFromString("accessibilityIdentifier")
        return o.responds(to: getter) ? o.value(forKey: "accessibilityIdentifier") as? String : nil
      }
      func ax(_ id: String) -> [NSObject] { objects().filter { axID($0) == id } }
      func visible(_ o: NSObject) -> Bool {
        let frame = o.accessibilityFrame
        let screenBounds = window.convert(window.bounds, to: nil)
        return !frame.isNull && !frame.isEmpty && frame.intersects(screenBounds) && !o.accessibilityElementsHidden
      }
      func closeCover() async throws {
        let candidates = ax("resource-image-preview-close").filter { visible($0) && !$0.accessibilityTraits.contains(.notEnabled) }
        guard candidates.count == 1, candidates[0].accessibilityActivate() else { throw NSError(domain: "R4158Close", code: 1) }
        report["closeRoute"] = "resource-image-preview-close accessibilityActivate"
        try await wait("image cover dismissed and settled") { ax("resource-image-preview").isEmpty && ax("resource-image-preview-image").isEmpty && settled() }
        caseCoverOpen = false
      }
      func openPreview(_ stage: String, _ suffix: String, _ key: String) async throws {
        try await activate("preview-" + filename)
        caseCoverOpen = true
        do {
          try await wait("visible image cover", seconds: 30) {
            settled() && ax("resource-image-preview").count == 1 && ax("resource-image-preview-image").filter { $0.accessibilityLabel == filename && visible($0) }.count == 1
          }
        } catch {
          report[key + "ImageControlDiagnostics"] = objects().filter { axID($0)?.hasPrefix("resource-image-preview") == true }.map {
            ["class": String(describing: type(of: $0)), "id": axID($0) ?? "", "labelMatchesFilename": $0.accessibilityLabel == filename, "frame": NSCoder.string(for: $0.accessibilityFrame), "visible": visible($0)] as [String: Any]
          }
          report[key + "ControllerTransitions"] = controllers().map { ["class": String(describing: type(of: $0)), "presenting": $0.isBeingPresented, "dismissing": $0.isBeingDismissed, "transition": $0.transitionCoordinator != nil] as [String: Any] }
          throw error
        }
        let image = try XCTUnwrap(ax("resource-image-preview-image").first { $0.accessibilityLabel == filename && visible($0) })
        report[key + "ImageAXLabel"] = image.accessibilityLabel
        report[key + "ImageAXFrame"] = NSCoder.string(for: image.accessibilityFrame)
        try save(stage)
        let receipt = try await deliveredObject(folder.appendingPathComponent(run + suffix), label: key + "Pixels", seconds: 90)
        guard receipt["caseId"] as? String == run, receipt["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
          receipt["officialCoreDeviceScreenshot"] as? Bool == true, let hash = receipt["sha256"] as? String,
          hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          ax("resource-image-preview-image").filter({ $0.accessibilityLabel == filename && visible($0) }).count == 1 else { throw NSError(domain: "R4158Pixels", code: 1) }
        report[key + "SystemScreenshotSHA256"] = hash
        try await closeCover()
      }
      // O_NOFOLLOW and fstat bind validation and mutation to one regular file descriptor.
      func mutateExact(_ path: URL, from: Data, to: Data) throws {
        let fd = Darwin.open(path.path, O_RDWR | O_NOFOLLOW)
        guard fd >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, (info.st_mode & mode_t(S_IFMT)) == mode_t(S_IFREG), info.st_size == off_t(from.count), from.count == to.count else { throw NSError(domain: "R4158CacheFD", code: 1) }
        var current = Data(count: from.count)
        let readCount = current.withUnsafeMutableBytes { Darwin.pread(fd, $0.baseAddress, from.count, 0) }
        guard readCount == from.count, current == from, sha(current) == sha(from) else { throw NSError(domain: "R4158CacheFD", code: 2) }
        let differences = zip(from, to).enumerated().filter { $0.element.0 != $0.element.1 }
        guard differences.count == 1, let difference = differences.first else { throw NSError(domain: "R4158CacheFD", code: 3) }
        var byte = difference.element.1
        guard Darwin.pwrite(fd, &byte, 1, off_t(difference.offset)) == 1, fsync(fd) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
      }
      try await openPreview("original-image-preview-await-system-pixels", "-image-system-pixels.json", "original")
      guard try Data(contentsOf: safe) == bytes, safe.path == safe.resolvingSymlinksInPath().standardizedFileURL.path else { throw NSError(domain: "R4158CacheChanged", code: 1) }
      var corrupted = bytes; corrupted[corrupted.startIndex] ^= 1
      originalBytes = bytes; corruptBytes = corrupted; alteredCache = safe
      try mutateExact(safe, from: bytes, to: corrupted)
      guard try await provider.cachedDownload(client, file: file) == nil else { throw NSError(domain: "R4158CorruptAccepted", code: 1) }
      // The actual preview action revalidates the cache and requests a download when needed.
      try await activate("preview-" + filename)
      caseCoverOpen = true
      var recovered: URL?
      let recoveryDeadline = Date().addingTimeInterval(90)
      while Date() < recoveryDeadline {
        recovered = try await provider.cachedDownload(client, file: file)
        if let url = recovered, url.standardizedFileURL == safe, (try? Data(contentsOf: url)) == bytes { break }
        try await Task.sleep(for: .milliseconds(150))
      }
      guard let recovered, recovered.standardizedFileURL == safe, try Data(contentsOf: recovered) == bytes else { throw NSError(domain: "R4158Recovery", code: 1) }
      // The preview:true action may already present the cover after download. Close it before the independently recorded production action.
      try await wait("recovery preview cover", seconds: 90) { settled() && ax("resource-image-preview").count == 1 && ax("resource-image-preview-image").filter { $0.accessibilityLabel == filename && visible($0) }.count == 1 }
      try await closeCover()
      alteredCache = nil
      try await openPreview("recovered-image-preview-await-system-pixels", "-recovered-image-system-pixels.json", "recovered")
    } catch { caught = error; report["failureDescription"] = error.localizedDescription; let ns = error as NSError; report["failureDomain"] = ns.domain; report["failureCode"] = ns.code }
    if let path = alteredCache, let originalBytes, let corruptBytes {
      do {
        guard path.standardizedFileURL.path == path.resolvingSymlinksInPath().standardizedFileURL.path else { throw NSError(domain: "R4158CleanupOwnership", code: 1) }
        let fd = Darwin.open(path.path, O_RDWR | O_NOFOLLOW)
        guard fd >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, (info.st_mode & mode_t(S_IFMT)) == mode_t(S_IFREG), info.st_size == off_t(originalBytes.count) else { throw NSError(domain: "R4158CleanupOwnership", code: 2) }
        var current = Data(count: originalBytes.count)
        guard current.withUnsafeMutableBytes({ Darwin.pread(fd, $0.baseAddress, originalBytes.count, 0) }) == originalBytes.count, current == corruptBytes || current == originalBytes else { throw NSError(domain: "R4158CleanupOwnership", code: 3) }
        if current == corruptBytes {
          guard let index = zip(corruptBytes, originalBytes).enumerated().first(where: { $0.element.0 != $0.element.1 }) else { throw NSError(domain: "R4158CleanupOwnership", code: 4) }
          var byte = index.element.1
          guard Darwin.pwrite(fd, &byte, 1, off_t(index.offset)) == 1, fsync(fd) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        }
        report["caseCorruptCacheRestored"] = true
      } catch { report["cacheCleanupError"] = error.localizedDescription; if caught == nil { caught = error } }
    }
    // Cleanup only the cover opened by this case, using its public accessibility action.
    if caseCoverOpen {
      func findClose(_ view: UIView) -> [NSObject] {
        var found: [NSObject] = [], seen = Set<ObjectIdentifier>()
        func visit(_ o: NSObject) {
          guard seen.insert(ObjectIdentifier(o)).inserted else { return }
          let getter = NSSelectorFromString("accessibilityIdentifier")
          let identifier = (o as? UIView)?.accessibilityIdentifier ?? (o.responds(to: getter) ? o.value(forKey: "accessibilityIdentifier") as? String : nil)
          if identifier == "resource-image-preview-close" { found.append(o) }
          (o as? UIView)?.subviews.forEach(visit); o.accessibilityElements?.compactMap { $0 as? NSObject }.forEach(visit)
          let n = o.accessibilityElementCount(); if n > 0 && n < 10000 { for i in 0..<n { if let child = o.accessibilityElement(at: i) as? NSObject { visit(child) } } }
        }
        visit(view); return found
      }
      let closes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).flatMap(findClose)
      if closes.count == 1 && closes[0].accessibilityActivate() { report["failureCleanupRoute"] = "resource-image-preview-close accessibilityActivate" }
      else { let error = NSError(domain: "R4158CleanupCover", code: 1); report["coverCleanupError"] = error.localizedDescription; if caught == nil { caught = error } }
    }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values == oldDrafts, store.selectedThreadId == originalThread, store.selectedProjectId == oldProject else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "completed-image-preview-await-independent-pixel-review" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

}

extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testNonemptyClearKeepsHistoryResource() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Original physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any], c["caseId"] as? String == run,
      c["newModelsAuthorized"] as? Int == 0, c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C", c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      UIDevice.current.userInterfaceIdiom == .phone,
      c["originalThreadId"] as? String == "10bee47f-8aef-421f-8a94-2378d6bbd6e8",
      c["newImportsAuthorized"] as? Int == 0 else { throw XCTSkip("Explicit existing-result render configuration required") }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json");guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "F31ExistingCase", code: 2) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier, "bundleId": Bundle.main.bundleIdentifier!, "newModels": 0, "wholeFeaturePassed": false]
    func save(_ stage: String) throws { report["status"] = stage; report["stage"] = stage; try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "F31RenderWait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    // CoreDevice may expose a destination while a copy is still in progress.
    // Wait for two identical, complete local JSON reads; validate the full case
    // and process-bound permit below. This never retries a remote operation.
    func deliveredObject(_ url: URL, label: String, seconds: Double) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(seconds)
      var prior: Data?
      while Date() < deadline {
        if let data = try? Data(contentsOf: url), !data.isEmpty,
          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
          if prior == data { report[label + "DeliveredBytes"] = data.count; return value }
          prior = data
        } else { prior = nil }
        try await Task.sleep(for: .milliseconds(150))
      }
      throw NSError(domain: "R4147IncompleteLocalDelivery", code: 1, userInfo: [NSLocalizedDescriptionKey: label])
    }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    try save("awaiting-original-app")
    try await wait("original App store and foreground", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let activated = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official original same-PID activation", seconds: 120) { FileManager.default.fileExists(atPath: activated.path) }
    let activation = try await deliveredObject(activated, label: "activationPermit", seconds: 120)
    guard activation["caseId"] as? String == run, activation["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), activation["officialSameRunningProcessActivated"] as? Bool == true, activation["bundleId"] as? String == Bundle.main.bundleIdentifier, activation["deviceUDID"] as? String == c["deviceUDID"] as? String else { throw NSError(domain: "F31RenderActivation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard connection.target == target, target.agentId == "easywork", connection.preference == .lanOnly, !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "F31RenderTarget", code: 1) }
    let credential = try CredentialVault.existingToken(connection.credentialRef), identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder();encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, oldDrafts = store.drafts.values
    let originalThread = try XCTUnwrap(store.selectedThreadId), selected = try XCTUnwrap(store.threads.first { $0.id == originalThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    let ownOperation = try XCTUnwrap(c["originalOperationId"] as? String), ownThread = try XCTUnwrap(c["originalThreadId"] as? String)
    var ownedDraftKey: String?
    func protectedState() throws -> String {
      var saved = store.saved;saved.drafts = store.drafts.values
      if let before = old.replyRecovery?[ownOperation] {
        guard let after = saved.replyRecovery?[ownOperation] else { throw NSError(domain: "R4140OriginalIntentMissing", code: 1) }
        guard try encoder.encode(before.original) == encoder.encode(after.original), before.target == after.target, before.trust == after.trust,
          before.fingerprint == after.fingerprint, before.retryConsumed == after.retryConsumed,
          try encoder.encode(before.sharingScope) == encoder.encode(after.sharingScope) else { throw NSError(domain: "F31RenderOriginalIntent", code: 1) }
        saved.replyRecovery?[ownOperation] = before
      }
      else if saved.replyRecovery?[ownOperation] != nil { throw NSError(domain: "R4140UnexpectedOriginalIntent", code: 1) }
      if let key = ownedDraftKey {
        guard (saved.drafts[key] ?? Draft()) == Draft(), (oldDrafts[key] ?? Draft()) == Draft() else { throw NSError(domain: "R4177OwnDraftNotEmpty", code: 1) }
        saved.drafts[key] = oldDrafts[key]
      }
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState();report["originalProtectedStateSHA"] = baseline
    let ownedBody = "EWF_CLEAR_RESOURCE_" + run
    var ownedBodyInserted = false
    var caught: Error?
    var caseCoverOpen = false
    do {
      let channel = try await EasyWorkSessions.shared.session(client)
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary),
        (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true,
        let endpoint = c["originalLiveLANEndpoint"] as? String, let pin = c["expectedExistingLANCertificateSHA256"] as? String,
        URL(string: endpoint)?.scheme == "https", URL(string: endpoint)?.port == c["expectedOriginalLANPort"] as? Int,
        connection.candidates.contains(where: { $0.transportId == "lan-wss" && $0.endpoint == endpoint && $0.peerFingerprint == pin }) else { throw NSError(domain: "R4155Target", code: 1) }
      let binding = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(credential).utf8)).binding
      guard try easyWorkJSON(binding.target.dictionary) == easyWorkJSON(channelTarget.dictionary) else { throw NSError(domain: "R4155Binding", code: 1) }
      let descriptor = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: binding.target, publicKey: binding.desktopPublicKey, timeout: 10)
      guard descriptor.target == binding.target, descriptor.publicKey == binding.desktopPublicKey, descriptor.fingerprint == pin else { throw NSError(domain: "R4155Target", code: 2) }
      let filename = "f31-26b995b4.png", expected = "e5ab5b57d54731e65104bbb52c4dc3974d8d52b5f0b1fd9dc3feb43ca0594cf0", resource = "res_b1612bbd25d1192c"
      guard let rgba = c["expectedPixelRGBA"] as? [Int], rgba == [49,115,169,255], c["nonemptyClearAuthorized"] as? Bool == true else { throw NSError(domain: "R4177Config", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == ownThread }); store.hostedEasyWorkProductUIPresented = true; store.openThread(entry, loadAutomatically: false)
      await store.refresh(includeHistory: false); await store.loadHistory(threadId: ownThread)
      func attachments() -> [RemoteAttachment] { store.messages.flatMap { $0.attachments ?? [] } }
      func matches(_ f: RemoteAttachment) -> Bool { f.threadId == ownThread && f.id == resource && f.name == filename && f.sizeBytes == 247 && f.sha256 == expected }
      try await wait("original image metadata", seconds: 90) { !store.historyLoading && attachments().contains(where: matches) }
      let matching = attachments().filter(matches); guard matching.count == 1 else { throw NSError(domain: "R4155Attachment", code: 1) }
      let file = try XCTUnwrap(matching.first), provider = try AgentClients.resolve(client)
      let previous = try await provider.cachedDownload(client, file: file); report["cacheAlreadyPresentBeforeCase"] = previous != nil
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      func controllers() -> [UIViewController] {
        var seen = Set<ObjectIdentifier>()
        func visit(_ v: UIViewController) -> [UIViewController] { guard seen.insert(ObjectIdentifier(v)).inserted else { return [] }; return [v] + v.children.flatMap(visit) + (v.presentedViewController.map(visit) ?? []) }
        return (window.windowScene?.windows ?? [window]).compactMap(\.rootViewController).flatMap(visit)
      }
      func settled() -> Bool { controllers().allSatisfy { !$0.isBeingPresented && !$0.isBeingDismissed && $0.transitionCoordinator == nil } }
      func button(_ id: String) -> UIButton? {
        let matches = views(window).compactMap { $0 as? UIButton }.filter { b in
          guard settled(), b.accessibilityIdentifier == id, b.window === window, b.isEnabled, b.isUserInteractionEnabled, !b.isHidden, b.alpha > 0.01 else { return false }
          let rect = b.convert(b.bounds, to: window); guard window.bounds.contains(CGPoint(x: rect.midX, y: rect.midY)), let hit = window.hitTest(CGPoint(x: rect.midX, y: rect.midY), with: nil) else { return false }
          return hit === b || hit.isDescendant(of: b)
        }; return matches.count == 1 ? matches[0] : nil
      }
      func activate(_ id: String) async throws { try await wait("actual production control " + id) { button(id) != nil }; try XCTUnwrap(button(id)).sendActions(for: .touchUpInside); report["lastProductionControl"] = id }
      let key = store.targetKey; ownedDraftKey = key
      guard store.draft == Draft(), (oldDrafts[key] ?? Draft()) == Draft(), store.selectedThreadId == ownThread else { throw NSError(domain: "R4177OriginalDraft", code: 1) }
      let beforeIntent = try XCTUnwrap(store.captureEasyWorkDraftClearIntent())
      let importDestination = store.captureDraftImportDestination()
      var inputView: UIView?
      try await wait("actual editable composer") {
        let candidates = views(window).filter { v in
          guard v.window === window, !v.isHidden, v.alpha > 0.99, !v.bounds.isEmpty, window.bounds.intersects(v.convert(v.bounds, to: window)) else { return false }
          return (v as? UITextView)?.isEditable == true || (v as? UITextField)?.isEnabled == true
        }; inputView = candidates.count == 1 ? candidates[0] : nil; return inputView != nil
      }
      let input = try XCTUnwrap(inputView as? any UITextInput)
      guard try XCTUnwrap(inputView).becomeFirstResponder() else { throw NSError(domain: "R4177Input", code: 1) }
      try await wait("actual empty composer focus") { inputView?.isFirstResponder == true && input.text(in: input.textRange(from: input.beginningOfDocument, to: input.endOfDocument)!) == "" }
      ownedBodyInserted = true; input.insertText(ownedBody)
      try await wait("owned nonempty body") { store.targetKey == key && store.draft.text == ownedBody && store.draft.attachments.isEmpty }
      let filledIntent = try XCTUnwrap(store.captureEasyWorkDraftClearIntent()); guard filledIntent.textRevision > beforeIntent.textRevision else { throw NSError(domain: "R4177Revision", code: 1) }
      inputView?.resignFirstResponder(); try await Task.sleep(for: .milliseconds(350))
      func actions(_ menu: UIMenu) -> [UIAction] { menu.children.flatMap { element -> [UIAction] in if let action = element as? UIAction { return [action] }; if let child = element as? UIMenu { return actions(child) }; return [] } }
      var menuButton: UIButton?
      try await wait("exact current composer menu") {
        let markers = views(window).compactMap { $0 as? HostedNativeChromeGeometryView }.filter { $0.role == "composer-clear-intent:" + filledIntent.menuIdentity && $0.window === window && !$0.isHidden && !$0.bounds.isEmpty }
        guard markers.count == 1 else { return false }; let marker = markers[0], point = marker.convert(CGPoint(x: marker.bounds.midX, y: marker.bounds.midY), to: window)
        guard window.bounds.contains(point) else { return false }; var v = window.hitTest(point, with: nil)
        while let current = v { if let b = current as? UIButton, b.menu != nil, b.isEnabled, b.window === window { menuButton = b; return true }; v = current.superview }; return false
      }
      let actual = try XCTUnwrap(menuButton)
      if #available(iOS 17.4, *) { actual.performPrimaryAction() } else { throw XCTSkip("Public menu API required") }
      try await wait("actual composer menu held") { actual.isHeld }
      var clearAction: UIAction?
      try await wait("actual clear draft action") { clearAction = actual.menu.flatMap { actions($0).first { $0.title == L10n.tr("清空草稿") } }; return clearAction != nil }
      guard store.targetKey == key, store.draft.text == ownedBody, store.captureEasyWorkDraftClearIntent()?.menuIdentity == filledIntent.menuIdentity else { throw NSError(domain: "R4177StaleMenu", code: 1) }
      actual.sendAction(try XCTUnwrap(clearAction))
      try await wait("own nonempty draft cleared") { store.targetKey == key && store.draft == Draft() }
      if actual.isHeld { try XCTUnwrap(actual.interactions.compactMap { $0 as? UIContextMenuInteraction }.first).dismissMenu(); try await wait("clear menu animation") { !actual.isHeld } }
      let clearedIntent = try XCTUnwrap(store.captureEasyWorkDraftClearIntent())
      guard clearedIntent.textRevision > filledIntent.textRevision, !store.isDraftImportCurrent(importDestination), store.drafts.values.filter({ $0.key != key }) == oldDrafts.filter({ $0.key != key }) else { throw NSError(domain: "R4177ClearProtection", code: 1) }
      report["actualNonemptyClearMenuExecuted"] = true; report["ownTextRevisionAdvancedWithoutReset"] = true; report["oldImportDestinationInvalidated"] = true
      await store.loadHistory(threadId: ownThread)
      guard attachments().filter(matches).count == 1 else { throw NSError(domain: "R4177HistoryResourceMissing", code: 1) }
      let metadata = try easyWorkObject(await channel.request("resource/read", ["threadId": ownThread, "resourceId": resource]))
      let transfer = try XCTUnwrap(metadata["transfer"] as? [String: Any])
      guard transfer["threadId"] as? String == ownThread, transfer["resourceId"] as? String == resource, transfer["name"] as? String == filename, transfer["size"] as? Int == 247, transfer["sha256"] as? String == expected, transfer["status"] as? String == "completed", transfer["bindingId"] as? String == c["iosBindingId"] as? String else { report["actualUnexpectedReadTransfer"] = transfer; throw NSError(domain: "R4177ResourceRead", code: 1) }
      report["actualPostClearThreadBoundResourceRead"] = true; report["postClearResourceId"] = resource
      // Search only the real mounted message list; no model input or replay.
      if button("download-" + filename) == nil && button("preview-" + filename) == nil {
        let pull = try XCTUnwrap(views(window).first { String(describing: type(of: $0)) == "EarlierHistoryPullView" })
        var ancestor = pull.superview, chatList: UIScrollView?
        while let view = ancestor { if let list = view as? UIScrollView { chatList = list; break }; ancestor = view.superview }
        let list = try XCTUnwrap(chatList)
        list.setContentOffset(CGPoint(x: 0, y: -list.adjustedContentInset.top), animated: false)
        for _ in 0..<80 {
          window.layoutIfNeeded(); await Task.yield(); try await Task.sleep(for: .milliseconds(100))
          if button("download-" + filename) != nil || button("preview-" + filename) != nil { break }
          if button("history-local-more") != nil { try await activate("history-local-more"); continue }
          let bottom = max(-list.adjustedContentInset.top, list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom)
          let next = min(bottom, list.contentOffset.y + list.bounds.height * 0.65); if next <= list.contentOffset.y + 1 { break }; list.setContentOffset(CGPoint(x: list.contentOffset.x, y: next), animated: false)
        }
      }
      // The mounted image card's task downloads automatically when cache is absent or invalid.
      var cacheCandidate: URL?
      let cacheDeadline = Date().addingTimeInterval(90)
      while Date() < cacheDeadline {
        cacheCandidate = try await provider.cachedDownload(client, file: file)
        if cacheCandidate != nil && button("preview-" + filename) != nil { break }
        try await Task.sleep(for: .milliseconds(150))
      }
      guard button("preview-" + filename) != nil, let cache = cacheCandidate else { throw NSError(domain: "R4158CacheWait", code: 1) }
      let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath().appendingPathComponent("Downloads", isDirectory: true).standardizedFileURL
      let safe = cache.standardizedFileURL
      let scope = HostService.hash(try easyWorkJSON([target.agentId, target.account?.authority ?? "", target.account?.subject ?? "", target.hostId, target.workspaceId, target.runtimeId, file.threadId]))
      let exact = root.appendingPathComponent("agent-" + scope, isDirectory: true).appendingPathComponent(resource, isDirectory: true).appendingPathComponent(filename).standardizedFileURL
      guard safe == exact, safe.deletingLastPathComponent().lastPathComponent == resource,
        safe.lastPathComponent == filename, safe.path == safe.resolvingSymlinksInPath().standardizedFileURL.path else { throw NSError(domain: "R4155UnsafeCache", code: 1) }
      let bytes = try Data(contentsOf: safe)
      guard bytes.count == 247, sha(bytes) == expected, let cg = UIImage(data: bytes)?.cgImage, cg.width == 1, cg.height == 1 else { throw NSError(domain: "R4155Image", code: 1) }
      var pixel = [UInt8](repeating: 0, count: 4)
      let rendered = pixel.withUnsafeMutableBytes { raw -> Bool in
        guard let ctx = CGContext(data: raw.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: 1, height: 1)); return true
      }
      guard rendered, pixel.map(Int.init) == rgba else { throw NSError(domain: "R4155Pixel", code: 1) }
      report["decodedPremultipliedRGBA"] = pixel.map(Int.init)
      // Traverse the public accessibility tree including SwiftUI accessibility elements.
      func objects() -> [NSObject] {
        var result: [NSObject] = [], seen = Set<ObjectIdentifier>()
        func visit(_ object: NSObject) {
          guard seen.insert(ObjectIdentifier(object)).inserted else { return }
          result.append(object)
          (object as? UIView)?.subviews.forEach(visit)
          object.accessibilityElements?.compactMap { $0 as? NSObject }.forEach(visit)
          let count = object.accessibilityElementCount()
          if count > 0 && count < 10000 { for i in 0..<count { if let child = object.accessibilityElement(at: i) as? NSObject { visit(child) } } }
        }
        (window.windowScene?.windows ?? [window]).forEach(visit)
        return result
      }
      func axID(_ o: NSObject) -> String? {
        if let view = o as? UIView, let value = view.accessibilityIdentifier { return value }
        let getter = NSSelectorFromString("accessibilityIdentifier")
        return o.responds(to: getter) ? o.value(forKey: "accessibilityIdentifier") as? String : nil
      }
      func ax(_ id: String) -> [NSObject] { objects().filter { axID($0) == id } }
      func visible(_ o: NSObject) -> Bool {
        let frame = o.accessibilityFrame
        let screenBounds = window.convert(window.bounds, to: nil)
        return !frame.isNull && !frame.isEmpty && frame.intersects(screenBounds) && !o.accessibilityElementsHidden
      }
      func closeCover() async throws {
        let candidates = ax("resource-image-preview-close").filter { visible($0) && !$0.accessibilityTraits.contains(.notEnabled) }
        guard candidates.count == 1, candidates[0].accessibilityActivate() else { throw NSError(domain: "R4158Close", code: 1) }
        report["closeRoute"] = "resource-image-preview-close accessibilityActivate"
        try await wait("image cover dismissed and settled") { ax("resource-image-preview").isEmpty && ax("resource-image-preview-image").isEmpty && settled() }
        caseCoverOpen = false
      }
      func openPreview(_ stage: String, _ suffix: String, _ key: String) async throws {
        try await activate("preview-" + filename)
        caseCoverOpen = true
        do {
          try await wait("visible image cover", seconds: 30) {
            settled() && ax("resource-image-preview").count == 1 && ax("resource-image-preview-image").filter { $0.accessibilityLabel == filename && visible($0) }.count == 1
          }
        } catch {
          report[key + "ImageControlDiagnostics"] = objects().filter { axID($0)?.hasPrefix("resource-image-preview") == true }.map {
            ["class": String(describing: type(of: $0)), "id": axID($0) ?? "", "labelMatchesFilename": $0.accessibilityLabel == filename, "frame": NSCoder.string(for: $0.accessibilityFrame), "visible": visible($0)] as [String: Any]
          }
          report[key + "ControllerTransitions"] = controllers().map { ["class": String(describing: type(of: $0)), "presenting": $0.isBeingPresented, "dismissing": $0.isBeingDismissed, "transition": $0.transitionCoordinator != nil] as [String: Any] }
          throw error
        }
        let image = try XCTUnwrap(ax("resource-image-preview-image").first { $0.accessibilityLabel == filename && visible($0) })
        report[key + "ImageAXLabel"] = image.accessibilityLabel
        report[key + "ImageAXFrame"] = NSCoder.string(for: image.accessibilityFrame)
        try save(stage)
        let receipt = try await deliveredObject(folder.appendingPathComponent(run + suffix), label: key + "Pixels", seconds: 90)
        guard receipt["caseId"] as? String == run, receipt["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
          receipt["officialCoreDeviceScreenshot"] as? Bool == true, let hash = receipt["sha256"] as? String,
          hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          ax("resource-image-preview-image").filter({ $0.accessibilityLabel == filename && visible($0) }).count == 1 else { throw NSError(domain: "R4158Pixels", code: 1) }
        report[key + "SystemScreenshotSHA256"] = hash
        try await closeCover()
      }
      try await openPreview("nonempty-clear-history-image-await-system-pixels", "-clear-resource-system-pixels.json", "postClear")
    } catch { caught = error; report["failureDescription"] = error.localizedDescription; let ns = error as NSError; report["failureDomain"] = ns.domain; report["failureCode"] = ns.code }
    if let key = ownedDraftKey {
      do {
        guard store.targetKey == key, store.selectedThreadId == ownThread, store.draft.attachments.isEmpty else { throw NSError(domain: "R4177CleanupTarget", code: 1) }
        if !store.draft.text.isEmpty {
          guard ownedBodyInserted, store.draft.text == ownedBody else { throw NSError(domain: "R4177UnknownDraftNoOverwrite", code: 1) }
          store.setText("")
        }
        guard store.draft == Draft() else { throw NSError(domain: "R4177CleanupOwnDraft", code: 1) }
        report["onlyOwnedDraftTextEmptyFinally"] = true
      } catch { report["ownedCleanupError"] = error.localizedDescription; if caught == nil { caught = error } }
    }
    // Cleanup only the cover opened by this case, using its public accessibility action.
    if caseCoverOpen {
      func findClose(_ view: UIView) -> [NSObject] {
        var found: [NSObject] = [], seen = Set<ObjectIdentifier>()
        func visit(_ o: NSObject) {
          guard seen.insert(ObjectIdentifier(o)).inserted else { return }
          let getter = NSSelectorFromString("accessibilityIdentifier")
          let identifier = (o as? UIView)?.accessibilityIdentifier ?? (o.responds(to: getter) ? o.value(forKey: "accessibilityIdentifier") as? String : nil)
          if identifier == "resource-image-preview-close" { found.append(o) }
          (o as? UIView)?.subviews.forEach(visit); o.accessibilityElements?.compactMap { $0 as? NSObject }.forEach(visit)
          let n = o.accessibilityElementCount(); if n > 0 && n < 10000 { for i in 0..<n { if let child = o.accessibilityElement(at: i) as? NSObject { visit(child) } } }
        }
        visit(view); return found
      }
      let closes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).flatMap(findClose)
      if closes.count == 1 && closes[0].accessibilityActivate() { report["failureCleanupRoute"] = "resource-image-preview-close accessibilityActivate" }
      else { let error = NSError(domain: "R4158CleanupCover", code: 1); report["coverCleanupError"] = error.localizedDescription; if caught == nil { caught = error } }
    }
    store.openThread(selected, loadAutomatically: false);store.selectedProjectId = oldProject;store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id];store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id];store.saved.newProjects[client.id] = old.newProjects[client.id];store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, try encoder.encode(VoiceMigrationSafety.snapshot()) == voice, try CredentialVault.existingToken(connection.credentialRef) == credential,
        try CredentialVault.existingToken("easywork-device-identity") == identity, store.drafts.values.filter({ $0.key != ownedDraftKey }) == oldDrafts.filter({ $0.key != ownedDraftKey }), store.selectedThreadId == originalThread, store.selectedProjectId == oldProject else { throw NSError(domain: "F31RenderOriginalProtection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription;if caught == nil { caught = error } }
    try save(caught == nil ? "completed-nonempty-clear-resource-await-independent-pixel-review" : "failed-readonly-render-no-replay")
    if let caught { throw caught }
  }

}

extension EasyWorkAttachmentFailureNativeTests {
  @MainActor func testOneOtherDeviceVisibleTextStream() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Paired physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
      c["caseId"] as? String == run, c["newModelsAuthorized"] as? Int == 0,
      c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C",
      c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      c["bundleId"] as? String == "com.easyai.agenthub.easyworkacceptance",
      Bundle.main.bundleIdentifier == c["bundleId"] as? String,
      UIDevice.current.userInterfaceIdiom == .phone,
      let thread = c["fixtureThreadId"] as? String, UUID(uuidString: thread) != nil,
      let project = c["projectId"] as? String, !project.isEmpty,
      let start = c["replyStartMarker"] as? String, !start.isEmpty,
      let end = c["replyEndMarker"] as? String, !end.isEmpty, start != end,
      let bodySHA = c["bodySHA256"] as? String, bodySHA.count == 64, bodySHA.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
      c["maxLocalInputs"] as? Int == 0, c["maxOtherDeviceRuns"] as? Int == 1, c["newImportsAuthorized"] as? Int == 0 else {
      throw XCTSkip("Explicit R4203 read-only configuration required")
    }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "R4203ExistingCase", code: 1) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": Int(ProcessInfo.processInfo.processIdentifier), "bundleId": Bundle.main.bundleIdentifier!, "threadId": thread, "projectId": project, "target": c["target"] as Any, "newModels": 0, "newChats": 0, "wholeFeaturePassed": false, "listenerReadyForOneOtherDeviceInput": false, "maxLocalInputs": 0, "maxOtherDeviceRuns": 1, "bodySHA256": bodySHA]
    func save(_ stage: String) throws {
      report["stage"] = stage; report["status"] = stage
      try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func abortRequested() throws -> Bool {
      let file = folder.appendingPathComponent(run + "-local-observation-abort.json")
      guard FileManager.default.fileExists(atPath: file.path) else { return false }
      let value = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
      guard value["caseId"] as? String == run, value["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), value["stopOnlyLocalObservation"] as? Bool == true, value["cancelRemote"] as? Bool == false else { throw NSError(domain: "R4203InvalidLocalAbort", code: 1) }
      return true
    }
    func wait(_ label: String, seconds: Double, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { if try abortRequested() { throw NSError(domain: "R4203LocalObservationEnded", code: 1) }; try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "R4203Wait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    // CoreDevice may expose a destination while a copy is still in progress.
    // Wait for two identical, complete local JSON reads; validate the full case
    // and process-bound permit below. This never retries a remote operation.
    func deliveredObject(_ url: URL, label: String, seconds: Double) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(seconds)
      var prior: Data?
      while Date() < deadline {
        if let data = try? Data(contentsOf: url), !data.isEmpty,
          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
          if prior == data { report[label + "DeliveredBytes"] = data.count; return value }
          prior = data
        } else { prior = nil }
        try await Task.sleep(for: .milliseconds(150))
      }
      throw NSError(domain: "R4147IncompleteLocalDelivery", code: 1, userInfo: [NSLocalizedDescriptionKey: label])
    }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    try save("awaiting-original-app")
    try await wait("foreground original app", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let permitURL = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official same-process activation", seconds: 120) { FileManager.default.fileExists(atPath: permitURL.path) }
    let permit = try await deliveredObject(permitURL, label: "activationPermit", seconds: 120)
    guard permit["caseId"] as? String == run, permit["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
      permit["bundleId"] as? String == Bundle.main.bundleIdentifier, permit["deviceUDID"] as? String == c["deviceUDID"] as? String,
      permit["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "R4203Activation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork", connection.target == target, connection.preference == .lanOnly,
      !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "R4203Target", code: 1) }
    let identity = try CredentialVault.existingToken("easywork-device-identity"), credential = try CredentialVault.existingToken(connection.credentialRef)
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, drafts = store.drafts.values
    let oldThread = try XCTUnwrap(store.selectedThreadId), original = try XCTUnwrap(store.threads.first { $0.id == oldThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    func protectedState() throws -> String {
      var saved = store.saved; saved.drafts = store.drafts.values
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState()
    var failure: Error?
    var samples: [[String: Any]] = []
    do {
      let channel = try await EasyWorkSessions.shared.session(client)
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary),
        (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true,
        let endpoint = c["originalLiveLANEndpoint"] as? String, let pin = c["expectedExistingLANCertificateSHA256"] as? String,
        URL(string: endpoint)?.scheme == "https", URL(string: endpoint)?.port == c["expectedOriginalLANPort"] as? Int,
        connection.candidates.contains(where: { $0.transportId == "lan-wss" && $0.endpoint == endpoint && $0.peerFingerprint == pin }) else { throw NSError(domain: "R4203Runtime", code: 1) }
      let binding = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(credential).utf8)).binding
      guard try easyWorkJSON(binding.target.dictionary) == easyWorkJSON(channelTarget.dictionary) else { throw NSError(domain: "R4203Binding", code: 1) }
      let descriptor = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: binding.target, publicKey: binding.desktopPublicKey, timeout: 10)
      guard descriptor.target == binding.target, descriptor.publicKey == binding.desktopPublicKey, descriptor.fingerprint == pin else { throw NSError(domain: "R4203LAN", code: 1) }
      // Reclaim only independently archived, exact files from prior ended cases.
      // The trusted Documents root may have the normal /var alias; descendants
      // in this reserved Native namespace must not introduce another alias.
      let prior = c["priorNativeArtifacts"] as? [[String: Any]] ?? []
      let knownCases: Set<String> = ["d158ed68-bcd2-42f2-9b6e-f0fd3b09c509", "432b525e-c27c-466f-9c11-a9d9bafb27b5", "d2ada189-b28f-4bd6-87cd-912a3b63f4bf", "fc22ac71-196a-49a5-9539-ef628f77264e", "09253eb2-8308-4faf-a76c-426e4971d550", "1caba776-9430-4cfd-9f3f-c8bef1bda37d"]
      guard prior.count <= 64 else { throw NSError(domain: "R4210CleanupManifest", code: 1) }
      let documentRoot = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath().standardizedFileURL
      let artifactRoot = documentRoot.appendingPathComponent("easywork-hosted-native", isDirectory: true)
      guard artifactRoot.resolvingSymlinksInPath().standardizedFileURL == artifactRoot else { throw NSError(domain: "R4210CleanupRoot", code: 1) }
      var removed: [[String: Any]] = [], retained: [[String: Any]] = [], totalBytes = 0
      for item in prior {
        guard let oldCase = item["caseId"] as? String, knownCases.contains(oldCase), oldCase != run,
          let name = item["name"] as? String, name.hasPrefix(oldCase), name == (name as NSString).lastPathComponent,
          ["json", "png", "txt", "bin"].contains((name as NSString).pathExtension),
          let size = item["bytes"] as? Int, size > 0, size <= 2 * 1024 * 1024,
          let expected = item["sha256"] as? String, expected.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw NSError(domain: "R4210CleanupManifest", code: 2) }
        totalBytes += size; guard totalBytes <= 12 * 1024 * 1024 else { throw NSError(domain: "R4210CleanupManifest", code: 3) }
        let url = artifactRoot.appendingPathComponent(name)
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW)
        if fd < 0 { retained.append(["name": name, "reason": errno == ENOENT ? "already-absent" : "open-denied", "errno": Int(errno)]); continue }
        defer { Darwin.close(fd) }
        var info = stat(), current = stat()
        var data = Data(count: size)
        guard fstat(fd, &info) == 0, (info.st_mode & mode_t(S_IFMT)) == mode_t(S_IFREG), info.st_nlink == 1, info.st_size == off_t(size),
          data.withUnsafeMutableBytes({ Darwin.pread(fd, $0.baseAddress, size, 0) }) == size, sha(data) == expected,
          lstat(url.path, &current) == 0, current.st_dev == info.st_dev, current.st_ino == info.st_ino else { retained.append(["name": name, "reason": "changed-or-unverified-no-delete"]); continue }
        if Darwin.unlink(url.path) == 0 { removed.append(["name": name, "bytes": size, "sha256": expected, "device": String(info.st_dev), "inode": String(info.st_ino)]) }
        else { retained.append(["name": name, "reason": "unlink-failed", "errno": Int(errno)]) }
      }
      report["priorNativeArtifactCleanup"] = ["removed": removed, "retainedOrAbsent": retained, "manifestCount": prior.count]
      try save("prior-owned-native-artifacts-checked")
      await store.refresh(includeHistory: false)
      let entry = try XCTUnwrap(store.threads.first { $0.id == thread && $0.projectId == project })
      store.hostedEasyWorkProductUIPresented = true; store.openThread(entry)
      try await wait("production navigation event reader started", seconds: 30) {
        store.selectedThreadId == thread && store.hostedEasyWorkReadLoopState["eventTaskActive"] as? Bool == true
      }
      report["productionNavigationStartsItsOwnEventReader"] = true
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      // SwiftUI's accessibility identifier need not be attached to the backing
      // UIScrollView. Resolve the real chat scroll through its production pull
      // control, as the physical pagination examiner already does.
      func mountedMessageList() -> UIScrollView? {
        let pulls = views(window).filter { String(describing: type(of: $0)) == "EarlierHistoryPullView" && $0.window === window }
        guard pulls.count == 1 else { return nil }
        var ancestor = pulls[0].superview
        while let view = ancestor {
          if let scroll = view as? UIScrollView {
            return scroll.window === window && !scroll.isHidden && scroll.bounds.height > 200 ? scroll : nil
          }
          ancestor = view.superview
        }
        return nil
      }
      report["selectedThreadBeforeMount"] = store.selectedThreadId
      report["selectedProjectBeforeMount"] = store.selectedProjectId
      report["historyLoadingBeforeMount"] = store.historyLoading
      try save("mounting-original-empty-thread-ui")
      try await wait("exact thread and mounted message list", seconds: 60) {
        store.selectedThreadId == thread && store.selectedProjectId == project && !store.historyLoading &&
          store.hostedEasyWorkReadLoopState["eventTaskActive"] as? Bool == true && mountedMessageList() != nil
      }
      report["runtimeContext"] = context
      report["eventSequence"] = await channel.eventSequence
      report["sourceHistoryIds"] = store.messages.map(\.id)
      report["readLoopState"] = store.hostedEasyWorkReadLoopState
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      let png = try XCTUnwrap(image.pngData()), screenshot = run + "-preflight.png"
      try png.write(to: folder.appendingPathComponent(screenshot), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["preflightScreenshot"] = screenshot; report["preflightScreenshotSHA256"] = sha(png)
      guard store.error == nil, !store.historyLoading, !store.isRunning, store.messages.isEmpty, store.draft.text.isEmpty, store.draft.attachments.isEmpty else { throw NSError(domain: "R4203NewThreadNotEmpty", code: 1) }
      report["parentMustVerifyAuthenticatedSubscribeAndSuccessfulEmptyHistoryBeforeInput"] = true
      report["listenerReadyForOneOtherDeviceInput"] = true
      try save("listener-ready")
      let began = ProcessInfo.processInfo.systemUptime
      var previous = "", lastSaved = Date.distantPast, lastPicture = Date.distantPast
      var completed: ChatMessage?, observedRun: String?, observedMessage: String?
      let list = try XCTUnwrap(mountedMessageList())
      report["messageListResolvedThroughActualProductionEarlierHistoryPullView"] = true
      // In-progress MessageView is real SwiftUI Text(verbatim:), not MarkdownBody.
      // Capture its actual window pixels. Independent Vision OCR must prove visible
      // changed lines for >=2 frames; Store growth alone never qualifies this clause.
      while ProcessInfo.processInfo.systemUptime - began < 600 {
        if try abortRequested() { throw NSError(domain: "R4203LocalObservationEnded", code: 1) }
        guard store.selectedThreadId == thread, store.client?.id == client.id, store.client?.agentConnection?.target == target, UIApplication.shared.applicationState == .active,
          store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "R4203ListenerTargetChanged", code: 1) }
        let inputs = store.messages.filter { $0.role == "user" }
        guard inputs.count <= 1, inputs.allSatisfy({ sha(Data($0.text.utf8)) == bodySHA }) else { throw NSError(domain: "R4203UnexpectedInput", code: 1) }
        if let partial = store.messages.last(where: { $0.role == "assistant" && $0.text.contains(start) }) {
          if store.isRunning, partial.text != previous, samples.count < 8, Date().timeIntervalSince(lastPicture) >= 1.25,
            partial.text.utf16.count >= 120, let runId = partial.runId, !runId.isEmpty {
            guard inputs.count == 1, observedRun == nil || observedRun == runId, observedMessage == nil || observedMessage == partial.id else { throw NSError(domain: "R4203MultipleRuns", code: 1) }
            observedRun = runId; observedMessage = partial.id
            if !previous.isEmpty, !partial.text.hasPrefix(previous) { throw NSError(domain: "R4203NonAppendBody", code: 1) }
            let viewport = list.convert(list.bounds, to: window).intersection(CGRect(x: 8, y: 135, width: window.bounds.width - 16, height: window.bounds.height - 395))
            guard viewport.width > 100, viewport.height > 100 else { throw NSError(domain: "R4203Viewport", code: 1) }
            let sampleTime = ProcessInfo.processInfo.systemUptime
            let pixels = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
            if store.isRunning, store.messages.contains(where: { $0.id == partial.id && $0.text == partial.text && $0.runId == runId }) {
              let png = try XCTUnwrap(pixels.pngData()), index = samples.count, imageName = run + "-actual-running-\(index).png", partialName = run + "-partial-\(index).txt"
              try png.write(to: folder.appendingPathComponent(imageName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
              try Data(partial.text.utf8).write(to: folder.appendingPathComponent(partialName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
              samples.append(["uptimeSeconds": sampleTime, "messageId": partial.id, "runId": runId, "runningBeforeAndAfterSynchronousPixelCapture": true,
                "partialUTF16Length": partial.text.utf16.count, "partialUTF8SHA256": sha(Data(partial.text.utf8)), "partialTextFile": partialName,
                "screenshot": imageName, "screenshotSHA256": sha(png), "windowPoints": [window.bounds.width, window.bounds.height],
                "conservativeMessageViewportPoints": [viewport.minX, viewport.minY, viewport.width, viewport.height],
                "visibleTextQualification": "pending independent OCR of these actual pixels; no Store/transparent-view visible claim"])
              report["runningPixelCandidates"] = samples; try save("observing-one-real-run-no-manual-refresh")
              previous = partial.text; lastPicture = Date()
            }
          }
          if !store.isRunning, ["complete", "completed"].contains(partial.status ?? ""), partial.text.contains(end) { completed = partial; break }
        }
        if Date().timeIntervalSince(lastSaved) >= 1.5 {
          report["runningPixelCandidates"] = samples; report["readLoopState"] = store.hostedEasyWorkReadLoopState
          try save("observing-one-real-run-no-manual-refresh"); lastSaved = Date()
        }
        if !store.isRunning, ["failed", "interrupted", "cancelled"].contains(store.runtime?.state ?? "") { throw NSError(domain: "R4203ActualRunFailed", code: 1, userInfo: [NSLocalizedDescriptionKey: store.runtime?.error ?? "Original run failed; never send or replay"]) }
        try await Task.sleep(for: .milliseconds(120))
      }
      let final = try XCTUnwrap(completed), finalRun = try XCTUnwrap(final.runId)
      report["observedTemporaryMessageId"] = observedMessage; report["finalPersistedMessageId"] = final.id
      report["finalCandidateRunId"] = finalRun; report["finalCandidateTextSHA256"] = sha(Data(final.text.utf8))
      // The in-progress projection uses active-run:<run>:<part>; persisted history
      // legitimately has its own message UUID. The immutable run and text bind them.
      guard !finalRun.isEmpty, finalRun == observedRun, final.text.hasPrefix(previous), store.messages.filter({ $0.role == "user" }).count == 1 else { throw NSError(domain: "R4203MissingFinalRun", code: 1) }
      let finalName = run + "-actual-final-reply.txt"
      try Data(final.text.utf8).write(to: folder.appendingPathComponent(finalName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["actualRunId"] = finalRun; report["finalMessageId"] = final.id; report["finalTextFile"] = finalName
      report["finalUTF16Length"] = final.text.utf16.count; report["finalUTF8SHA256"] = sha(Data(final.text.utf8)); report["runningPixelCandidates"] = samples
      report["eventSequenceAfterRun"] = await channel.eventSequence
      guard samples.count >= 2 else { throw NSError(domain: "R4203TooFewNonterminalPixelCandidates", code: 1) }
      report["oneOriginalRunPixelCandidatesObserved"] = true
      report["pixelOCRQualificationStillRequired"] = true
      try save("completed-run-independent-OCR-pending")
    } catch { failure = error; report["runningPixelCandidates"] = samples; report["failureDescription"] = error.localizedDescription }
    store.openThread(original); store.selectedProjectId = oldProject; store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id]; store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id]; store.saved.newProjects[client.id] = old.newProjects[client.id]; store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, store.drafts.values == drafts,
        try encoder.encode(VoiceMigrationSafety.snapshot()) == voice,
        try CredentialVault.existingToken("easywork-device-identity") == identity,
        try CredentialVault.existingToken(connection.credentialRef) == credential,
        store.selectedThreadId == oldThread, store.selectedProjectId == oldProject else { throw NSError(domain: "R4203Protection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription; if failure == nil { failure = error } }
    try save(failure == nil ? "completed-controlled-stream-OCR-pending" : "failed-controlled-listener-preserve-no-send")
    if let failure { throw failure }
  }
  @MainActor func testOriginalVisibleStreamFinalReadOnly() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Paired physical iPhone required")
    #endif
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkProductUIEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      let c = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
      c["caseId"] as? String == run, c["newModelsAuthorized"] as? Int == 0,
      c["newChatsAuthorized"] as? Int == 0,
      c["deviceUDID"] as? String == "00008130-001C48620120001C",
      c["coreDeviceId"] as? String == "A973988F-57A3-5D0D-839F-797968DFB335",
      c["bundleId"] as? String == "com.easyai.agenthub.easyworkacceptance",
      Bundle.main.bundleIdentifier == c["bundleId"] as? String,
      UIDevice.current.userInterfaceIdiom == .phone,
      let thread = c["fixtureThreadId"] as? String, UUID(uuidString: thread) != nil,
      let project = c["projectId"] as? String, !project.isEmpty,
      let start = c["replyStartMarker"] as? String, !start.isEmpty,
      let end = c["replyEndMarker"] as? String, !end.isEmpty, start != end,
      let bodySHA = c["bodySHA256"] as? String, bodySHA.count == 64, bodySHA.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
      c["maxLocalInputs"] as? Int == 0, c["maxOtherDeviceRuns"] as? Int == 0, c["newImportsAuthorized"] as? Int == 0 else {
      throw XCTSkip("Explicit R4203 read-only configuration required")
    }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let output = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: output.path) else { throw NSError(domain: "R4203ExistingCase", code: 1) }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": Int(ProcessInfo.processInfo.processIdentifier), "bundleId": Bundle.main.bundleIdentifier!, "threadId": thread, "projectId": project, "target": c["target"] as Any, "newModels": 0, "newChats": 0, "wholeFeaturePassed": false, "listenerReadyForOneOtherDeviceInput": false, "maxLocalInputs": 0, "maxOtherDeviceRuns": 0, "bodySHA256": bodySHA]
    func save(_ stage: String) throws {
      report["stage"] = stage; report["status"] = stage
      try easyWorkJSON(report).write(to: output, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func abortRequested() throws -> Bool {
      let file = folder.appendingPathComponent(run + "-local-observation-abort.json")
      guard FileManager.default.fileExists(atPath: file.path) else { return false }
      let value = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
      guard value["caseId"] as? String == run, value["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier), value["stopOnlyLocalObservation"] as? Bool == true, value["cancelRemote"] as? Bool == false else { throw NSError(domain: "R4203InvalidLocalAbort", code: 1) }
      return true
    }
    func wait(_ label: String, seconds: Double, _ condition: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(seconds)
      while !condition() && Date() < deadline { if try abortRequested() { throw NSError(domain: "R4203LocalObservationEnded", code: 1) }; try await Task.sleep(for: .milliseconds(100)) }
      guard condition() else { throw NSError(domain: "R4203Wait", code: 1, userInfo: [NSLocalizedDescriptionKey: label]) }
    }
    // CoreDevice may expose a destination while a copy is still in progress.
    // Wait for two identical, complete local JSON reads; validate the full case
    // and process-bound permit below. This never retries a remote operation.
    func deliveredObject(_ url: URL, label: String, seconds: Double) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(seconds)
      var prior: Data?
      while Date() < deadline {
        if let data = try? Data(contentsOf: url), !data.isEmpty,
          let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
          if prior == data { report[label + "DeliveredBytes"] = data.count; return value }
          prior = data
        } else { prior = nil }
        try await Task.sleep(for: .milliseconds(150))
      }
      throw NSError(domain: "R4147IncompleteLocalDelivery", code: 1, userInfo: [NSLocalizedDescriptionKey: label])
    }
    func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func views(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(views) }
    try save("awaiting-original-app")
    try await wait("foreground original app", seconds: 120) { SessionStore.hostedEasyWorkAcceptanceStore != nil && UIApplication.shared.applicationState == .active }
    let permitURL = folder.appendingPathComponent(run + "-activation-permit.json")
    try await wait("official same-process activation", seconds: 120) { FileManager.default.fileExists(atPath: permitURL.path) }
    let permit = try await deliveredObject(permitURL, label: "activationPermit", seconds: 120)
    guard permit["caseId"] as? String == run, permit["nativeProcessId"] as? Int == Int(ProcessInfo.processInfo.processIdentifier),
      permit["bundleId"] as? String == Bundle.main.bundleIdentifier, permit["deviceUDID"] as? String == c["deviceUDID"] as? String,
      permit["officialSameRunningProcessActivated"] as? Bool == true else { throw NSError(domain: "R4203Activation", code: 1) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork", connection.target == target, connection.preference == .lanOnly,
      !client.isDemo, store.error == nil, store.sheet == nil, !store.drawerOpen else { throw NSError(domain: "R4203Target", code: 1) }
    let identity = try CredentialVault.existingToken("easywork-device-identity"), credential = try CredentialVault.existingToken(connection.credentialRef)
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let voice = try encoder.encode(VoiceMigrationSafety.snapshot()), old = store.saved, drafts = store.drafts.values
    let oldThread = try XCTUnwrap(store.selectedThreadId), original = try XCTUnwrap(store.threads.first { $0.id == oldThread })
    let oldProject = store.selectedProjectId, oldUI = store.hostedEasyWorkProductUIPresented
    func protectedState() throws -> String {
      var saved = store.saved; saved.drafts = store.drafts.values
      for i in saved.clients.indices { saved.clients[i].online = false }
      var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: encoder.encode(saved)) as? [String: Any])
      for key in ["history", "historyCacheKeys", "workspaces", "runtimes", "agentEventCursors", "mobileReads", "mobileReadBaselines"] { object.removeValue(forKey: key) }
      return sha(try easyWorkJSON(object))
    }
    let baseline = try protectedState()
    var failure: Error?
    do {
      let channel = try await EasyWorkSessions.shared.session(client)
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let channelTarget = await channel.target
      guard try easyWorkJSON(context["target"] ?? [:]) == easyWorkJSON(channelTarget.dictionary),
        (context["state"] as? [String: Any])?["runtimeConnected"] as? Bool == true,
        let endpoint = c["originalLiveLANEndpoint"] as? String, let pin = c["expectedExistingLANCertificateSHA256"] as? String,
        URL(string: endpoint)?.scheme == "https", URL(string: endpoint)?.port == c["expectedOriginalLANPort"] as? Int,
        connection.candidates.contains(where: { $0.transportId == "lan-wss" && $0.endpoint == endpoint && $0.peerFingerprint == pin }) else { throw NSError(domain: "R4203Runtime", code: 1) }
      let binding = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(credential).utf8)).binding
      guard try easyWorkJSON(binding.target.dictionary) == easyWorkJSON(channelTarget.dictionary) else { throw NSError(domain: "R4203Binding", code: 1) }
      let descriptor = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: binding.target, publicKey: binding.desktopPublicKey, timeout: 10)
      guard descriptor.target == binding.target, descriptor.publicKey == binding.desktopPublicKey, descriptor.fingerprint == pin else { throw NSError(domain: "R4203LAN", code: 1) }
      let entry = try XCTUnwrap(store.threads.first { $0.id == thread && $0.projectId == project })
      store.hostedEasyWorkProductUIPresented = true; store.openThread(entry)
      try await wait("production navigation event reader started", seconds: 30) {
        store.selectedThreadId == thread && store.hostedEasyWorkReadLoopState["eventTaskActive"] as? Bool == true
      }
      report["productionNavigationStartsItsOwnEventReader"] = true
      let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first { $0.isKeyWindow })
      // SwiftUI's accessibility identifier need not be attached to the backing
      // UIScrollView. Resolve the real chat scroll through its production pull
      // control, as the physical pagination examiner already does.
      func mountedMessageList() -> UIScrollView? {
        let pulls = views(window).filter { String(describing: type(of: $0)) == "EarlierHistoryPullView" && $0.window === window }
        guard pulls.count == 1 else { return nil }
        var ancestor = pulls[0].superview
        while let view = ancestor {
          if let scroll = view as? UIScrollView {
            return scroll.window === window && !scroll.isHidden && scroll.bounds.height > 200 ? scroll : nil
          }
          ancestor = view.superview
        }
        return nil
      }
      report["selectedThreadBeforeMount"] = store.selectedThreadId
      report["selectedProjectBeforeMount"] = store.selectedProjectId
      report["historyLoadingBeforeMount"] = store.historyLoading
      try save("mounting-original-completed-thread-ui")
      try await wait("exact thread and mounted message list", seconds: 60) {
        store.selectedThreadId == thread && store.selectedProjectId == project && !store.historyLoading &&
          store.hostedEasyWorkReadLoopState["eventTaskActive"] as? Bool == true && mountedMessageList() != nil
      }
      report["runtimeContext"] = context
      report["eventSequence"] = await channel.eventSequence
      report["sourceHistoryIds"] = store.messages.map(\.id)
      report["readLoopState"] = store.hostedEasyWorkReadLoopState
      let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
      let png = try XCTUnwrap(image.pngData()), screenshot = run + "-preflight.png"
      try png.write(to: folder.appendingPathComponent(screenshot), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["preflightScreenshot"] = screenshot; report["preflightScreenshotSHA256"] = sha(png)
      let expectedRun = try XCTUnwrap(c["originalRunId"] as? String), digest = try XCTUnwrap(c["expectedFinalSHA256"] as? String)
      guard expectedRun == "9cbcc2a1-d78c-4667-be92-9d1597113cf1", thread == "77c32c90-f708-463a-9782-c3c4c2d9f15e" else { throw NSError(domain: "R4229OriginalTarget", code: 1) }
      try await wait("fresh original terminal body", seconds: 60) {
        !store.historyLoading && !store.isRunning && store.messages.contains(where: { $0.role == "assistant" && $0.runId == expectedRun && sha(Data($0.text.utf8)) == digest })
      }
      let final = try XCTUnwrap(store.messages.last(where: { $0.role == "assistant" && $0.runId == expectedRun && sha(Data($0.text.utf8)) == digest }))
      let inputs = store.messages.filter { $0.role == "user" }
      guard ["complete", "completed"].contains(final.status ?? ""), final.text.hasPrefix(start), final.text.hasSuffix(end), inputs.count == 1, sha(Data(inputs[0].text.utf8)) == bodySHA, store.draft.text.isEmpty, store.draft.attachments.isEmpty, store.error == nil else { throw NSError(domain: "R4229FinalBody", code: 1) }
      let finalName = run + "-actual-final-reply.txt"
      try Data(final.text.utf8).write(to: folder.appendingPathComponent(finalName), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      report["actualRunId"] = final.runId; report["finalMessageId"] = final.id; report["finalTextFile"] = finalName
      report["finalUTF8SHA256"] = digest; report["finalUTF16Length"] = final.text.utf16.count
      report["eventSequenceAfterRead"] = await channel.eventSequence
      report["readOnlyOriginalTerminalVerified"] = true; report["newInputs"] = 0
    } catch { failure = error; report["failureDescription"] = error.localizedDescription }
    store.openThread(original); store.selectedProjectId = oldProject; store.hostedEasyWorkProductUIPresented = oldUI
    store.saved.lastThreads[client.id] = old.lastThreads[client.id]; store.saved.lastThreadProjects?[client.id] = old.lastThreadProjects?[client.id]; store.saved.newProjects[client.id] = old.newProjects[client.id]; store.persist(reconcileUnread: false)
    do {
      guard try protectedState() == baseline, store.drafts.values == drafts,
        try encoder.encode(VoiceMigrationSafety.snapshot()) == voice,
        try CredentialVault.existingToken("easywork-device-identity") == identity,
        try CredentialVault.existingToken(connection.credentialRef) == credential,
        store.selectedThreadId == oldThread, store.selectedProjectId == oldProject else { throw NSError(domain: "R4203Protection", code: 1) }
      report["originalLogicalProtectionMatchedFinally"] = true
    } catch { report["protectionError"] = error.localizedDescription; if failure == nil { failure = error } }
    try save(failure == nil ? "completed-original-final-readonly" : "failed-original-final-readonly-no-input")
    if let failure { throw failure }
  }

}
