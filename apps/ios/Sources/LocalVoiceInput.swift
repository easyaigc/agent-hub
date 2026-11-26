import AVFoundation
import Speech
import SwiftUI

/// Opt-in scalar observations of the existing microphone tap, independent of fixtures.
/// No audio buffers, transcript, target or credentials are retained.
@MainActor
private final class LocalVoiceCaptureProbe {
  private let id: UUID
  private let url: URL
  private var events = 0
  private var buffers = 0
  private var frames: UInt64 = 0
  private var meterBuffers = 0
  private var meteredFrames: UInt64 = 0
  private var maximumRMS: Float = 0
  private var lastSample = -Double.infinity

  static func make(id: UUID) -> LocalVoiceCaptureProbe? {
    guard ProcessInfo.processInfo.arguments.contains("-voice-capture-diagnostics") else { return nil }
    return try? LocalVoiceCaptureProbe(id: id)
  }

  private init(id: UUID) throws {
    self.id = id
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("local-voice-capture-diagnostics", isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
      attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
    url = root.appendingPathComponent(id.uuidString.lowercased() + ".jsonl")
    try Data().write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }

  func buffer(frames count: AVAudioFrameCount, rate: Double, channels: AVAudioChannelCount, rms: Float?) {
    buffers += 1
    frames += UInt64(count)
    if let rms {
      meterBuffers += 1
      meteredFrames += UInt64(count)
      maximumRMS = max(maximumRMS, rms)
    }
    let now = ProcessInfo.processInfo.systemUptime
    guard now - lastSample >= 1 else { return }
    lastSample = now
    sample("input-tap", details: ["bufferFrames": count, "tapSampleRate": rate,
      "tapChannels": channels, "sampledRMS": rms as Any? ?? NSNull()])
  }

  func sample(_ event: String, details: [String: Any] = [:]) {
    guard events < 340 else { return }
    events += 1
    let session = AVAudioSession.sharedInstance()
    var value: [String: Any] = [
      "operationId": id.uuidString.lowercased(), "event": event,
      "timestamp": Date().timeIntervalSince1970, "uptime": ProcessInfo.processInfo.systemUptime,
      "category": session.category.rawValue, "mode": session.mode.rawValue,
      "sampleRate": session.sampleRate, "inputChannels": session.inputNumberOfChannels,
      "inputAvailable": session.isInputAvailable, "inputMuted": AVAudioApplication.shared.isInputMuted,
      "inputPorts": session.currentRoute.inputs.map { $0.portType.rawValue },
      "outputPorts": session.currentRoute.outputs.map { $0.portType.rawValue },
      "tapBuffers": buffers, "tapFrames": frames,
      "meterBuffers": meterBuffers, "meteredFrames": meteredFrames, "maximumSampledRMS": maximumRMS,
    ]
    value.merge(details) { _, new in new }
    guard JSONSerialization.isValidJSONObject(value),
      var data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
      let handle = try? FileHandle(forWritingTo: url) else { return }
    defer { try? handle.close() }
    data.append(0x0A)
    _ = try? handle.seekToEnd()
    try? handle.write(contentsOf: data)
  }
}

/// Recognizes an existing recording without opening the microphone again.
@MainActor
final class LocalRecordingTranscriber {
  private var recognitionTask: SFSpeechRecognitionTask?
  private var work: Task<Void, Never>?
  private var timeout: Task<Void, Never>?
  private var continuation: CheckedContinuation<String, Error>?

  func transcribe(_ url: URL, onReady: () -> Void) async throws -> String {
    let permission = await withCheckedContinuation { continuation in
      SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
    }
    try Task.checkCancellation()
    guard permission == .authorized else {
      throw HostServiceError.message(L10n.tr("语音识别权限未开启，请在系统设置中允许语音识别。"))
    }
    onReady()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        self.continuation = continuation
        work = Task {
          do {
            try Task.checkCancellation()
            let locale = Locale(identifier: Locale.preferredLanguages.first ?? "zh-CN")
            if #available(iOS 26, *),
              let supported = await DictationTranscriber.supportedLocale(equivalentTo: locale) {
              try Task.checkCancellation()
              complete(.success(try await recognizeFile(url, locale: supported)))
            } else {
              try Task.checkCancellation()
              guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
                throw HostServiceError.message(L10n.tr("当前语言的语音识别暂不可用，请稍后重试。"))
              }
              let request = SFSpeechURLRecognitionRequest(url: url)
              request.shouldReportPartialResults = false
              request.taskHint = .dictation
              request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
              recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let text = result?.isFinal == true ? result?.bestTranscription.formattedString : nil
                Task { @MainActor [weak self] in
                  if let text { self?.complete(.success(text)) }
                  else if let error { self?.complete(.failure(error)) }
                }
              }
            }
          } catch { complete(.failure(error)) }
        }
        timeout = Task {
          do { try await Task.sleep(for: .seconds(120)) } catch { return }
          complete(.failure(HostServiceError.message(L10n.tr("本地转写超时，录音已保留，请重试。"))))
        }
      }
    } onCancel: {
      Task { @MainActor in self.complete(.failure(CancellationError())) }
    }
  }

  @available(iOS 26, *)
  private func recognizeFile(_ url: URL, locale: Locale) async throws -> String {
    let transcriber = DictationTranscriber(locale: locale, preset: .progressiveLongDictation)
    if let installation = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
      try await installation.downloadAndInstall()
    }
    try Task.checkCancellation()
    let analyzer = SpeechAnalyzer(modules: [transcriber])
    let results = Task {
      var committed = ""
      var latest = ""
      for try await result in transcriber.results {
        try Task.checkCancellation()
        latest = committed + String(result.text.characters)
        if result.isFinal { committed = latest }
      }
      return latest
    }
    return try await withTaskCancellationHandler {
      do {
        let file = try AVAudioFile(forReading: url)
        try await analyzer.start(inputAudioFile: file, finishAfterFile: true)
        let text = try await results.value
        try Task.checkCancellation()
        return text
      } catch {
        results.cancel()
        await analyzer.cancelAndFinishNow()
        throw error
      }
    } onCancel: {
      results.cancel()
      Task { await analyzer.cancelAndFinishNow() }
    }
  }

  private func complete(_ result: Result<String, Error>) {
    guard let continuation else { return }
    self.continuation = nil
    timeout?.cancel(); timeout = nil
    work?.cancel(); work = nil
    recognitionTask?.cancel(); recognitionTask = nil
    continuation.resume(with: result.flatMap { text in
      text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        ? .failure(HostServiceError.message(L10n.tr("没有识别到语音，请重新录音。"))) : .success(text)
    })
  }
}

@MainActor
final class LocalVoiceInputController: ObservableObject {
  enum Phase { case idle, requesting, listening, finishing }
  @Published private(set) var phase: Phase = .idle
  @Published private(set) var transcript = ""
  @Published private(set) var levels: [CGFloat] = Array(repeating: 0, count: 9)
  @Published var error: String?
  // Only exposed to explicit UI acceptance runs, never shown in normal UI.
  @Published private(set) var diagnosticInfo = ""
  private var diagnosticPrefix = ""
  private var maximumRMS: Float = 0
  private var meterEvents = 0
  private var lastSignalAt = Date.distantFuture
  private var captureProbe: LocalVoiceCaptureProbe?
  private(set) var targetKey = ""
  private(set) var originalText = ""
  private var generation = UUID()
  private var engine: AVAudioEngine?
  private var request: SFSpeechAudioBufferRecognitionRequest?
  private var recognitionTask: SFSpeechRecognitionTask?
  private var modernRecognition: (any VoiceRecognitionSession)?
  private var setupTask: Task<Void, Never>?
  private var finishTimeout: Task<Void, Never>?
  private var finishWaiters: [CheckedContinuation<Bool, Never>] = []
  var onComplete: ((String?) -> Void)?
  private var onDraftChange: ((String, String) -> Void)?
  private var interruptionObserver: NSObjectProtocol?
  private var tapInstalled = false
  private let audioSession = VoiceAudioSession.shared
  private var activeAudioSession: UUID?
  var isActive: Bool { phase != .idle }
  var draftText: String {
    guard !transcript.isEmpty else { return originalText }
    let separator = originalText.isEmpty || originalText.last?.isWhitespace == true ? "" : "\n"
    return originalText + separator + transcript
  }
  var status: String {
    switch phase {
    case .requesting: return L10n.tr("准备听写…")
    case .listening: return L10n.tr("正在听写")
    case .finishing: return L10n.tr("正在完成听写…")
    case .idle: return ""
    }
  }

  func start(target: String, text: String, onChange: @escaping (String, String) -> Void) {
    guard !isActive else { return }
    targetKey = target
    originalText = text
    onDraftChange = onChange
    transcript = ""
    levels = Array(repeating: 0, count: 9)
    error = nil
    diagnosticInfo = ""
    maximumRMS = 0
    meterEvents = 0
    lastSignalAt = .distantFuture
    generation = UUID()
    let id = generation
    captureProbe = LocalVoiceCaptureProbe.make(id: id)
    captureProbe?.sample("start-requested")
    phase = .requesting
    setupTask = Task {
      let speechPermission = await withCheckedContinuation { continuation in
        SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
      }
      guard generation == id, !Task.isCancelled else { return }
      captureProbe?.sample("speech-permission", details: ["status": speechPermission.rawValue])
      guard speechPermission == .authorized else {
        complete(id: id, message: L10n.tr("语音识别权限未开启，请在系统设置中允许语音识别。"))
        return
      }
      let microphonePermission = await withCheckedContinuation { continuation in
        AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
      }
      guard generation == id, !Task.isCancelled else { return }
      captureProbe?.sample("microphone-permission", details: ["granted": microphonePermission])
      guard microphonePermission else {
        complete(id: id, message: L10n.tr("麦克风权限未开启，请在系统设置中允许访问麦克风。"))
        return
      }
      do { try await beginCapture(id: id) } catch {
        let failure = error as NSError
        captureProbe?.sample("setup-failed", details: ["errorDomain": failure.domain, "errorCode": failure.code])
        complete(id: id, message: L10n.tr("无法开始听写：\(error.localizedDescription)"))
      }
    }
  }

  private func beginCapture(id: UUID) async throws {
    let locale = Locale(identifier: Locale.preferredLanguages.first ?? "zh-CN")
    let session = AVAudioSession.sharedInstance()
    try await audioSession.activate(id: id)
    guard generation == id, !Task.isCancelled else {
      audioSession.deactivate(id: id)
      return
    }
    activeAudioSession = id
    captureProbe?.sample("session-activated")
    guard !AVAudioApplication.shared.isInputMuted else {
      throw HostServiceError.message(L10n.tr("麦克风已被系统静音，请解除静音后重试。原草稿已保留。"))
    }
    let engine = AVAudioEngine()
    self.engine = engine
    let input = engine.inputNode
    let format = input.outputFormat(forBus: 0)
    guard format.sampleRate > 0, format.channelCount > 0 else {
      throw HostServiceError.message(L10n.tr("没有可用的麦克风。"))
    }
    let sink: VoiceAudioSink
    var backend = "SFSpeechRecognizer"
    if #available(iOS 26, *),
      let supportedLocale = await DictationTranscriber.supportedLocale(equivalentTo: locale) {
      guard generation == id, !Task.isCancelled else { return }
      if ProcessInfo.processInfo.arguments.contains("-ui-testing") {
        print("VOICE_BACKEND:DictationTranscriber")
      }
      let modern = VoiceAnalyzerRecognition(
        locale: supportedLocale,
        onChange: { [weak self] text in
          guard let self, self.generation == id else { return }
          self.updateTranscript(text)
        },
        onComplete: { [weak self] message in self?.complete(id: id, message: message) })
      modernRecognition = modern
      backend = "DictationTranscriber"
      let audioSink = try await modern.prepare(inputFormat: format)
      guard generation == id, !Task.isCancelled else { modern.cancel(); return }
      sink = VoiceAudioSink { buffer in audioSink.append(buffer) }
    } else {
      // Locale discovery suspends; cancellation may have occurred even if
      // the modern API returned no supported locale.
      guard generation == id, !Task.isCancelled else { return }
      guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
        throw HostServiceError.message(L10n.tr("当前语言的语音识别暂不可用，请稍后重试。"))
      }
      let request = SFSpeechAudioBufferRecognitionRequest()
      request.shouldReportPartialResults = true
      request.taskHint = .dictation
      request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
      self.request = request
      sink = VoiceAudioSink(request: request)
      recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
        let text = result?.bestTranscription.formattedString
        let isFinal = result?.isFinal == true
        let message = error.map(Self.recognitionErrorMessage)
        let serviceUnavailable = (error as NSError?)?.domain == "kLSRErrorDomain"
          && (error as NSError?)?.code == 201
        Task { @MainActor [weak self] in
          guard let self, self.generation == id else { return }
          if let text { self.updateTranscript(text) }
          if isFinal || message != nil {
            self.complete(
              id: id, message: self.phase == .finishing && !serviceUnavailable ? nil : message)
          }
        }
      }
    }
    diagnosticPrefix = "backend=\(backend) locale=\(locale.identifier) input=\(session.currentRoute.inputs.map(\.portType.rawValue).joined(separator: ",")) format=\(format.sampleRate)/\(format.channelCount) muted=\(AVAudioApplication.shared.isInputMuted)"
    let diagnosticsEnabled = captureProbe != nil
    captureProbe?.sample("recognizer-prepared", details: ["backend": backend,
      "tapSampleRate": format.sampleRate, "tapChannels": format.channelCount])
    if ProcessInfo.processInfo.arguments.contains("-ui-testing") || diagnosticsEnabled { diagnosticInfo = diagnosticPrefix }
    input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
      let frames = buffer.frameLength
      let rate = buffer.format.sampleRate
      let channels = buffer.format.channelCount
      let meter = sink.append(buffer)
      guard meter != nil || diagnosticsEnabled else { return }
      Task { @MainActor [weak self] in
        guard let self, self.generation == id, self.phase == .listening else { return }
        self.captureProbe?.buffer(frames: frames, rate: rate, channels: channels, rms: meter?.rms)
        guard let meter else { return }
        self.levels.removeFirst()
        self.levels.append(meter.level)
        if meter.rms > 0 { self.lastSignalAt = Date() }
        if ProcessInfo.processInfo.arguments.contains("-ui-testing") || diagnosticsEnabled {
          self.maximumRMS = max(self.maximumRMS, meter.rms)
          self.meterEvents += 1
          self.diagnosticInfo = self.diagnosticPrefix + " meters=\(self.meterEvents) peak=\(self.maximumRMS)"
        }
        if Date().timeIntervalSince(self.lastSignalAt) >= 8 {
          self.captureProbe?.sample("silence-timeout", details: ["silenceSeconds": Date().timeIntervalSince(self.lastSignalAt)])
          self.complete(id: id, message: L10n.tr("麦克风未提供音频，听写已停止。请检查系统麦克风及通话、其他录音占用后重试。原草稿已保留。"))
        }
      }
    }
    tapInstalled = true
    interruptionObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.interruptionNotification, object: session, queue: .main
    ) { [weak self] notification in
      guard
        (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
          == AVAudioSession.InterruptionType.began.rawValue
      else { return }
      Task { @MainActor [weak self] in
        self?.captureProbe?.sample("interruption")
        self?.complete(id: id, message: L10n.tr("听写被中断，已识别的文字保留在草稿中。"))
      }
    }
    engine.prepare()
    try engine.start()
    lastSignalAt = Date()
    phase = .listening
    captureProbe?.sample("engine-started", details: ["engineRunning": engine.isRunning])
  }

  nonisolated private static func recognitionErrorMessage(_ error: Error) -> String {
    let error = error as NSError
    // Apple's 201 covers Siri OR Dictation availability. It does not identify
    // which setting is off, even when Speech permission is granted.
    if error.domain == "kLSRErrorDomain", error.code == 201 {
      return L10n.tr("系统语音服务暂不可用。请检查 Siri 及「设置 → 通用 → 键盘」中的「启用听写」后重试。原草稿已保留。")
    }
    return L10n.tr("听写已停止：\(error.localizedDescription)")
  }

  private func updateTranscript(_ text: String) {
    transcript = text
    onDraftChange?(targetKey, draftText)
  }

  func finish() async -> Bool {
    guard isActive else { return error == nil }
    if phase == .requesting {
      cancel()
      return false
    }
    return await withCheckedContinuation { continuation in
      finishWaiters.append(continuation)
      guard phase != .finishing else { return }
      phase = .finishing
      stopCapture()
      request?.endAudio()
      modernRecognition?.finish()
      let id = generation
      let timeout: Int64 = modernRecognition == nil ? 2500 : 5000
      finishTimeout = Task {
        do { try await Task.sleep(for: .milliseconds(timeout)) } catch { return }
        complete(id: id)
      }
    }
  }

  // Invalidates permission callbacks and late transcripts as well as active capture.
  func cancel() { complete(id: generation, accepted: false) }

  private func stopCapture() {
    captureProbe?.sample("will-stop", details: ["engineRunning": engine?.isRunning ?? false])
    engine?.stop()
    if tapInstalled {
      engine?.inputNode.removeTap(onBus: 0)
      tapInstalled = false
    }
    if let id = activeAudioSession {
      audioSession.deactivate(id: id)
      activeAudioSession = nil
    }
    captureProbe?.sample("did-stop")
  }

  private func complete(id: UUID, message: String? = nil, accepted: Bool = true) {
    guard generation == id else { return }
    captureProbe?.sample("completed", details: ["hasError": message != nil, "accepted": accepted])
    generation = UUID()
    setupTask?.cancel()
    setupTask = nil
    finishTimeout?.cancel()
    finishTimeout = nil
    stopCapture()
    captureProbe = nil
    request?.endAudio()
    recognitionTask?.cancel()
    recognitionTask = nil
    modernRecognition?.cancel()
    modernRecognition = nil
    request = nil
    engine = nil
    if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
    interruptionObserver = nil
    phase = .idle
    onDraftChange = nil
    let waiters = finishWaiters
    finishWaiters.removeAll()
    for waiter in waiters { waiter.resume(returning: accepted && message == nil) }
    if let message { error = message }
    if accepted { onComplete?(message) }
  }
}

@MainActor
private protocol VoiceRecognitionSession: AnyObject {
  func finish()
  func cancel()
}

// The modern on-device API manages its own speech assets and does not depend
// on the user's Siri/keyboard dictation switch. Older iOS keeps the legacy path.
@available(iOS 26, *)
@MainActor
private final class VoiceAnalyzerRecognition: VoiceRecognitionSession {
  private let transcriber: DictationTranscriber
  private let analyzer: SpeechAnalyzer
  private let onChange: (String) -> Void
  private let onComplete: (String?) -> Void
  private var input: AsyncStream<AnalyzerInput>.Continuation?
  private var resultsTask: Task<Void, Never>?
  private var finalizationTask: Task<Void, Never>?
  private var cancelled = false

  init(locale: Locale, onChange: @escaping (String) -> Void, onComplete: @escaping (String?) -> Void) {
    transcriber = DictationTranscriber(locale: locale, preset: .progressiveLongDictation)
    analyzer = SpeechAnalyzer(modules: [transcriber])
    self.onChange = onChange
    self.onComplete = onComplete
  }

  func prepare(inputFormat: AVAudioFormat) async throws -> VoiceAnalyzerSink {
    if let installation = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
      try await installation.downloadAndInstall()
    }
    try Task.checkCancellation()
    guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(
      compatibleWith: [transcriber], considering: inputFormat) else {
      throw HostServiceError.message(L10n.tr("当前语言没有可用的本地语音识别格式。"))
    }
    try Task.checkCancellation()
    try await analyzer.prepareToAnalyze(in: format)
    try Task.checkCancellation()
    let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream(bufferingPolicy: .bufferingNewest(100))
    input = continuation
    let sink = try VoiceAnalyzerSink(inputFormat: inputFormat, outputFormat: format, input: continuation) { [weak self] in
      Task { @MainActor [weak self] in
        guard let self, !self.cancelled else { return }
        self.onComplete(L10n.tr("语音处理未能跟上录音，已停止听写并保留草稿，请重试。"))
      }
    }
    resultsTask = Task { [weak self, transcriber] in
      var committed = ""
      do {
        for try await result in transcriber.results {
          guard let self, !Task.isCancelled, !self.cancelled else { return }
          let text = committed + String(result.text.characters)
          self.onChange(text)
          if result.isFinal { committed = text }
        }
        guard let self, !Task.isCancelled, !self.cancelled else { return }
        self.onComplete(nil)
      } catch {
        guard let self, !Task.isCancelled, !self.cancelled else { return }
        self.onComplete(L10n.tr("听写已停止：\(error.localizedDescription)"))
      }
    }
    try await analyzer.start(inputSequence: stream)
    return sink
  }

  func finish() {
    input?.finish()
    finalizationTask = Task { [weak self, analyzer] in
      do { try await analyzer.finalizeAndFinishThroughEndOfInput() }
      catch {
        guard let self, !Task.isCancelled, !self.cancelled else { return }
        self.onComplete(L10n.tr("无法完成听写，已保留识别文字：\(error.localizedDescription)"))
      }
    }
  }

  func cancel() {
    cancelled = true
    input?.finish()
    input = nil
    resultsTask?.cancel()
    finalizationTask?.cancel()
    Task { [analyzer] in await analyzer.cancelAndFinishNow() }
  }
}

// Conversion is confined to the audio tap. Each output buffer is newly owned
// by the asynchronous analyzer, rather than retaining an engine-reused buffer.
@available(iOS 26, *)
private final class VoiceAnalyzerSink: @unchecked Sendable {
  private let converter: AVAudioConverter
  private let outputFormat: AVAudioFormat
  private let input: AsyncStream<AnalyzerInput>.Continuation
  private let onFailure: @Sendable () -> Void

  init(inputFormat: AVAudioFormat, outputFormat: AVAudioFormat,
    input: AsyncStream<AnalyzerInput>.Continuation, onFailure: @escaping @Sendable () -> Void) throws {
    guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
      throw HostServiceError.message(L10n.tr("无法准备麦克风语音格式。"))
    }
    self.converter = converter
    self.outputFormat = outputFormat
    self.input = input
    self.onFailure = onFailure
  }

  func append(_ buffer: AVAudioPCMBuffer) {
    let frames = AVAudioFrameCount(ceil(Double(buffer.frameLength) * outputFormat.sampleRate / buffer.format.sampleRate))
    guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: max(1, frames)) else {
      onFailure(); return
    }
    var supplied = false
    var error: NSError?
    let status = converter.convert(to: output, error: &error) { _, inputStatus in
      guard !supplied else { inputStatus.pointee = .noDataNow; return nil }
      supplied = true
      inputStatus.pointee = .haveData
      return buffer
    }
    guard status != .error, error == nil else { onFailure(); return }
    guard output.frameLength > 0 else { return }
    if case .dropped = input.yield(AnalyzerInput(buffer: output)) { onFailure() }
  }
}

// Access is confined to AVAudioEngine's serial tap callback. Only scalar meter
// values leave the callback; no buffer is retained or written to disk.
private final class VoiceAudioSink: @unchecked Sendable {
  private let appendAudio: (AVAudioPCMBuffer) -> Void
  private var lastMeterTime = Date.distantPast
  init(request: SFSpeechAudioBufferRecognitionRequest) { appendAudio = { request.append($0) } }
  init(append: @escaping @Sendable (AVAudioPCMBuffer) -> Void) { appendAudio = append }
  func append(_ buffer: AVAudioPCMBuffer) -> (level: CGFloat, rms: Float)? {
    // Meter the original input before any converter consumes the buffer.
    defer { appendAudio(buffer) }
    let now = Date()
    guard now.timeIntervalSince(lastMeterTime) >= 0.05,
      let channel = buffer.floatChannelData?[0], buffer.frameLength > 0
    else { return nil }
    lastMeterTime = now
    let count = Int(buffer.frameLength)
    var sum: Float = 0
    for index in 0..<count { sum += channel[index] * channel[index] }
    let rms = sqrt(sum / Float(count))
    let decibels = 20 * log10(max(rms, 0.00001))
    return (CGFloat(max(0, min(1, (decibels + 55) / 45))), rms)
  }
}
