import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test(
  'actual voice controller failed recording retry only queries the original provider',
  { skip: process.platform !== 'darwin' },
  async (t) => {
    const source = await readFile('apps/ios/Sources/VoiceInput.swift', 'utf8');
    const start = source.indexOf('  func finish(usingLocalRecording:');
    const end = source.indexOf('  func recordAgain(', start);
    assert.ok(start >= 0 && end > start);
    const directory = await mkdtemp(join(tmpdir(), 'voice-controller-original-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    // Exercise the production finish method. Capture, platform lifecycle and
    // persistence are controlled here; the live ASR/native UI have separate proof.
    const harness = String.raw`import Foundation
import CryptoKit
enum HostServiceError: Error { case unavailable, message(String) }
enum L10n { static func tr(_ value: String) -> String { value } }
struct VoiceRecording: Sendable { let operationId: String; let data: Data; let sha256: String; let durationMs: Int }
struct Provider { let scopeKey = "owned-scope"; let transcribe: (VoiceRecording) async throws -> String; let recover: ((VoiceRecording) async throws -> String)? }
@MainActor enum PendingVoiceStore {
  static var saved: VoiceRecording?
  static func save(_ recording: VoiceRecording, target: String, scopeKey: String, text: String) throws { saved = recording }
  static func remove(_ id: String) { if let saved { precondition(saved.operationId == id) }; saved = nil }
  static func resolveLocally(_ id: String) { remove(id) }
}
struct AVAudioFile { let length = 0; let processingFormat = Format(); struct Format { let sampleRate = 16000.0 }; init(forReading: URL) throws {} }
// Capture diagnostics are disabled in this recovery-only harness.
struct VoiceCaptureProbe { func observeInitialFile(_ file: AVAudioFile) -> Bool { false }; func releaseRecorder() {} }
struct LocalRecordingTranscriber { func transcribe(_ url: URL, _ onStart: () -> Void) async throws -> String { onStart(); return "local-recording" } }
final class LocalRecognition { var error: String?; let transcript = "local-recognition"; func finish() async -> Bool { true } }
@MainActor final class UIApplication { enum State { case active }; static let shared = UIApplication(); let applicationState = State.active }
@MainActor final class Controller {
  enum Phase { case idle, requesting, listening, finishing, failed }
  var phase: Phase; var finishTask: Task<Bool, Never>?; var generation = UUID()
  var recording: VoiceRecording?; var fileURL: URL?; var provider: Provider?
  var captureProbe: VoiceCaptureProbe?
  var recordingPersisted = false; var usesSavedLocalRecognition = false; var usesLocalRecognition = false
  var isFixture = false; var simulatesTranscriptionFailure = false; var localRecognition: LocalRecognition?
  var targetKey = "owned-target"; var originalText = "original prefix "; var transcript = ""; var error: String?
  var needsReceiptRecovery = false; var backgroundedWhileFinishing = false
  var onDraftChange: ((String, String) -> Bool)?
  var draftText: String { originalText + transcript }
  init(_ phase: Phase, _ recording: VoiceRecording?, _ provider: Provider) { self.phase = phase; self.recording = recording; self.provider = provider }
  func beginBackgroundTime(id: UUID) {}; func endBackgroundTime() {}; func stopCapture() {}
  func recoverAfterInterruption() { preconditionFailure("unexpected implicit recovery") }
  func failSetup(id: UUID, message: String, preserveDiagnosticFile: Bool = false) { precondition(!preserveDiagnosticFile); phase = .failed; error = message }
  func reset(discard: Bool, preservePending: Bool) { phase = .idle; if discard { recording = nil } }
${source.slice(start, end)}
}
@MainActor final class Calls {
  var transcribes = 0; var recovers = 0; var ids: [String] = []; var audio: [String] = []; var failRecovery = false
  func provider(hasRecovery: Bool = true) -> Provider {
    Provider(transcribe: { recording in
      self.transcribes += 1; self.ids.append(recording.operationId); self.audio.append(recording.sha256); return "first result"
    }, recover: hasRecovery ? { recording in
      self.recovers += 1; self.ids.append(recording.operationId); self.audio.append(recording.sha256)
      if self.failRecovery { throw HostServiceError.unavailable }; return "recovered result"
    } : nil)
  }
}
@main struct Checks {
  @MainActor static func main() async throws {
    let bytes = Data(repeating: 4, count: 96)
    let recording = VoiceRecording(operationId: "immutable-original", data: bytes,
      sha256: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), durationMs: 1)
    func controller(_ phase: Controller.Phase, _ calls: Calls, hasRecovery: Bool = true) -> Controller {
      let value = Controller(phase, recording, calls.provider(hasRecovery: hasRecovery))
      value.onDraftChange = { target, draft in target == "owned-target" && draft.hasPrefix("original prefix ") }
      return value
    }
    let firstCalls = Calls(), first = controller(.listening, firstCalls)
    let firstDone = await first.finish(); precondition(firstDone && firstCalls.transcribes == 1 && firstCalls.recovers == 0)
    let retryCalls = Calls(), retry = controller(.failed, retryCalls)
    let retryDone = await retry.finish()
    guard retryDone && retryCalls.recovers == 1 && retryCalls.transcribes == 0 else {
      FileHandle.standardError.write(Data("manual retry reentered initial transcription instead of original recovery\n".utf8)); exit(17)
    }
    precondition(retryCalls.ids == [recording.operationId] && retryCalls.audio == [recording.sha256])
    precondition(retry.transcript == "recovered result")
    let failureCalls = Calls(); failureCalls.failRecovery = true
    let failure = controller(.failed, failureCalls)
    let failureDone = await failure.finish(); precondition(!failureDone && failure.phase == .failed)
    precondition(failure.recording?.data == bytes && PendingVoiceStore.saved?.sha256 == recording.sha256)
    let secondFailureDone = await failure.finish(); precondition(!secondFailureDone && failureCalls.recovers == 2 && failureCalls.transcribes == 0)
    precondition(failureCalls.ids == [recording.operationId, recording.operationId] && !failure.needsReceiptRecovery)
    let missingCalls = Calls(), missing = controller(.failed, missingCalls, hasRecovery: false)
    let missingDone = await missing.finish(); precondition(!missingDone && missing.recording?.data == bytes && missingCalls.transcribes == 0)
    let explicitCalls = Calls(), explicit = controller(.listening, explicitCalls)
    let explicitDone = await explicit.finish(queryOnly: true); precondition(explicitDone && explicitCalls.recovers == 1 && explicitCalls.transcribes == 0)
    let localCalls = Calls(), local = controller(.failed, localCalls)
    local.usesLocalRecognition = true; local.localRecognition = LocalRecognition()
    let localDone = await local.finish(); precondition(localDone && localCalls.transcribes == 0 && localCalls.recovers == 0)
    precondition(local.transcript == "local-recognition")
    print("actual-controller-original-retry: all routes passed")
  }
}
`;
    const file = join(directory, 'Controller.swift');
    const executable = join(directory, 'controller-checks');
    await writeFile(file, harness);
    const compiled = spawnSync('swiftc', ['-parse-as-library', file, '-o', executable], {
      encoding: 'utf8',
      timeout: 120000,
      maxBuffer: 1024 * 1024,
    });
    assert.equal(compiled.status, 0, compiled.stderr || compiled.stdout);
    const result = spawnSync(executable, [], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.match(result.stdout, /actual-controller-original-retry: all routes passed/);
  },
);

test(
  'actual EasyWork voice recovery only queries the original recording',
  {
    skip: process.platform !== 'darwin',
  },
  async (t) => {
    const source = await readFile('apps/ios/Sources/EasyWorkAgentClient.swift', 'utf8');
    const voice = await readFile('apps/ios/Sources/VoiceInput.swift', 'utf8');
    const start = source.indexOf('  func transcribeVoice(');
    const end = source.indexOf('  func updateNotificationPreferences(', start);
    const declarations = voice.indexOf('struct VoiceRecording:');
    const declarationsEnd = voice.indexOf('enum VoiceTranscriptionPreference:', declarations);
    assert.ok(start >= 0 && end > start && declarations >= 0 && declarationsEnd > declarations);
    const directory = await mkdtemp(join(tmpdir(), 'easywork-voice-recovery-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    // Compile the actual adapter methods, Codable declarations and CryptoKit hash.
    // Only transport and the shared validator's verdict are controlled here.
    // Validator correctness, live ASR and native draft persistence have separate checks.
    const harness = String.raw`import Foundation
import CryptoKit
enum HostServiceError: Error { case unavailable, invalidResponse, message(String) }
enum L10n { static func tr(_ text: String) -> String { text } }
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]) }
func easyWorkObject(_ data: Data) throws -> [String: Any] { try JSONSerialization.jsonObject(with: data) as! [String: Any] }
extension Data { var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } }
final class ValidationVerdict {
  static let shared = ValidationVerdict()
  var accepted = true
  var calls = 0
  func receiptMatches(expected: String, receipt: String) -> Bool { calls += 1; return accepted }
}
typealias VoiceTranscriptionRules = ValidationVerdict
${voice.slice(declarations, declarationsEnd)}
struct Client { let id = "owned-host" }
protocol AgentClient {
  func transcribeVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String
  func recoverVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String
}
extension AgentClient {
  func recoverVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String { throw HostServiceError.unavailable }
}
struct Call { let method: String; let params: Data; let id: String? }
final class Transport {
  var calls: [Call] = []
  var statuses: [Data]
  let prepared: Data
  let completed: Data
  init(_ statuses: [Data], _ prepared: Data, _ completed: Data) { self.statuses = statuses; self.prepared = prepared; self.completed = completed }
  func request(_ method: String, _ params: [String: Any], id: String? = nil) async throws -> Data {
    calls.append(Call(method: method, params: try easyWorkJSON(params), id: id))
    switch method {
    case "voice/status": guard !statuses.isEmpty else { throw HostServiceError.unavailable }; return statuses.removeFirst()
    case "voice/prepare": return prepared
    case "voice/upload": return completed
    default: throw HostServiceError.invalidResponse
    }
  }
}
struct EasyWorkAgentClient: AgentClient {
  let transport: Transport
  func session(_ client: Client) async throws -> Transport { transport }
${source.slice(start, end)}
}
@main struct Checks {
  static func main() async throws {
    let client = Client(), bytes = Data(repeating: 7, count: 96)
    let recording = VoiceRecording(operationId: "original-audio-operation", data: bytes,
      sha256: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), durationMs: 1000)
    let manifest = VoiceTranscriptionRequest(hostId: client.id, threadId: "original-thread", draftId: "original-scoped-draft",
      operationId: recording.operationId, mediaType: "audio/wav", sizeBytes: bytes.count, sha256: recording.sha256, durationMs: 1000)
    var original = try easyWorkObject(JSONEncoder().encode(manifest))
    original["expiresAt"] = "2099-01-01T00:00:00.000Z"; original["error"] = NSNull()
    func receipt(_ phase: String) throws -> Data {
      var value = original; value["phase"] = phase; value["text"] = phase == "completed" ? "original transcript" as Any : NSNull()
      return try easyWorkJSON(value)
    }
    func status(_ phase: String?) throws -> Data {
      let value: Any
      if let phase { value = try easyWorkObject(receipt(phase)) } else { value = NSNull() }
      return try easyWorkJSON(["receipt": value])
    }
    let prepared = try receipt("awaiting-audio"), completed = try receipt("completed")
    for phase in [nil, "awaiting-audio", "failed", "outcome-unknown"] as [String?] {
      let transport = Transport([try status(phase)], prepared, completed)
      let adapter: any AgentClient = EasyWorkAgentClient(transport: transport)
      var rejected = false
      do { _ = try await adapter.recoverVoice(client, recording: recording, threadId: manifest.threadId, draftId: manifest.draftId) }
      catch { rejected = true }
      precondition(rejected)
      precondition(transport.calls.map(\.method) == ["voice/status"])
      let queried = try easyWorkObject(transport.calls[0].params)["operationId"] as? String
      precondition(queried == recording.operationId)
    }
    for phases in [["completed"], ["transcribing", "completed"]] {
      let transport = Transport(try phases.map { try status($0) }, prepared, completed)
      let adapter: any AgentClient = EasyWorkAgentClient(transport: transport)
      let text = try await adapter.recoverVoice(client, recording: recording, threadId: manifest.threadId, draftId: manifest.draftId)
      precondition(text == "original transcript")
      precondition(transport.calls.map(\.method) == phases.map { _ in "voice/status" })
    }
    let invalid = Transport([try status("completed")], prepared, completed)
    ValidationVerdict.shared.accepted = false
    var rejected = false
    do { _ = try await EasyWorkAgentClient(transport: invalid).recoverVoice(client, recording: recording, threadId: manifest.threadId, draftId: manifest.draftId) }
    catch { rejected = true }
    precondition(rejected && invalid.calls.map(\.method) == ["voice/status"])
    ValidationVerdict.shared.accepted = true
    let changed = Transport([], prepared, completed)
    let bad = VoiceRecording(operationId: recording.operationId, data: bytes, sha256: String(repeating: "0", count: 64), durationMs: 1000)
    rejected = false
    do { _ = try await EasyWorkAgentClient(transport: changed).recoverVoice(client, recording: bad, threadId: manifest.threadId, draftId: manifest.draftId) }
    catch { rejected = true }
    precondition(rejected && changed.calls.isEmpty)
    let fresh = Transport([try status(nil)], prepared, completed)
    let text = try await EasyWorkAgentClient(transport: fresh).transcribeVoice(client, recording: recording, threadId: manifest.threadId, draftId: manifest.draftId)
    precondition(text == "original transcript")
    precondition(fresh.calls.map(\.method) == ["voice/status", "voice/prepare", "voice/upload"])
    precondition(fresh.calls[1].id == recording.operationId + ":prepare" && fresh.calls[2].id == recording.operationId)
    let submitted = try easyWorkObject(fresh.calls[1].params)["recording"] as! [String: Any]
    let submittedBytes = try easyWorkJSON(submitted)
    let expectedBytes = try easyWorkJSON(easyWorkObject(JSONEncoder().encode(manifest)))
    precondition(submittedBytes == expectedBytes)
    print("voice-recovery-routing: completed, polling, missing, awaiting-audio, failed, unknown, rejected receipt, changed bytes, original submit")
  }
}
`;
    const file = join(directory, 'Recovery.swift');
    await writeFile(file, harness);
    const binary = join(directory, 'recovery');
    const compiled = spawnSync('xcrun', ['swiftc', '-parse-as-library', file, '-o', binary], {
      encoding: 'utf8',
    });
    assert.equal(compiled.status, 0, compiled.stdout + compiled.stderr);
    const result = spawnSync(binary, [], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
  },
);
