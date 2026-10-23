import AVFoundation
import CryptoKit
import Foundation
import UserNotifications

@main struct NotificationNativeSmoke {
  @MainActor static func main() async throws {
    if CommandLine.arguments.count == 3 && CommandLine.arguments[1] == "import" {
      let item = try TaskNotificationSoundLibrary.importFile(URL(fileURLWithPath: CommandLine.arguments[2]))
      let audio = try AVAudioFile(forReading: TaskNotificationSoundLibrary.directory.appendingPathComponent(item.id))
      precondition(audio.fileFormat.commonFormat == .pcmFormatInt16 && audio.fileFormat.channelCount == 1)
      print("Native MP3 decode and CAF conversion passed"); return
    }
    if CommandLine.arguments.contains("decrypt") {
      let data = FileHandle.standardInput.readDataToEndOfFile()
      let value = try JSONSerialization.jsonObject(with: data) as! [String: Any]
      let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(base64Encoded: value["key"] as! String)!)
      let binding = try JSONDecoder().decode(TaskNoticeBinding.self, from: JSONSerialization.data(withJSONObject: value["binding"]!))
      let event = try TaskNotificationShared.openEnvelope(value["envelope"] as! [String: Any], privateKey: key, binding: binding)
      FileHandle.standardOutput.write(try JSONEncoder().encode(event)); return
    }
    let monitor = NotificationHostMonitor(interval: .milliseconds(10))
    var slowStarted = false, healthyPolls = 0
    monitor.reconcile(["offline", "healthy"]) { id in
      if id == "offline" { slowStarted = true; try? await Task.sleep(for: .seconds(30)) }
      else { healthyPolls += 1 }
    }
    let deadline = ContinuousClock.now.advanced(by: .seconds(2))
    while (!slowStarted || healthyPolls < 3) && ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(10)) }
    precondition(slowStarted && healthyPolls >= 3, "An offline target blocked a healthy notification target")
    monitor.reconcile(["offline"]) { _ in preconditionFailure("Existing target must retain one poller") }
    let removedCount = healthyPolls
    try await Task.sleep(for: .milliseconds(40))
    precondition(healthyPolls == removedCount, "Removed target continued polling")
    monitor.cancel()
    monitor.reconcile(["replacement"]) { id in precondition(id == "replacement"); healthyPolls += 1 }
    try await Task.sleep(for: .milliseconds(40)); monitor.cancel()
    let stoppedCount = healthyPolls
    try await Task.sleep(for: .milliseconds(40))
    precondition(stoppedCount > removedCount && healthyPolls == stoppedCount)
    print("Native notification target isolation: blocked peer, removal, restart and cancellation passed")
    guard let root = ProcessInfo.processInfo.environment["CODEX_NOTIFICATION_TEST_DIRECTORY"] else { fatalError("Select an isolated native test directory") }
    let directory = URL(fileURLWithPath: root, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "fixture-host", workspaceId: "fixture-workspace", runtimeId: "desktop-runtime")
    let notification = TaskNotificationEvent(version: 1, id: String(repeating: "a", count: 64), target: target, kind: .approval, occurredAt: "2026-10-10T00:00:00.000Z", threadTitle: "审查通知功能", taskSummary: "核对正式签名手机的审批提醒")
    precondition(TaskNotificationValidation.event(notification))
    let visible = TaskNotificationPresentation(event: notification, computer: "验收电脑", showPreview: true)
    precondition(visible.title == "审查通知功能" && visible.summary == "核对正式签名手机的审批提醒" && visible.metadata == "验收电脑 · 待审批")
    let systemContent = UNMutableNotificationContent()
    visible.apply(to: systemContent)
    precondition(systemContent.title == visible.title && systemContent.subtitle == visible.metadata && systemContent.body == visible.summary)
    let hidden = TaskNotificationPresentation(event: notification, computer: "验收电脑", showPreview: false)
    precondition(hidden.title == "待审批" && hidden.summary.isEmpty && hidden.metadata == visible.metadata)
    hidden.apply(to: systemContent)
    precondition(systemContent.title == "待审批" && systemContent.body.isEmpty && !systemContent.subtitle.contains("核对"))
    var question = notification; question.kind = .question; question.taskSummary = "请选择本次交付格式"
    let questionPreview = TaskNotificationPresentation(event: question, computer: "验收电脑", showPreview: true)
    precondition(questionPreview.summary == "请选择本次交付格式" && questionPreview.kind == "待回答")
    let privateQuestion = TaskNotificationPresentation(event: question, computer: "验收电脑", showPreview: false)
    precondition(privateQuestion.title == "待回答" && privateQuestion.summary.isEmpty)
    var uncertain = notification; uncertain.kind = .uncertain; uncertain.taskSummary = "核对已提交消息是否执行：优化通知内容"
    let uncertainPreview = TaskNotificationPresentation(event: uncertain, computer: "验收电脑", showPreview: true)
    uncertainPreview.apply(to: systemContent)
    precondition(systemContent.title == notification.threadTitle && systemContent.body == uncertain.taskSummary && systemContent.subtitle == "验收电脑 · 操作结果待确认")
    var explicitPrivate = TaskNotificationPreferences(); explicitPrivate.showThreadTitle = false
    let retained = try JSONDecoder().decode(TaskNotificationPreferences.self, from: JSONEncoder().encode(explicitPrivate))
    precondition(!retained.showThreadTitle && TaskNotificationPreferences().showThreadTitle)
    var missingTitle = notification; missingTitle.threadTitle = nil; missingTitle.threadId = "abcdef12-other"
    precondition(TaskNotificationPresentation(event: missingTitle, computer: "验收电脑", showPreview: true).title == "对话 · abcdef12")
    var legacy = try JSONSerialization.jsonObject(with: JSONEncoder().encode(notification)) as! [String: Any]
    legacy.removeValue(forKey: "taskSummary")
    let restored = try JSONDecoder().decode(TaskNotificationEvent.self, from: JSONSerialization.data(withJSONObject: legacy))
    precondition(restored.taskSummary == nil && TaskNotificationValidation.event(restored))
    precondition(!TaskNotificationPresentation(event: restored, computer: "验收电脑", showPreview: true).summary.isEmpty)
    var invalid = notification; invalid.taskSummary = String(repeating: "a", count: 161)
    precondition(!TaskNotificationValidation.event(invalid))
    invalid.taskSummary = "摘要\n另一行"; precondition(!TaskNotificationValidation.event(invalid))
    for value in ["", " 前导空格", "尾随空格 ", "重复  空格", "隐藏\u{200B}字符", "方向\u{202E}字符", String(repeating: "🚀", count: 81)] {
      invalid.taskSummary = value; precondition(!TaskNotificationValidation.event(invalid))
    }
    print("Native notification content: title, task summary, right-side metadata, privacy and legacy decoding passed")
    try TaskNotificationSoundLibrary.prepare()
    for name in ["soft.caf", "chime.caf", "attention.caf"] {
      let audio = try AVAudioFile(forReading: TaskNotificationSoundLibrary.directory.appendingPathComponent(name))
      precondition(audio.length > 0 && Double(audio.length) / audio.fileFormat.sampleRate < 30)
    }
    func fixture(_ name: String, seconds: Double) throws -> URL {
      let url = directory.appendingPathComponent(name), format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 2)!
      let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(seconds * 44100))!
      buffer.frameLength = buffer.frameCapacity
      for channel in 0..<2 { for frame in 0..<Int(buffer.frameLength) { buffer.floatChannelData![channel][frame] = Float(sin(Double(frame) / 44100 * 2 * .pi * 660) * 0.2) } }
      var settings = format.settings; settings[AVLinearPCMIsNonInterleaved] = false
      if url.pathExtension == "m4a" { settings = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 128000] }
      let audio = try AVAudioFile(forWriting: url, settings: settings); try audio.write(from: buffer)
      return url
    }
    for name in ["input.wav", "input.aiff", "input.caf", "input.m4a"] {
      let input = try fixture(name, seconds: 0.3), item = try TaskNotificationSoundLibrary.importFile(input)
      let audio = try AVAudioFile(forReading: TaskNotificationSoundLibrary.directory.appendingPathComponent(item.id))
      precondition(audio.fileFormat.commonFormat == .pcmFormatInt16 && audio.fileFormat.channelCount == 1)
      precondition(abs(Double(audio.length) / audio.fileFormat.sampleRate - 0.3) < 0.01)
      try TaskNotificationSoundLibrary.validateArchiveSound(Data(contentsOf: TaskNotificationSoundLibrary.directory.appendingPathComponent(item.id)))
      try FileManager.default.removeItem(at: TaskNotificationSoundLibrary.directory.appendingPathComponent(item.id))
    }
    _ = try TaskNotificationSoundLibrary.importFile(fixture("maximum.wav", seconds: 29))
    let tooLong = try fixture("long.wav", seconds: 29.1)
    do { _ = try TaskNotificationSoundLibrary.importFile(tooLong); preconditionFailure("Long sounds must be rejected") } catch {}
    let oversized = directory.appendingPathComponent("oversized.wav")
    try Data(repeating: 0, count: 10 * 1024 * 1024 + 1).write(to: oversized)
    do { _ = try TaskNotificationSoundLibrary.importFile(oversized); preconditionFailure("Large sounds must be rejected") } catch {}
    let corrupt = directory.appendingPathComponent("corrupt.mp3"); try Data("broken audio".utf8).write(to: corrupt)
    do { _ = try TaskNotificationSoundLibrary.importFile(corrupt); preconditionFailure("Broken audio must be rejected") } catch {}
    do { try TaskNotificationSoundLibrary.validateArchiveSound(Data("broken CAF".utf8)); preconditionFailure("Invalid migration audio must be rejected before restore") } catch {}
    let selected = try TaskNotificationSoundLibrary.importFile(fixture("delete-selected.wav", seconds: 0.3))
    try TaskNotificationShared.write([selected], name: "sounds.json")
    var prefs = TaskNotificationPreferences(); prefs.sound = selected.id; prefs.categorySounds = ["approval": selected.id, "failed": "none"]
    try TaskNotificationShared.write(prefs, name: "preferences.json")
    do { try TaskNotificationSoundLibrary.removeImported(selected); preconditionFailure("Referenced sounds cannot be deleted before preferences are persisted") } catch {}
    prefs.resetSoundReferences(selected.id); try TaskNotificationShared.write(prefs, name: "preferences.json")
    try TaskNotificationSoundLibrary.removeImported(selected)
    precondition(TaskNotificationShared.preferences.sound == "default" && TaskNotificationShared.preferences.categorySounds["approval"] == nil && TaskNotificationShared.preferences.categorySounds["failed"] == "none")
    precondition(!TaskNotificationSoundLibrary.sounds.contains { $0.id == selected.id })
    precondition(!FileManager.default.fileExists(atPath: TaskNotificationSoundLibrary.directory.appendingPathComponent(selected.id).path))
    print("Native selected ringtone deletion: persisted default, category fallback and file removal passed")
    print("Native AVFoundation: built-ins, WAV/AIFF/CAF/M4A conversion, 29-second limit, 10 MB limit and corrupt audio passed")
    let original = Data("Synthetic connection, draft, attachment and uncertain receipt".utf8)
    let password = "synthetic-migration-passphrase"
    let sealed = try EncryptedMigrationCrypto.seal(original, password: password)
    let opened = try EncryptedMigrationCrypto.open(sealed, password: password)
    precondition(opened == original)
    precondition(!String(decoding: sealed, as: UTF8.self).contains(String(decoding: original, as: UTF8.self)))
    do { _ = try EncryptedMigrationCrypto.open(sealed, password: "incorrect-password"); preconditionFailure("Wrong passwords must fail") } catch {}
    var envelope = try JSONDecoder().decode(EncryptedMigrationCrypto.Envelope.self, from: sealed)
    envelope.ciphertext[envelope.ciphertext.count - 1] ^= 1
    do { _ = try EncryptedMigrationCrypto.open(JSONEncoder().encode(envelope), password: password); preconditionFailure("Tampered archives must fail") } catch {}
    let identities = Data("com.easyai.codexmanage".utf8)
    let identityArchive = try EncryptedMigrationCrypto.seal(identities, password: password)
    let source = String(decoding: try EncryptedMigrationCrypto.open(identityArchive, password: password), as: UTF8.self)
    precondition(MigrationIdentityPolicy.accepts(source: source, destination: "com.easyai.agenthub"))
    precondition(MigrationIdentityPolicy.accepts(source: "com.easyai.agenthub", destination: "com.easyai.agenthub"))
    precondition(!MigrationIdentityPolicy.accepts(source: "com.easyai.agenthub", destination: "com.easyai.codexmanage"))
    precondition(!MigrationIdentityPolicy.accepts(source: "com.other.app", destination: "com.easyai.agenthub"))
    precondition(!MigrationIdentityPolicy.accepts(source: "com.easyai.codexmanage.NotificationService", destination: "com.easyai.agenthub"))
    precondition(!MigrationIdentityPolicy.accepts(source: "", destination: "com.easyai.agenthub"))
    precondition(!MigrationIdentityPolicy.accepts(source: source, destination: nil))
    print("Native Agent Hub migration identity: authenticated legacy import, same-app restore and unrelated/reverse identity rejection passed")
    print("Native encrypted migration: round trip, no plaintext, wrong password and tamper rejection passed")
  }
}
