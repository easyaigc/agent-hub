import AVFoundation
import Foundation

struct TaskNotificationSound: Codable, Identifiable, Sendable {
  var id: String
  var title: String
  var imported: Bool
}
enum TaskNotificationSoundLibrary {
  static var directory: URL { TaskNotificationShared.soundDirectory }
  static var sounds: [TaskNotificationSound] {
    [TaskNotificationSound(id: "default", title: L10n.tr("系统默认"), imported: false),
     TaskNotificationSound(id: "soft.caf", title: L10n.tr("轻柔提示"), imported: false),
     TaskNotificationSound(id: "chime.caf", title: L10n.tr("清亮铃声"), imported: false),
     TaskNotificationSound(id: "attention.caf", title: L10n.tr("醒目提醒"), imported: false)] + (TaskNotificationShared.read([TaskNotificationSound].self, name: "sounds.json") ?? [])
  }
  static func prepare() throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    for (name, frequencies) in [("soft.caf", [660.0]), ("chime.caf", [660.0, 880.0]), ("attention.caf", [880.0, 660.0, 880.0])] {
      let url = directory.appendingPathComponent(name); if FileManager.default.fileExists(atPath: url.path) { continue }
      let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 22050, channels: 1, interleaved: true)!
      let frames = AVAudioFrameCount(22050 * Double(frequencies.count) * 0.24)
      guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames), let samples = buffer.int16ChannelData?[0] else { throw NSError(domain: "TaskNotificationSound", code: 1) }
      buffer.frameLength = frames
      for index in 0..<Int(frames) {
        let time = Double(index) / 22050, part = min(frequencies.count - 1, Int(time / 0.24)), phase = time.truncatingRemainder(dividingBy: 0.24)
        let envelope = min(1, phase / 0.01) * exp(-phase * 12)
        samples[index] = Int16(sin(time * frequencies[part] * 2 * .pi) * envelope * 10000)
      }
      let file = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: .pcmFormatInt16, interleaved: true); try file.write(from: buffer)
      try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    }
  }
  static func importFile(_ source: URL) throws -> TaskNotificationSound {
    let accessed = source.startAccessingSecurityScopedResource(); defer { if accessed { source.stopAccessingSecurityScopedResource() } }
    guard ["wav", "aiff", "aif", "caf", "m4a", "mp3"].contains(source.pathExtension.lowercased()) else { throw error(L10n.tr("请选择 WAV、AIFF、CAF、M4A 或 MP3 音频。")) }
    let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
    guard size > 0, size <= 10 * 1024 * 1024 else { throw error(L10n.tr("铃声文件最多 10 MB。")) }
    let input: AVAudioFile
    do { input = try AVAudioFile(forReading: source) } catch { throw Self.error(L10n.tr("音频已损坏或无法解码，请选择其他音频。")) }
    let duration = Double(input.length) / input.processingFormat.sampleRate
    guard duration.isFinite, duration > 0, duration <= 29 else { throw error(L10n.tr("请选择不超过 29 秒的短铃声。")) }
    let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 22050, channels: 1, interleaved: true)!
    guard let converter = AVAudioConverter(from: input.processingFormat, to: format),
      let sourceBuffer = AVAudioPCMBuffer(pcmFormat: input.processingFormat, frameCapacity: 8192),
      let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8192) else { throw error(L10n.tr("无法转换这段音频。")) }
    try prepare()
    let item = TaskNotificationSound(id: UUID().uuidString.lowercased() + ".caf", title: String(source.deletingPathExtension().lastPathComponent.prefix(80)), imported: true)
    let url = directory.appendingPathComponent(item.id)
    var completed = false
    defer { if !completed { try? FileManager.default.removeItem(at: url) } }
    let file = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: .pcmFormatInt16, interleaved: true)
    var total: AVAudioFramePosition = 0, readError: Error?
    while true {
      var conversionError: NSError?
      let result = converter.convert(to: output, error: &conversionError) { count, status in
        do {
          let remaining = input.length - input.framePosition
          if remaining <= 0 { status.pointee = .endOfStream; return nil }
          try input.read(into: sourceBuffer, frameCount: min(min(AVAudioFrameCount(count), sourceBuffer.frameCapacity), AVAudioFrameCount(remaining)))
          if sourceBuffer.frameLength == 0 { status.pointee = .endOfStream; return nil }
          status.pointee = .haveData; return sourceBuffer
        } catch { readError = error; status.pointee = .endOfStream; return nil }
      }
      guard result != .error, conversionError == nil, readError == nil else { throw error(L10n.tr("音频已损坏或无法转换。")) }
      total += AVAudioFramePosition(output.frameLength)
      guard total <= 29 * 22050 else { throw error(L10n.tr("请选择不超过 29 秒的短铃声。")) }
      if output.frameLength > 0 { try file.write(from: output) }
      if result == .endOfStream { break }
      if output.frameLength == 0 { throw error(L10n.tr("音频已损坏或无法转换。")) }
    }
    guard total > 0 else { throw error(L10n.tr("音频没有可播放的内容。")) }
    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    completed = true
    return item
  }
  static func validateArchiveSound(_ data: Data) throws {
    guard !data.isEmpty, data.count <= 10 * 1024 * 1024 else { throw error(L10n.tr("迁移铃声已损坏。")) }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".caf")
    defer { try? FileManager.default.removeItem(at: url) }
    try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    let file = try AVAudioFile(forReading: url)
    let duration = Double(file.length) / file.fileFormat.sampleRate
    guard file.fileFormat.commonFormat == .pcmFormatInt16, file.fileFormat.channelCount == 1,
      duration.isFinite, duration > 0, duration <= 29 else { throw error(L10n.tr("迁移铃声格式无效。")) }
  }
  static func removeImported(_ item: TaskNotificationSound) throws {
    guard item.imported, TaskNotificationValidation.sound(item.id), !["soft.caf", "chime.caf", "attention.caf"].contains(item.id) else { throw error(L10n.tr("无法删除内置铃声。")) }
    let prefs = TaskNotificationShared.preferences
    guard prefs.sound != item.id, !prefs.categorySounds.values.contains(item.id) else { throw error(L10n.tr("请先保存默认铃声选择。")) }
    try TaskNotificationShared.write(sounds.filter { $0.imported && $0.id != item.id }, name: "sounds.json")
    let url = directory.appendingPathComponent(item.id)
    if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
  }
  static func error(_ text: String) -> NSError { NSError(domain: "TaskNotificationSound", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
}
