import AVFoundation
import CryptoKit
import Combine
import SwiftUI
import MobileCore

struct VoiceRecording: Sendable {
  let operationId: String
  let data: Data
  let sha256: String
  let durationMs: Int
}
struct VoiceTranscriptionReadiness: Decodable, Sendable {
  let hostId: String
  let available: Bool
  let reason: String?
  let maxBytes: Int
  let maxDurationMs: Int
}
struct VoiceTranscriptionRequest: Codable, Equatable, Sendable {
  let hostId: String
  let threadId: String?
  let draftId: String
  let operationId: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
  let durationMs: Int
  func encode(to encoder: Encoder) throws {
    var values = encoder.container(keyedBy: Keys.self)
    try values.encode(hostId, forKey: .hostId)
    if let threadId { try values.encode(threadId, forKey: .threadId) } else { try values.encodeNil(forKey: .threadId) }
    try values.encode(draftId, forKey: .draftId); try values.encode(operationId, forKey: .operationId)
    try values.encode(mediaType, forKey: .mediaType); try values.encode(sizeBytes, forKey: .sizeBytes)
    try values.encode(sha256, forKey: .sha256); try values.encode(durationMs, forKey: .durationMs)
  }
  enum Keys: String, CodingKey { case hostId, threadId, draftId, operationId, mediaType, sizeBytes, sha256, durationMs }
}
struct VoiceTranscriptionReceipt: Decodable, Sendable {
  let hostId: String; let threadId: String?; let draftId: String; let operationId: String
  let mediaType: String; let sizeBytes: Int; let sha256: String; let durationMs: Int
  let phase: String; let text: String?; let error: String?; let expiresAt: String
  var request: VoiceTranscriptionRequest { .init(hostId: hostId, threadId: threadId, draftId: draftId, operationId: operationId,
    mediaType: mediaType, sizeBytes: sizeBytes, sha256: sha256, durationMs: durationMs) }
  func matches(_ original: VoiceTranscriptionRequest) throws -> Bool {
    let encoder = JSONEncoder()
    guard let expected = String(data: try encoder.encode(original), encoding: .utf8),
      var value = try JSONSerialization.jsonObject(with: encoder.encode(request)) as? [String: Any] else { return false }
    value["phase"] = phase; value["text"] = text as Any? ?? NSNull(); value["error"] = error as Any? ?? NSNull(); value["expiresAt"] = expiresAt
    guard let receipt = String(data: try JSONSerialization.data(withJSONObject: value), encoding: .utf8) else { return false }
    return VoiceTranscriptionRules.shared.receiptMatches(expected: expected, receipt: receipt)
  }
}
enum VoiceTranscriptionPreference: String, CaseIterable {
  case desktop, local
  static var storageKey: String {
    ProcessInfo.processInfo.arguments.contains("-ui-testing") ? "testing-voice-transcription-default" : "voice-transcription-default"
  }
  static var current: Self { Self(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .desktop }
  var title: String { self == .desktop ? L10n.tr("电脑端") : L10n.tr("本地（手机）") }
}

struct VoiceTranscriptionProvider {
  let scopeKey: String
  // Reads a prepared route and obtains any required sharing consent; never performs I/O.
  let prepare: @MainActor () throws -> Bool
  let transcribe: @MainActor (VoiceRecording) async throws -> String
  static func local(scopeKey: String) -> Self {
    Self(scopeKey: scopeKey, prepare: { false }, transcribe: { _ in throw HostServiceError.unavailable })
  }
}

/// Private immutable uploads survive leaving the input or restarting the app.
/// Restoring only exposes a Retry button; it never submits audio automatically.
private enum PendingVoiceStore {
  struct Metadata: Codable {
    let target: String; let scopeKey: String; let originalText: String; let operationId: String
    let sha256: String; let durationMs: Int; let sizeBytes: Int; let createdAt: Date
    var locallyResolvedAt: Date?
    var discardedAt: Date?
  }
  static func directory() throws -> URL {
    let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
      .appendingPathComponent("voice-transcriptions", isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
      attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
    return root
  }
  static func save(_ recording: VoiceRecording, target: String, scopeKey: String, text: String) throws {
    let root = try directory()
    let metadata = root.appendingPathComponent(recording.operationId + ".json")
    if FileManager.default.fileExists(atPath: metadata.path) { return }
    let audio = root.appendingPathComponent(recording.operationId + ".wav")
    try recording.data.write(to: audio, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: audio.path)
    let record = Metadata(target: target, scopeKey: scopeKey, originalText: text, operationId: recording.operationId,
      sha256: recording.sha256, durationMs: recording.durationMs, sizeBytes: recording.data.count, createdAt: Date())
    try JSONEncoder().encode(record).write(to: metadata, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: metadata.path)
  }
  static func restore(target: String, scopeKey: String, text: String) throws -> (VoiceRecording, URL)? {
    let root = try directory()
    let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey])
    var candidates: [Metadata] = []
    for file in files where file.pathExtension == "json" {
      guard let record = try? JSONDecoder().decode(Metadata.self, from: Data(contentsOf: file)),
        UUID(uuidString: record.operationId)?.uuidString.lowercased() == record.operationId,
        file.lastPathComponent == record.operationId + ".json" else { continue }
      if Date().timeIntervalSince(record.createdAt) >= 24 * 3600 { remove(record.operationId); continue }
      if record.locallyResolvedAt == nil, record.discardedAt == nil,
        record.target == target && record.scopeKey == scopeKey && record.originalText == text { candidates.append(record) }
    }
    // Partial local saves contain no submitted operation. Remove old orphan audio only.
    for file in files where file.pathExtension == "wav" {
      let metadata = root.appendingPathComponent(file.deletingPathExtension().lastPathComponent + ".json")
      if !FileManager.default.fileExists(atPath: metadata.path),
        let modified = try file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
        Date().timeIntervalSince(modified) >= 24 * 3600 { try? FileManager.default.removeItem(at: file) }
    }
    guard let record = candidates.max(by: { $0.createdAt < $1.createdAt }) else { return nil }
    let audio = root.appendingPathComponent(record.operationId + ".wav")
    let data = try Data(contentsOf: audio)
    guard data.count == record.sizeBytes, data.count <= 12 * 1024 * 1024,
      SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == record.sha256,
      record.durationMs > 0, record.durationMs <= 300000 else { throw HostServiceError.message(L10n.tr("保存的原录音不完整，已停止转写")) }
    return (.init(operationId: record.operationId, data: data, sha256: record.sha256, durationMs: record.durationMs), audio)
  }
  static func remove(_ id: String) {
    guard let root = try? directory() else { return }
    for suffix in [".json", ".wav"] { try? FileManager.default.removeItem(at: root.appendingPathComponent(id + suffix)) }
  }
  static func discard(_ id: String) throws {
    let root = try directory(), metadata = root.appendingPathComponent(id + ".json")
    if FileManager.default.fileExists(atPath: metadata.path) {
      var record = try JSONDecoder().decode(Metadata.self, from: Data(contentsOf: metadata))
      // Persist the user's decision before deleting audio. A leftover file must
      // never become recoverable again, including after an app restart.
      record.discardedAt = Date()
      try JSONEncoder().encode(record).write(to: metadata, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
      try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: metadata.path)
    }
    let audio = root.appendingPathComponent(id + ".wav")
    if FileManager.default.fileExists(atPath: audio.path) { try FileManager.default.removeItem(at: audio) }
  }
  static func resolveLocally(_ id: String) {
    // Keep the original operation and audio until expiry, without offering it again.
    guard let root = try? directory() else { return }
    let url = root.appendingPathComponent(id + ".json")
    guard let data = try? Data(contentsOf: url), var record = try? JSONDecoder().decode(Metadata.self, from: data) else { return }
    record.locallyResolvedAt = Date()
    guard let updated = try? JSONEncoder().encode(record) else { return }
    try? updated.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }
}

/// Select a backend before capture. Submitted desktop operations keep their original receipt.
@MainActor
final class VoiceInputController: ObservableObject {
  enum Phase { case idle, requesting, listening, finishing, failed }
  @Published private(set) var phase: Phase = .idle
  @Published private(set) var transcript = ""
  @Published private(set) var levels: [CGFloat] = Array(repeating: 0, count: 9)
  @Published var error: String?
  @Published private(set) var diagnosticInfo = ""
  private(set) var targetKey = ""
  private(set) var originalText = ""
  private var generation = UUID()
  private var recorder: AVAudioRecorder?
  private var fileURL: URL?
  private var recording: VoiceRecording?
  private var provider: VoiceTranscriptionProvider?
  private var usesLocalRecognition = false
  private var usesLocalFallback = false
  private var usesSavedLocalRecognition = false
  private var localRecognition: LocalVoiceInputController?
  private var localObservation: AnyCancellable?
  private var setupTask: Task<Void, Never>?
  private var meterTask: Task<Void, Never>?
  private var finishTask: Task<Bool, Never>?
  private var onDraftChange: ((String, String) -> Bool)?
  private var interruptionObserver: NSObjectProtocol?
  private let audioSession = VoiceAudioSession.shared
  private var activeAudioSession: UUID?
  private var isFixture: Bool {
    let args = ProcessInfo.processInfo.arguments
    return args.contains("-ui-testing") && args.contains("-voice-input-fixture")
  }
  var isActive: Bool { phase != .idle }
  var canUseLocalTranscription: Bool {
    phase == .failed && !usesLocalRecognition && (recording != nil || simulatesTranscriptionFailure)
  }
  private var simulatesTranscriptionFailure: Bool {
    isFixture && ProcessInfo.processInfo.arguments.contains("-voice-transcription-failed")
  }
  var draftText: String {
    guard !transcript.isEmpty else { return originalText }
    let separator = originalText.isEmpty || originalText.last?.isWhitespace == true ? "" : "\n"
    return originalText + separator + transcript
  }
  var status: String {
    switch phase {
    case .requesting: return usesSavedLocalRecognition ? L10n.tr("准备本地转写…") : usesLocalRecognition ? L10n.tr("准备本地听写…") : L10n.tr("准备录音…")
    case .listening:
      if usesLocalFallback { return L10n.tr("电脑转写暂不可用，正在本地听写") }
      return usesLocalRecognition ? L10n.tr("正在本地听写") : L10n.tr("正在录音，结束后由电脑转写")
    case .finishing: return usesSavedLocalRecognition ? L10n.tr("正在本地转写…") : usesLocalRecognition ? L10n.tr("正在完成本地听写…") : L10n.tr("电脑正在转写…")
    case .failed: return error ?? (usesLocalRecognition ? L10n.tr("本地听写已结束，点击完成以填入草稿") : L10n.tr("录音已保留，点击重试查询转写"))
    case .idle: return ""
    }
  }
  func start(target: String, text: String, provider: VoiceTranscriptionProvider, restorePending: Bool = true,
    onChange: @escaping (String, String) -> Bool) {
    guard !isActive else { return }
    targetKey = target; originalText = text; self.provider = provider; onDraftChange = onChange
    transcript = ""; error = nil; diagnosticInfo = ""; recording = nil
    usesLocalRecognition = VoiceTranscriptionPreference.current == .local
    usesLocalFallback = false
    usesSavedLocalRecognition = false
    levels = Array(repeating: 0, count: 9)
    generation = UUID(); let id = generation
    phase = .requesting
    setupTask = Task {
      do {
        if !usesLocalRecognition, restorePending, let (saved, audio) = try PendingVoiceStore.restore(target: target, scopeKey: provider.scopeKey, text: text) {
          guard generation == id, !Task.isCancelled else { return }
          recording = saved; fileURL = audio; phase = .failed
          error = L10n.tr("已恢复此草稿的原录音。点击重试查询同一转写；不会自动重新提交。")
          return
        }
        usesLocalRecognition = !(try provider.prepare())
        usesLocalFallback = usesLocalRecognition && VoiceTranscriptionPreference.current == .desktop
        guard generation == id, !Task.isCancelled else { return }
        if isFixture {
          do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
          guard generation == id else { return }
          if ProcessInfo.processInfo.arguments.contains("-voice-input-denied") {
            failSetup(id: id, message: L10n.tr("麦克风权限未开启，请在系统设置中允许访问麦克风。"))
            return
          }
          diagnosticInfo = usesLocalRecognition ? "backend=LocalSpeech fixture=true" : "backend=HostTranscription fixture=true"
          phase = .listening
          levels = [0.3, 0.55, 0.8, 0.4, 0.9, 0.65, 0.35, 0.6, 0.25]
          return
        }

        if usesLocalRecognition {
          beginLocalRecognition(id: id)
          return
        }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ui-testing"),
          ProcessInfo.processInfo.arguments.contains("-voice-file-fixture"),
          let address = ProcessInfo.processInfo.environment["VOICE_FILE_FIXTURE_URL"], let source = URL(string: address) {
          let (data, response) = try await URLSession.shared.data(from: source)
          guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 12 * 1024 * 1024 else { throw HostServiceError.invalidResponse }
          guard generation == id, !Task.isCancelled else { return }
          let url = FileManager.default.temporaryDirectory.appendingPathComponent(id.uuidString.lowercased() + ".wav")
          try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
          fileURL = url; phase = .listening
          diagnosticInfo = "backend=HostTranscription source=fixture-file microphone=false"
          return
        }
        #endif
        let granted = await withCheckedContinuation { continuation in
          AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard generation == id, !Task.isCancelled else { return }
        guard granted else { throw HostServiceError.message(L10n.tr("麦克风权限未开启，请在系统设置中允许访问麦克风。")) }
        try await audioSession.activate(id: id)
        guard generation == id, !Task.isCancelled else { audioSession.deactivate(id: id); return }
        activeAudioSession = id
        guard !AVAudioApplication.shared.isInputMuted else { throw HostServiceError.message(L10n.tr("麦克风已被系统静音。")) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("voice-recordings", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
          attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let url = directory.appendingPathComponent(id.uuidString.lowercased() + ".wav")
        fileURL = url
        // Preparing an AudioQueue can block in the device driver. Keep that work
        // off Main and abandon a late recorder after timeout/cancel.
        let prepared = try await audioSession.prepareRecorder(url: url, id: id)
        guard generation == id, !Task.isCancelled else { prepared.recorder.stop(); return }
        let recorder = prepared.recorder
        self.recorder = recorder; phase = .listening
        if ProcessInfo.processInfo.arguments.contains("-ui-testing") { diagnosticInfo = "backend=HostTranscription format=16000/1 file=wav" }
        interruptionObserver = NotificationCenter.default.addObserver(
          forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            guard (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
            Task { @MainActor [weak self] in _ = await self?.finish() }
          }
        meterTask = Task {
          while generation == id, phase == .listening {
            do { try await Task.sleep(for: .milliseconds(70)) } catch { return }
            guard generation == id, phase == .listening else { return }
            recorder.updateMeters()
            levels.removeFirst(); levels.append(CGFloat(max(0, min(1, (recorder.averagePower(forChannel: 0) + 55) / 45))))
            if !recorder.isRecording { _ = await finish(); return }
          }
        }
      } catch is CancellationError {
        guard generation == id else { return }
        reset(discard: true)
      } catch {
        guard generation == id, !Task.isCancelled else { return }
        failSetup(id: id, message: error.localizedDescription)
      }
    }
  }
  func transcribeLocally() async -> Bool {
    guard canUseLocalTranscription else { return false }
    return await finish(usingLocalRecording: true)
  }
  func finish(usingLocalRecording: Bool = false) async -> Bool {
    if let finishTask { return await finishTask.value }
    guard phase == .listening || phase == .failed else { return phase == .idle && error == nil }
    let id = generation
    usesSavedLocalRecognition = usingLocalRecording
    phase = usingLocalRecording ? .requesting : .finishing; error = nil
    let task = Task<Bool, Never> {
      do {
        let text: String
        if isFixture {
          #if DEBUG
          if simulatesTranscriptionFailure, recording == nil,
            let encoded = ProcessInfo.processInfo.environment["VOICE_PENDING_FIXTURE_WAV"], let data = Data(base64Encoded: encoded) {
            let audio = FileManager.default.temporaryDirectory.appendingPathComponent(id.uuidString.lowercased() + ".wav")
            try data.write(to: audio, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            fileURL = audio
            let saved = VoiceRecording(operationId: id.uuidString.lowercased(), data: data,
              sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), durationMs: 1000)
            guard let provider else { throw HostServiceError.unavailable }
            recording = saved
            try PendingVoiceStore.save(saved, target: targetKey, scopeKey: provider.scopeKey, text: originalText)
          }
          #endif
          let delay = ProcessInfo.processInfo.environment["VOICE_PENDING_FIXTURE_WAV"] == nil ? 300 : 8000
          try await Task.sleep(for: .milliseconds(delay))
          if simulatesTranscriptionFailure && !usingLocalRecording {
            throw HostServiceError.message(L10n.tr("电脑转写暂不可用"))
          }
          text = "语音输入测试"
        } else if usingLocalRecording {
          guard let recording, let fileURL, try Data(contentsOf: fileURL) == recording.data else {
            throw HostServiceError.message(L10n.tr("保存的原录音不完整，已停止转写"))
          }
          text = try await LocalRecordingTranscriber().transcribe(fileURL) {
            if generation == id { phase = .finishing }
          }
        } else if usesLocalRecognition {
          guard let localRecognition else { throw HostServiceError.unavailable }
          guard await localRecognition.finish() else {
            throw HostServiceError.message(localRecognition.error ?? L10n.tr("本地听写未完成，请重新录音。"))
          }
          text = localRecognition.transcript
          guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw HostServiceError.message(L10n.tr("没有识别到语音，请重新录音。"))
          }
        } else {
          if recording == nil {
            guard let fileURL else { throw HostServiceError.message(L10n.tr("原录音不存在")) }
            stopCapture()
            let audio = try AVAudioFile(forReading: fileURL)
            guard audio.length > 0 else {
              failSetup(id: id, message: L10n.tr("没有采集到录音，请重录。使用手机镜像时，请先退出镜像。"))
              return false
            }
            let duration = max(1, Int((Double(audio.length) / audio.processingFormat.sampleRate * 1000).rounded()))
            let data = try Data(contentsOf: fileURL)
            guard data.count <= 12 * 1024 * 1024, duration <= 300000 else { throw HostServiceError.message(L10n.tr("录音超过五分钟或大小限制")) }
            recording = VoiceRecording(operationId: id.uuidString.lowercased(), data: data,
              sha256: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), durationMs: duration)
          }
          guard let provider, let recording else { throw HostServiceError.unavailable }
          try PendingVoiceStore.save(recording, target: targetKey, scopeKey: provider.scopeKey, text: originalText)
          text = try await provider.transcribe(recording)
        }
        guard generation == id, !Task.isCancelled else { return false }
        transcript = text
        guard onDraftChange?(targetKey, draftText) == true else {
          phase = .failed; error = L10n.tr("草稿或目标已变化，转写已保留，未覆盖当前输入。")
          return false
        }
        if let recording {
          if usingLocalRecording { PendingVoiceStore.resolveLocally(recording.operationId) }
          else { PendingVoiceStore.remove(recording.operationId) }
        }
        reset(discard: true, preservePending: usingLocalRecording)
        return true
      } catch {
        guard generation == id, !Task.isCancelled else { return false }
        stopCapture()
        phase = .failed
        self.error = error.localizedDescription + (usesLocalRecognition ? L10n.tr(" 原草稿已保留。") : usingLocalRecording ? L10n.tr(" 录音和原草稿已保留，可再次选择转写方式。") : L10n.tr(" 录音已保留；重试只查询或补齐原操作。"))
        return false
      }
    }
    finishTask = task
    let result = await task.value
    if generation == id { finishTask = nil }
    return result
  }
  func recordAgain(text: String) {
    guard phase == .failed, let provider, let onChange = onDraftChange else { return }
    let target = targetKey
    guard discard() else { return }
    start(target: target, text: text, provider: provider, restorePending: false, onChange: onChange)
  }
  func cancel() {
    generation = UUID()
    setupTask?.cancel(); finishTask?.cancel()
    reset(discard: true, preservePending: recording != nil)
  }
  @discardableResult func discard() -> Bool {
    generation = UUID()
    setupTask?.cancel(); finishTask?.cancel(); finishTask = nil
    stopCapture()
    do {
      if let recording { try PendingVoiceStore.discard(recording.operationId) }
    } catch {
      phase = .failed
      self.error = L10n.tr("无法丢弃录音，请重试。")
      return false
    }
    reset(discard: true)
    error = nil
    return true
  }
  func suspend() {
    guard phase != .idle else { return }
    guard phase != .requesting else { cancel(); return }
    generation = UUID()
    setupTask?.cancel(); finishTask?.cancel(); finishTask = nil
    stopCapture()
    if usesLocalRecognition {
      localRecognition?.cancel()
      phase = .failed
      error = L10n.tr("本地听写已暂停，已识别的文字已保留，点击完成或重新录音。")
      return
    }
    phase = .failed
    error = L10n.tr("录音已暂停并保留，点击重试查询或完成原转写。")
  }
  private func beginLocalRecognition(id: UUID) {
    let local = LocalVoiceInputController()
    localRecognition = local
    localObservation = local.objectWillChange.sink { [weak self, weak local] in
      Task { @MainActor in
        guard let self, let local, self.generation == id else { return }
        self.levels = local.levels
        self.diagnosticInfo = "backend=LocalSpeech " + local.diagnosticInfo
        switch local.phase {
        case .requesting: self.phase = .requesting
        case .listening: self.phase = .listening
        case .finishing: self.phase = .finishing
        case .idle: break
        }
      }
    }
    local.onComplete = { [weak self] message in
      guard let self, self.generation == id, self.phase != .finishing else { return }
      self.phase = .failed
      self.error = message
    }
    local.start(target: targetKey, text: "") { [weak self] _, text in
      guard let self, self.generation == id else { return }
      self.transcript = text
    }
  }
  private func failSetup(id: UUID, message: String) {
    guard generation == id else { return }
    reset(discard: true); error = message
  }
  private func stopCapture() {
    meterTask?.cancel(); meterTask = nil
    recorder?.stop(); recorder = nil
    if let observer = interruptionObserver { NotificationCenter.default.removeObserver(observer); interruptionObserver = nil }
    if let id = activeAudioSession { audioSession.deactivate(id: id); activeAudioSession = nil }
  }
  private func reset(discard: Bool, preservePending: Bool = false) {
    stopCapture()
    localObservation = nil
    localRecognition?.cancel(); localRecognition = nil
    phase = .idle; onDraftChange = nil; provider = nil; finishTask = nil
    if discard {
      if let fileURL, !(preservePending && fileURL.deletingLastPathComponent().lastPathComponent == "voice-transcriptions") {
        try? FileManager.default.removeItem(at: fileURL)
      }
      fileURL = nil; recording = nil
    }
    levels = Array(repeating: 0, count: 9)
  }
}

final class VoiceAudioSession: @unchecked Sendable {
  static let shared = VoiceAudioSession()
  private init() {}
  private let queue = DispatchQueue(label: "voice-input.audio-session")
  private var owner: UUID?
  private let requestLock = NSLock()
  private var captureRequest: (UUID, RecorderRequest)?

  final class PreparedRecorder: @unchecked Sendable {
    let recorder: AVAudioRecorder
    init(_ recorder: AVAudioRecorder) { self.recorder = recorder }
  }
  private final class RecorderRequest: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<PreparedRecorder, Error>?
    init(_ continuation: CheckedContinuation<PreparedRecorder, Error>) { self.continuation = continuation }
    var pending: Bool { lock.withLock { continuation != nil } }
    @discardableResult func resolve(_ result: Result<PreparedRecorder, Error>) -> Bool {
      let next = lock.withLock { let next = continuation; continuation = nil; return next }
      guard let next else { return false }; next.resume(with: result); return true
    }
  }

  func prepareRecorder(url: URL, id: UUID) async throws -> PreparedRecorder {
    try await withCheckedThrowingContinuation { continuation in
      let request = RecorderRequest(continuation)
      requestLock.withLock { captureRequest = (id, request) }
      DispatchQueue.global().asyncAfter(deadline: .now() + 10) {
        request.resolve(.failure(HostServiceError.message(L10n.tr("录音设备准备超时，请检查麦克风后重试。原草稿已保留。"))))
      }
      queue.async { [self] in
        guard owner == id, request.pending else { request.resolve(.failure(CancellationError())); return }
        do {
          let recorder = try AVAudioRecorder(url: url, settings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
          ])
          recorder.isMeteringEnabled = true
          guard request.pending, recorder.prepareToRecord(), request.pending,
            recorder.record(forDuration: 300) else {
            recorder.stop(); try? FileManager.default.removeItem(at: url)
            request.resolve(.failure(HostServiceError.message(L10n.tr("无法开始录音")))); return
          }
          if !request.resolve(.success(PreparedRecorder(recorder))) {
            recorder.stop(); try? FileManager.default.removeItem(at: url)
          }
        } catch { request.resolve(.failure(error)) }
      }
    }
  }

  func activate(id: UUID) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      queue.async { [self] in
        do {
          let session = AVAudioSession.sharedInstance()
          try session.setCategory(.record, mode: .measurement, options: [])
          try session.setActive(true)
          owner = id
          continuation.resume()
        } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }

  func deactivate(id: UUID) {
    let request = requestLock.withLock { () -> RecorderRequest? in
      guard captureRequest?.0 == id else { return nil }
      let request = captureRequest?.1; captureRequest = nil; return request
    }
    request?.resolve(.failure(CancellationError()))
    queue.async { [self] in
      guard owner == id else { return }
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
      owner = nil
    }
  }
}

struct VoiceInputControls: View {
  @ObservedObject var voice: VoiceInputController
  let text: String
  var questionId: String? = nil

  private func identifier(_ composer: String, question: String) -> String {
    questionId.map { question + $0 } ?? composer
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack(spacing: 3) {
        IconButton(symbol: "xmark", label: voice.phase == .listening || voice.phase == .requesting ? L10n.tr("取消录音") : L10n.tr("丢弃录音"),
          id: identifier("cancel-dictation", question: "question-cancel-dictation-")) {
          voice.discard()
        }.background(Color.primary.opacity(0.06), in: Circle())
        Group {
          if voice.phase == .finishing {
            VoiceTranscriptionActivity(status: voice.status)
              .accessibilityElement(children: .ignore)
          } else if voice.phase == .listening {
            Button {
              Task { _ = await voice.finish() }
            } label: {
              HStack(spacing: 10) {
                VoiceWaveform(levels: voice.levels).frame(maxWidth: .infinity).accessibilityHidden(true)
                Text(L10n.tr("轻触停止录音")).font(.caption).foregroundStyle(Palette.secondary)
                  .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).layoutPriority(1)
                VoiceWaveform(levels: voice.levels).frame(maxWidth: .infinity)
                  .scaleEffect(x: -1, y: 1).accessibilityHidden(true)
              }.frame(minHeight: 44).contentShape(Rectangle())
            }.buttonStyle(.plain)
          } else {
            VoiceWaveform(levels: voice.levels)
              .accessibilityElement(children: .ignore)
          }
        }.frame(maxWidth: .infinity, minHeight: 44)
          .accessibilityLabel(voice.phase == .listening ? L10n.tr("轻触停止录音") : voice.status)
          .accessibilityHint(voice.phase == .listening ? voice.status : "")
          .accessibilityIdentifier(identifier("dictation-waveform", question: "question-dictation-"))
          .accessibilityValue(voice.diagnosticInfo)
        Button {
          Task { _ = await voice.finish() }
        } label: {
          Group {
            if voice.phase == .finishing {
              ProgressView().tint(.primary)
            } else {
              Image(systemName: voice.phase == .failed ? "arrow.clockwise" : "stop.fill")
                .font(.system(size: 17))
            }
          }.frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.plain)
          .accessibilityLabel(voice.phase == .finishing ? voice.status : voice.phase == .failed ? L10n.tr("重试转写") : L10n.tr("结束录音并转写"))
          .accessibilityIdentifier(identifier("stop-dictation", question: "question-voice-"))
          .background(Color.primary.opacity(0.06), in: Circle())
          .disabled(voice.phase != .listening && voice.phase != .failed)
      }
      if voice.phase != .finishing {
        Text(voice.status).font(.caption).foregroundStyle(Palette.secondary).padding(.horizontal, 14)
      }
      if voice.phase == .failed {
        HStack(spacing: 16) {
          if voice.canUseLocalTranscription {
            Button(L10n.tr("使用本地转写")) { Task { _ = await voice.transcribeLocally() } }
              .accessibilityIdentifier(identifier("voice-local-transcription-inline", question: "question-local-transcription-"))
          }
          Button(L10n.tr("重新录音")) { voice.recordAgain(text: text) }
            .accessibilityIdentifier(identifier("voice-record-again", question: "question-record-again-"))
        }.frame(minHeight: 44).padding(.horizontal, 14)
      }
    }
  }
}

struct VoiceTranscriptionFailureActions: View {
  @ObservedObject var voice: VoiceInputController

  var body: some View {
    Button(L10n.tr("重试转写")) { Task { _ = await voice.finish() } }
      .accessibilityIdentifier("voice-retry-transcription")
    Button(L10n.tr("使用本地转写")) { Task { _ = await voice.transcribeLocally() } }
      .accessibilityIdentifier("voice-local-transcription")
  }
}

struct VoiceTranscriptionActivity: View {
  let status: String

  var body: some View {
    HStack(spacing: 10) {
      VoiceTranscriptionPulse()
      Text(status).font(.caption).foregroundStyle(Palette.secondary)
        .multilineTextAlignment(.center).layoutPriority(1)
      VoiceTranscriptionPulse().scaleEffect(x: -1, y: 1)
    }
  }
}

private struct VoiceTranscriptionPulse: View {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || scenePhase != .active)) { timeline in
      Canvas { context, size in
        // A looping processing pulse, independent of microphone levels or progress.
        let phase = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate * .pi * 2 / 1.2
        let count = max(1, min(12, Int(size.width / 6)))
        let spacing = min(6, size.width / CGFloat(count))
        for index in 0..<count {
          let wave = (sin(phase - Double(index) * 0.6) + 1) / 2
          let pulse = wave * wave
          let height = 3 + 12 * pulse
          let x = size.width / 2 + (CGFloat(index) - CGFloat(count - 1) / 2) * spacing
          let rect = CGRect(x: x - 1.5, y: (size.height - height) / 2, width: 3, height: height)
          context.fill(Path(roundedRect: rect, cornerRadius: 1.5),
            with: .color(Palette.secondary.opacity(0.3 + 0.5 * pulse)))
        }
      }
    }.frame(maxWidth: .infinity).frame(height: 20).clipped().accessibilityHidden(true)
  }
}

struct VoiceWaveform: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let levels: [CGFloat]
  var body: some View {
    Canvas { context, size in
      let middle = size.height / 2
      let count = max(1, Int(size.width / 8))
      let center = count / 2
      for index in 0..<count {
        let x = (CGFloat(index) + 0.5) * size.width / CGFloat(count)
        let sample = index - center + levels.count / 2
        let isBar = levels.indices.contains(sample)
        let level = isBar ? levels[sample] : 0
        let height: CGFloat = isBar ? 4 + level * 32 : 3
        let rect = CGRect(x: x - 1.5, y: middle - height / 2, width: 3, height: height)
        context.fill(
          Path(roundedRect: rect, cornerRadius: 1.5),
          with: .color(.secondary.opacity(isBar ? 0.85 : 0.4)))
      }
    }.frame(height: 44)
  }
}
