import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const source = await readFile('apps/ios/Sources/CodexManageApp.swift', 'utf8');
const start = source.indexOf('    func start() async {', source.indexOf('class AppStartupModel'));
const end = source.indexOf('\n    }\n}', start);
assert(start >= 0 && end > start, 'Startup method extraction boundaries changed');
const method = source.slice(start, end + '\n    }'.length);
const sessionSource = await readFile('apps/ios/Sources/SessionStore.swift', 'utf8');
const sessionStart = sessionSource.indexOf('  func restoreForStartup() async {');
const sessionEnd = sessionSource.indexOf('\n  }\n', sessionStart);
assert(sessionStart >= 0 && sessionEnd > sessionStart, 'Session restoration boundaries changed');
const restore = sessionSource.slice(sessionStart, sessionEnd + '\n  }'.length);
const directory = await mkdtemp(join(tmpdir(), 'agent-hub-startup-scheduling-'));
try {
  const swift = `import Foundation
actor Gates {
  var taskOpen = false
  func releaseTask() { taskOpen = true }
  func waitTask() async { while !taskOpen { try? await Task.sleep(nanoseconds: 5_000_000) } }
}
let gates = Gates()
let readGate = DispatchSemaphore(value: 0)
final class Probe: @unchecked Sendable {
  let lock = NSLock(); private var started = false; private var main = true
  func record() { lock.lock(); started = true; main = Thread.isMainThread; lock.unlock() }
  func values() -> (Bool, Bool) { lock.lock(); defer { lock.unlock() }; return (started, main) }
}
let probe = Probe()
struct UIApplication {
  static let shared = UIApplication()
  let applicationState = State(); let isProtectedDataAvailable = true
  struct State { let rawValue = 0 }
}
struct LocalRestoration { let saved: String; let dirtyHistoryKeys: Set<String> }
@MainActor final class SessionStore {
  let testMode = false; let persistenceWriter = 0; let persistenceURL = URL(fileURLWithPath: "/tmp/fixture")
  var saved = "empty"; var dirtyHistoryKeys: Set<String> = []; var localRecoveryRequired = false
  init(deferRestoration: Bool) { precondition(deferRestoration) }
  func restoreLocalSession() { fatalError("unexpected fixture path") }
  func finishLocalRestoration() {}
  nonisolated static func readLocalSession(writer: Int, url: URL, applicationState: String, protectedDataAvailable: String) throws -> LocalRestoration {
    probe.record()
    precondition(readGate.wait(timeout: .now() + 5) == .success, "Reader gate timed out")
    return LocalRestoration(saved: "complete-draft-history-operation", dirtyHistoryKeys: ["history"])
  }
${restore}
}
final class TaskHubStore {
  let snapshot = "complete-taskhub"
  static func restoredForStartup() async -> TaskHubStore { await gates.waitTask(); return TaskHubStore() }
}
@MainActor final class AppStartupModel {
  struct Stores { let session: SessionStore; let taskHub: TaskHubStore }
  private(set) var stores: Stores?
  private var restoration: Task<Stores, Never>?
${method}
}
@main struct Check {
  @MainActor static func main() async {
    let model = AppStartupModel()
    let task = Task { await model.start() }
    for _ in 0..<100 where !probe.values().0 { try? await Task.sleep(nanoseconds: 5_000_000) }
    precondition(probe.values().0 && !probe.values().1, "Actual restore must read off the main thread")
    precondition(model.stores == nil, "Published before restoration")
    var heartbeat = 0
    for _ in 0..<5 { await Task.yield(); heartbeat += 1 }
    precondition(heartbeat == 5, "Main actor did not respond")
    readGate.signal()
    try? await Task.sleep(nanoseconds: 100_000_000)
    precondition(model.stores == nil, "Published before task hub finished")
    await gates.releaseTask()
    await task.value
    precondition(model.stores?.session.saved == "complete-draft-history-operation")
    precondition(model.stores?.session.dirtyHistoryKeys == ["history"])
    precondition(model.stores?.taskHub.snapshot == "complete-taskhub")
    print("Production startup coordinator: main actor responsive; no partial publication; complete snapshots delivered")
  }
}
`;
  const file = join(directory, 'scheduling.swift');
  const executable = join(directory, 'scheduling');
  await writeFile(file, swift, { mode: 0o600 });
  execFileSync('xcrun', ['swiftc', '-parse-as-library', file, '-o', executable], {
    stdio: 'inherit',
  });
  execFileSync(executable, { stdio: 'inherit', timeout: 15000 });
} finally {
  await rm(directory, { recursive: true, force: true });
}
