#if DEBUG
import Foundation

/// Use the existing file-input path without selecting a different saved-session directory.
/// The normal provider still owns consent, the exact target and the real ASR operation.
enum EasyWorkVoiceRecoveryAcceptance {
  static let completion = CompletionDeliveryFault()
  static var requested: Bool {
    let args = ProcessInfo.processInfo.arguments
    return Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance"
      && (args.contains("-voice-recovery-acceptance")
        || (args.contains("-voice-file-fixture") && !args.contains("-ui-testing")))
  }
  static func allowsFixture(target: String) -> Bool {
    let env = ProcessInfo.processInfo.environment
    let args = ProcessInfo.processInfo.arguments
    guard requested,
      args.contains("-easywork-local-dev"), args.contains("-voice-recovery-acceptance"),
      args.contains("-voice-file-fixture"), !args.contains("-ui-testing"),
      env["VOICE_FILE_FIXTURE_TARGET_SHA256"] == HostService.hash(Data(target.utf8)),
      let hash = env["VOICE_FILE_FIXTURE_SHA256"],
      hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      let address = env["VOICE_FILE_FIXTURE_URL"], let source = URL(string: address),
      source.scheme == "http", source.host == "127.0.0.1" else { return false }
    return true
  }

  actor CompletionDeliveryFault {
    private var originalOperationId: String?
    private var reportedRecovery = false
    func afterVerifiedCompletion(_ manifest: VoiceTranscriptionRequest, queryOnly: Bool) throws {
      guard manifest.draftId.hasPrefix("voice-v1:"),
        let bytes = Data(base64Encoded: String(manifest.draftId.dropFirst("voice-v1:".count))),
        let scope = try? JSONDecoder().decode([String].self, from: bytes), scope.count == 2,
        EasyWorkVoiceRecoveryAcceptance.allowsFixture(target: scope[1]),
        ProcessInfo.processInfo.environment["VOICE_FILE_FIXTURE_SHA256"] == manifest.sha256,
        ProcessInfo.processInfo.environment["EASYWORK_DROP_VOICE_COMPLETION_ONCE"] == "1" else { return }
      if queryOnly, !reportedRecovery,
        originalOperationId == nil || originalOperationId == manifest.operationId {
        reportedRecovery = true
        let marker = originalOperationId == nil ? "queried-original" : "recovered-original"
        print("EASYWORK_VOICE_RESULT", marker, manifest.operationId)
      } else if !queryOnly, originalOperationId == nil {
        originalOperationId = manifest.operationId
        print("EASYWORK_VOICE_RESULT completion-dropped", manifest.operationId)
        throw URLError(.networkConnectionLost)
      }
    }
  }
}
#endif
