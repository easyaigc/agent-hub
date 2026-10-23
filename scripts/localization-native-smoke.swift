import Foundation

@main
struct LocalizationSmoke {
  static func main() {
    let defaults = UserDefaults.standard
    let previous = defaults.string(forKey: L10n.storageKey)
    defer {
      if let previous { defaults.set(previous, forKey: L10n.storageKey) }
      else { defaults.removeObject(forKey: L10n.storageKey) }
    }
    precondition(AppLanguage.system.resolved(preferredLanguages: ["zh-Hant-TW"]) == "zh-Hans")
    precondition(AppLanguage.system.resolved(preferredLanguages: ["fr-FR", "zh-CN", "en-US"]) == "zh-Hans")
    precondition(AppLanguage.system.resolved(preferredLanguages: ["en-GB", "zh-CN"]) == "en")
    precondition(AppLanguage.system.resolved(preferredLanguages: ["fr-FR"]) == "en")
    precondition(AppLanguage.english.resolved(preferredLanguages: ["zh-CN"]) == "en")
    defaults.set("en", forKey: L10n.storageKey)
    precondition(L10n.tr("设置") == "Settings")
    precondition(L10n.tr("允许一次") == "Allow once")
    precondition(TaskNoticeKind.approval.title == "Approval needed")
    precondition(BusinessNoticeKind.acceptance_required.title == "Task acceptance needed")
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "test-host", workspaceId: "test-workspace", runtimeId: "test-runtime")
    let event = TaskNotificationEvent(version: 1, id: "test-event", target: target, kind: .completed,
      occurredAt: "2026-10-15T00:00:00Z", threadTitle: "用户标题 100%", taskSummary: "原始任务摘要")
    let presentation = TaskNotificationPresentation(event: event, computer: "电脑名字", showPreview: true)
    precondition(presentation.title == "用户标题 100%")
    precondition(presentation.summary == "原始任务摘要")
    precondition(presentation.metadata == "电脑名字 · Task completed")
    let filename = "报告 100% %@.txt"
    precondition(L10n.tr("上传附件 \(2)/\(4) · \(filename)") == "Uploading attachment 2/4 · \(filename)")
    precondition(L10n.tr("Progress 100% \(filename)") == "Progress 100% \(filename)")
    precondition(L10n.tr("Missing key") == "Missing key")
    let tls = NSError(domain: NSURLErrorDomain, code: URLError.secureConnectionFailed.rawValue,
      userInfo: [NSLocalizedDescriptionKey: "TLS error with private endpoint"])
    precondition(OperationFailure(tls).title == "Unable to connect to your computer")
    precondition(tls.userFacingDescription.contains("secure connection"))
    precondition(!tls.userFacingDescription.contains("TLS"))
    precondition(!tls.userFacingDescription.contains("private endpoint"))
    precondition(URLError(.serverCertificateUntrusted).userFacingDescription.contains("could not be verified"))
    precondition(URLError(.notConnectedToInternet).userFacingDescription.contains("Wi-Fi"))
    precondition(URLError(.timedOut).userFacingDescription.contains("timed out"))
    precondition(URLError(.cannotConnectToHost).userFacingDescription.contains("VPN"))
    precondition(URLError(.networkConnectionLost).userFacingDescription.contains("interrupted"))
    let rejected = NSError(domain: "HostIdentity", code: 1,
      userInfo: [NSLocalizedDescriptionKey: "Computer identity mismatch"])
    precondition(OperationFailure(rejected).title == "Unable to complete the operation")
    precondition(rejected.userFacingDescription == "Computer identity mismatch")
    let cancelled = URLError(.cancelled)
    precondition(cancelled.userFacingDescription == cancelled.localizedDescription)
    defaults.set("zh-Hans", forKey: L10n.storageKey)
    precondition(L10n.tr("设置") == "设置")
    precondition(OperationFailure(tls).title == "暂时无法连接电脑")
    precondition(tls.userFacingDescription.contains("安全连接未能建立"))
    precondition(URLError(.serverCertificateUntrusted).userFacingDescription.contains("检查连接设置"))
    precondition(TaskNoticeKind.approval.title == "待审批")
    precondition(BusinessNoticeKind.acceptance_required.title == "任务待验收")
    precondition(L10n.tr("上传附件 \(2)/\(4) · \(filename)") == "上传附件 2/4 · \(filename)")
    precondition(AppLanguage.system.title == "跟随系统")
    defaults.set("en", forKey: L10n.storageKey)
    precondition(AppLanguage.system.title == "System")
    precondition(L10n.tr("跟随系统，或选择中文和英文。更改会立即生效。").contains("immediately"))
    print("Localization native checks passed: language resolution, bundle lookup, live switching, fallback, and opaque interpolated content")
  }
}
