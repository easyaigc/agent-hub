import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Executes the production Swift event waiter/poll and delta merger with synthetic
// state. These are native boundary/CPU checks, not physical power measurements.
const directory = resolve('.local/mobile-idle-performance/' + Date.now());
await mkdir(directory, { recursive: true, mode: 0o700 });
const baselineAt = process.argv.indexOf('--baseline');
const baseline = baselineAt < 0 ? undefined : resolve(process.argv[baselineAt + 1]);
const sessionPath = 'apps/ios/Sources/EasyWorkSession.swift';
const syncPath = 'apps/ios/Sources/EasyWorkThreadSync.swift';
const session = await readFile(sessionPath, 'utf8');
const sync = await readFile(syncPath, 'utf8');
const declaration = (source, start) => {
  const at = source.indexOf(start);
  assert(at >= 0, start);
  const opening = source.indexOf('{', at);
  let depth = 1;
  let end = opening + 1;
  for (; depth > 0 && end < source.length; end++) {
    if (source[end] === '{') depth++;
    else if (source[end] === '}') depth--;
  }
  assert.equal(depth, 0);
  return source.slice(at, end);
};
const pollHarness = (source, name) => `
actor ${name} {
  var closed = false
  var transport: Int? = 1
  var lastError: Error?
  var subscriptions = ["thread": "epoch"]
  var subscribedThreads: Set<String> = ["thread", "old-thread"]
  var unsubscribed: [String] = []
  var epoch = "epoch"
  var threadSequences: [String: Int] = [:]
  let eventSignal = EasyWorkEventSignal()
  var iterations = 0
  func connect() async throws { transport = 1 }
  func request(_ method: String, _ params: [String: Any]) async throws -> Data {
    if method == "thread/unsubscribe" { unsubscribed.append(params["threadId"] as! String) }
    return Data()
  }
  func emit(_ thread: String, _ cursor: Int) async {
    threadSequences[thread] = cursor
    await eventSignal.signal()
  }
  func close() async { closed = true; await eventSignal.signal() }
  ${declaration(source, 'func pollEvents(').replace('repeat {', 'repeat { iterations += 1')}
}
`;
const oldSession = baseline ? await readFile(join(baseline, sessionPath), 'utf8') : undefined;
const oldSync = baseline ? await readFile(join(baseline, syncPath), 'utf8') : undefined;
const main = `
import Foundation
import CryptoKit
enum HostServiceError: Error { case wrongHost, invalidResponse }
func easyWorkJSON(_ value: Any) throws -> Data {
  try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes])
}
${declaration(session, 'actor EasyWorkEventSignal')}
${pollHarness(session, 'CurrentPoll')}
${oldSession ? pollHarness(oldSession, 'BaselinePoll') : ''}
${sync}
${oldSync ? oldSync.replaceAll('EasyWorkThreadSync', 'BaselineThreadSync').replaceAll('EasyWorkSyncError', 'BaselineSyncError') : ''}
@main struct IdlePerformanceSmoke {
  static func main() async throws {
    let signal = EasyWorkEventSignal()
    let stamp = await signal.current()
    await signal.signal()
    try await signal.wait(after: stamp, for: .seconds(12))
    for _ in 0..<100 {
      let stamp = await signal.current()
      let waiter = Task { try await signal.wait(after: stamp, for: .seconds(12)) }
      waiter.cancel()
      do { try await waiter.value; fatalError("Cancelled waiter returned successfully") }
      catch is CancellationError {}
    }
    let current = CurrentPoll()
    let idle = Task { try await current.pollEvents(threadId: "thread", after: 0) }
    ${oldSession ? 'let old = BaselinePoll(); let oldIdle = Task { try await old.pollEvents(threadId: "thread", after: 0) }' : ''}
    let cursor = try await idle.value
    guard cursor == 0 else { fatalError("Quiet subscription advanced its cursor") }
    let quietIterations = await current.iterations
    guard quietIterations <= 2 else { fatalError("Quiet subscription is polling") }
    guard await current.unsubscribed == ["old-thread"] else { fatalError("Retired chat still subscribed") }
    ${oldSession ? 'let oldCursor = try await oldIdle.value; guard oldCursor == 0 else { fatalError() }; let baselineIterations = await old.iterations' : ''}
    let pending = Task { try await current.pollEvents(threadId: "thread", after: 0) }
    try await Task.sleep(for: .milliseconds(100))
    await current.emit("other-thread", 9)
    try await Task.sleep(for: .milliseconds(60))
    let began = ContinuousClock.now
    await current.emit("thread", 1)
    guard try await pending.value == 1 else { fatalError("Lost actual thread event") }
    let wakeMS = Double(began.duration(to: .now).components.attoseconds) / 1e15
    let closing = Task { try await current.pollEvents(threadId: "thread", after: 1) }
    try await Task.sleep(for: .milliseconds(40))
    await current.close()
    do { _ = try await closing.value; fatalError("Closed session left a waiter") } catch is CancellationError {}

    let text = String(repeating: "Long history 中文 😀 ", count: 400)
    let digest = SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    let messages: [[String: Any]] = (0..<200).map { ["id": "m\\($0)", "role": "assistant", "content": [["type": "text", "text": text]]] }
    let head: [[String: Any]] = (0..<200).map { ["id": "m\\($0)", "hash": "v\\($0)", "textLength": text.utf16.count, "textHash": digest, "shapeHash": "shape"] }
    let frame: [String: Any] = ["snapshot": ["thread": ["id": "thread"]], "messages": messages, "head": head, "branchRevision": 1, "reset": true, "patches": []]
    var merger = try EasyWorkThreadSync().merging(frame, threadId: "thread").0
    var quiet = frame; quiet["messages"] = []; quiet["reset"] = false
    let start = ContinuousClock.now
    for _ in 0..<12 { merger = try merger.merging(quiet, threadId: "thread").0 }
    let mergeMS = milliseconds(start.duration(to: .now))
    var badHead = head; badHead[0]["textHash"] = "wrong"
    var bad = quiet; bad["head"] = badHead
    do { _ = try merger.merging(bad, threadId: "thread"); fatalError("Changed digest trusted") } catch EasyWorkSyncError.invalidDelta {}
    var changed = messages[0]; changed["content"] = [["type": "text", "text": text + "tampered"]]
    bad = quiet; bad["messages"] = [changed]
    do { _ = try merger.merging(bad, threadId: "thread"); fatalError("Changed content trusted") } catch EasyWorkSyncError.invalidDelta {}
    ${
      oldSync
        ? `var oldMerger = try BaselineThreadSync().merging(frame, threadId: "thread").0
    let oldStart = ContinuousClock.now
    for _ in 0..<12 { oldMerger = try oldMerger.merging(quiet, threadId: "thread").0 }
    let oldMergeMS = milliseconds(oldStart.duration(to: .now))`
        : ''
    }
    let report: [String: Any] = ["passed": true, "quietSeconds": 12, "quietLoopIterations": quietIterations,
      "eventWakeMS": wakeMS, "longHistoryMerges": 12, "messageCount": 200, "mergeMS": mergeMS,
      ${oldSession ? '"baselineQuietLoopIterations": baselineIterations,' : ''}
      ${oldSync ? '"baselineMergeMS": oldMergeMS,' : ''}
      "scope": "production Swift functions, synthetic native boundary and wall-time checks; no physical power claim"]
    print(String(decoding: try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]), as: UTF8.self))
  }
  static func milliseconds(_ duration: Duration) -> Double {
    Double(duration.components.seconds) * 1000 + Double(duration.components.attoseconds) / 1e15
  }
}
`;
await writeFile(join(directory, 'smoke.swift'), main);
const binary = join(directory, 'smoke');
execFileSync(
  'xcrun',
  ['swiftc', '-O', '-parse-as-library', join(directory, 'smoke.swift'), '-o', binary],
  {
    stdio: 'inherit',
  },
);
const report = JSON.parse(execFileSync(binary, { encoding: 'utf8', timeout: 60_000 }));
report.sourceSha256 = createHash('sha256').update(session).update(sync).digest('hex');
if (oldSession)
  report.baselineSha256 = createHash('sha256').update(oldSession).update(oldSync).digest('hex');
await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ directory, ...report }));
