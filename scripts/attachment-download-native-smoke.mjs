import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Production Swift download functions against synthetic HTTP; no desktop/model evidence.
if (process.platform !== 'darwin') {
  console.log('Attachment native regression requires macOS Swift.');
  process.exit(0);
}
const cancelStartDelayMs = Number(process.env.ATTACHMENT_NATIVE_CANCEL_START_DELAY_MS ?? 1500);
assert(
  Number.isSafeInteger(cancelStartDelayMs) && cancelStartDelayMs >= 0 && cancelStartDelayMs <= 5000,
  'ATTACHMENT_NATIVE_CANCEL_START_DELAY_MS must be an integer from 0 to 5000',
);
const execute = promisify(execFile);
const arm = process.arch === 'arm64';
// A cold Kotlin/Native link includes toolchain preparation. Keep its build budget
// separate from the short Swift compilation and download assertions below.
console.log('Preparing shared iOS simulator framework (20 minute build budget).');
const frameworkBuild = await execute(
  process.execPath,
  [
    'scripts/mobile/gradle.mjs',
    ':mobile-core:linkDebugFramework' + (arm ? 'IosSimulatorArm64' : 'IosX64'),
  ],
  { timeout: 20 * 60_000, maxBuffer: 8 * 1024 * 1024 },
);
process.stdout.write(frameworkBuild.stdout);
process.stderr.write(frameworkBuild.stderr);
console.log('Shared iOS simulator framework ready; starting native download checks.');
await mkdir('.local/attachment-download-native', { recursive: true, mode: 0o700 });
const directory = await mkdtemp(resolve('.local/attachment-download-native/run-'));
const source = await readFile('apps/ios/Sources/HostService.swift', 'utf8');
const discovery = await readFile('apps/ios/Sources/CodexDiscovery.swift', 'utf8');
const progressTypes = source.slice(
  source.indexOf('struct FileDownloadProgress:'),
  source.indexOf('// Opt-in metadata'),
);
const operation = discovery.slice(
  discovery.indexOf('final class FileDownloadOperation:'),
  discovery.indexOf('final class CodexSystemTrust:'),
);
const models = await readFile('apps/ios/Sources/Models.swift', 'utf8');
const methods = source.slice(
  source.indexOf('  static func baseURL('),
  source.indexOf('  static func nextTurnSuggestion('),
);
const resolutions = source.slice(
  source.indexOf('  static func resolveMessageFile('),
  source.indexOf('  static func download('),
);
const downloads = source.slice(source.indexOf('  static func download('), source.lastIndexOf('}'));
const errors = source.slice(
  source.indexOf('enum HostServiceError:'),
  source.indexOf('// Tokens never'),
);
const attachment = models.slice(
  models.indexOf('struct RemoteAttachment:'),
  models.indexOf('struct PlanStep:'),
);
const identitySource = await readFile('apps/ios/Sources/NotificationModels.swift', 'utf8');
const targetTypes = identitySource.slice(
  identitySource.indexOf('struct AgentRuntimeTarget:'),
  identitySource.indexOf('// Machine identity'),
);
const sharedSource = await readFile('apps/ios/Sources/SharedMobileRules.swift', 'utf8');
const sharedIdentity =
  sharedSource.slice(0, sharedSource.indexOf('  static func nativeReadingKey(')) + '\n}\n';
const nonce = randomUUID();
const target = { agentId: 'codex', hostId: nonce, workspaceId: 'workspace', runtimeId: 'runtime' };
const hash = (data) => createHash('sha256').update(data).digest('hex');
const counts = new Map();
const version = new Map();
const isolatedTargets = [
  { ...target, account: { authority: 'legacy', subject: 'first' } },
  { ...target, account: { authority: 'other', subject: 'second' } },
  { ...target, workspaceId: 'other-workspace' },
  { ...target, runtimeId: 'other-runtime' },
  { ...target },
  { ...target, hostId: nonce + '-other' },
  { ...target, account: { authority: 'legacy', subject: 'first' } },
];
let stableResolution = 0;
let expectedBinaryTarget = target;
const binaryTargets = [];
let ownedSimulator;
let simulator;
let selectedRuntime;
let selectedSDK;
let phase = 'listener';
let connections = 0;
let healthRequests = 0;
let healthReply = nonce;
const phaseStarted = performance.now();
const phases = [];
const cancelClosed = Promise.withResolvers();
const cancelTransfer = {
  startDelayMs: cancelStartDelayMs,
  totalBytes: 0,
  bytesSent: 0,
  closedEarly: false,
};
const enterPhase = (name) => {
  phase = name;
  phases.push({ name, elapsedMs: Math.round(performance.now() - phaseStarted) });
  console.log('Native download fixture phase: ' + name);
};
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const send = (code, value) =>
    res.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
  assert.equal(req.headers.authorization, 'Bearer synthetic-token');
  assert([nonce, nonce + '-other'].includes(url.searchParams.get('hostId')));
  if (url.pathname === '/fixture-health') {
    healthRequests += 1;
    res.setHeader('Cache-Control', 'no-store');
    return send(200, { fixtureReady: healthReply });
  }
  const id =
    url.searchParams.get('id') ??
    (url.pathname === '/v1/attachments/resolve-link' ? `large_${nonce}_original` : null);
  const scenario = id?.split('_')[0];
  const key = scenario + url.pathname;
  counts.set(key, (counts.get(key) ?? 0) + 1);
  if (['/v1/attachments/resolve', '/v1/attachments/resolve-link'].includes(url.pathname)) {
    assert.equal(url.searchParams.get('target'), null, 'Resolver whitelist excludes target');
    if (scenario === 'stable') {
      expectedBinaryTarget = isolatedTargets[stableResolution++];
      assert.equal(url.searchParams.get('hostId'), expectedBinaryTarget.hostId);
      const content = 'stable-current-version';
      return send(200, {
        hostId: expectedBinaryTarget.hostId,
        threadId: url.searchParams.get('threadId'),
        sourceAttachmentId: id,
        readTarget: expectedBinaryTarget,
        attachment: {
          id,
          hostId: expectedBinaryTarget.hostId,
          threadId: url.searchParams.get('threadId'),
          name: 'same-original-name.txt',
          mediaType: 'text/plain',
          sizeBytes: Buffer.byteLength(content),
          sha256: hash(content),
        },
      });
    }
    expectedBinaryTarget = scenario === 'legacyNil' ? null : target;
    if (scenario === 'legacy' || scenario === 'legacyNil') return send(404, { error: 'old-host' });
    if (scenario === 'removed' && version.has(scenario))
      return send(410, { error: 'file-removed' });
    const n = (version.get(scenario) ?? 0) + 1;
    version.set(scenario, n);
    const content = ['large', 'cancel'].includes(scenario)
      ? Buffer.alloc(24 * 1024 * 1024 + 1, 65)
      : scenario === 'zero'
        ? ''
        : `${scenario}-version-${n}`;
    return send(200, {
      hostId: scenario === 'wrongHost' ? 'other-host' : nonce,
      threadId: 'thread',
      messageId: url.searchParams.get('messageId'),
      reference: url.searchParams.get('reference'),
      sourceAttachmentId: scenario === 'wrongSource' ? 'other-id' : id,
      readTarget: scenario === 'wrongRuntime' ? { ...target, runtimeId: 'other-runtime' } : target,
      attachment: {
        id: `${scenario}_${nonce}_${n}`,
        hostId: nonce,
        threadId: scenario === 'wrongThread' ? 'other-thread' : 'thread',
        name: 'report.txt',
        mediaType: 'text/plain',
        sizeBytes: Buffer.byteLength(content),
        sha256: hash(content),
      },
    });
  }
  assert.equal(url.pathname, '/v1/attachments');
  const binaryTarget = JSON.parse(url.searchParams.get('target'));
  assert.deepEqual(
    binaryTarget,
    expectedBinaryTarget,
    'Binary GET keeps captured complete runtime and account payload',
  );
  binaryTargets.push(binaryTarget);
  if (scenario === 'stable') return res.writeHead(200).end('stable-current-version');
  if (scenario === 'changing' || (scenario === 'race' && counts.get(key) === 1))
    return send(409, { error: 'changed-during-download' });
  if (scenario === 'denied') return send(403, { error: 'denied' });
  if (scenario === 'redirect') return res.writeHead(302, { Location: '/redirect-target' }).end();
  const content = ['large', 'cancel'].includes(scenario)
    ? Buffer.alloc(24 * 1024 * 1024 + 1, 65)
    : scenario === 'zero'
      ? ''
      : scenario === 'legacy' || scenario === 'legacyNil'
        ? 'original'
        : `${scenario}-version-${id.split('_').at(-1)}`;
  if (['large', 'cancel'].includes(scenario)) {
    res.writeHead(200, { 'Content-Length': content.length });
    let offset = 0;
    let timer, startup;
    const stop = () => {
      clearInterval(timer);
      clearTimeout(startup);
    };
    // Keep the cancel response incomplete even if Swift is scheduled late.
    // Cancellation must happen after observed bytes, rather than a fixed sleep.
    const limit = content.length - (scenario === 'cancel' ? 1 : 0);
    if (scenario === 'cancel') cancelTransfer.totalBytes = content.length;
    res.once('close', () => {
      stop();
      if (scenario === 'cancel') {
        cancelTransfer.closedEarly = !res.writableEnded && offset < content.length;
        cancelClosed.resolve();
      }
    });
    const stream = () => {
      timer = setInterval(() => {
        if (res.destroyed || offset >= limit) {
          stop();
          if (!res.destroyed && scenario !== 'cancel') res.end();
          return;
        }
        const end = Math.min(offset + 1024 * 1024, limit);
        res.write(content.subarray(offset, end));
        offset = end;
        if (scenario === 'cancel') cancelTransfer.bytesSent = offset;
      }, 100);
    };
    if (scenario === 'cancel') startup = setTimeout(stream, cancelStartDelayMs);
    else stream();
    return;
  }
  res.writeHead(200).end(scenario === 'corrupt' ? 'invalid-bytes' : content);
});
server.on('connection', () => {
  connections += 1;
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const healthURL = 'http://127.0.0.1:' + server.address().port + '/fixture-health?hostId=' + nonce;
const checkHostHealth = async () => {
  const response = await fetch(healthURL, {
    headers: { Authorization: 'Bearer synthetic-token' },
    signal: AbortSignal.timeout(5_000),
  });
  assert.equal(response.status, 200, 'Host must reach its own fixture listener');
  assert.deepEqual(await response.json(), { fixtureReady: nonce });
};
try {
  enterPhase('host-http-ready');
  await checkHostHealth();
  enterPhase('swift-source');
  const swift = `import Foundation
import Darwin
import CryptoKit
import MobileCore
enum L10n { static func tr(_ text: String) -> String { text } }
${errors}
${progressTypes}
${operation}
final class CodexSystemTrust: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
${targetTypes}
${sharedIdentity}
struct Client { let id: String; let endpoint: String?; let readingTarget: AgentRuntimeTarget? }
// The attachment resolution DTO does not use these unrelated MobileCore contracts.
struct WorkspaceSnapshot {}; struct HistorySnapshot {}; struct EventBatch {}; struct ReplyDeliveryInspection {}
struct ThreadForkOptions {}; struct ThreadForkOperation {}; struct VoiceTranscriptionReadiness {}; struct VoiceTranscriptionReceipt {}
struct DesktopSuggestionResponse {}
struct CodexHttpContract {
  static let shared = CodexHttpContract()
  func validate(json: String, definition: String) -> String? { fatalError("Unexpected non-attachment contract in download fixture") }
}
${attachment}
enum CredentialVault { static func token(_ id: String) -> String { "synthetic-token" } }
final class CodexHostRoutes {
  static let shared = CodexHostRoutes()
  let session = URLSession(configuration: .ephemeral, delegate: CodexSystemTrust(), delegateQueue: nil)
  func download(for request: URLRequest, totalBytes: Int64, progress: FileDownloadProgressHandler?) async throws -> (Data, URLResponse) {
    try await FileDownloadOperation(totalBytes: totalBytes, progress: progress).run(session: session, request: request)
  }
  func data(for request: URLRequest, maxBytes: Int? = nil) async throws -> (Data, URLResponse) { try await session.data(for: request) }
}
struct HostService {
${methods}
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
${resolutions}
  static func boundaryDirectory(_ client: Client, file: RemoteAttachment) throws -> URL { try resourceDirectory(client, file: file) }
${downloads}
}
final class ProgressCapture: @unchecked Sendable {
  private let lock = NSLock()
  private var samples: [FileDownloadProgress] = []
  var values: [FileDownloadProgress] { lock.withLock { samples } }
  func add(_ value: FileDownloadProgress) { lock.withLock { samples.append(value) } }
  func waitForActiveTransfer() async throws {
    let deadline = ContinuousClock.now.advanced(by: .seconds(10))
    while !values.contains(where: { $0.phase == .downloading && $0.receivedBytes > 0 && $0.receivedBytes < $0.totalBytes }) {
      guard ContinuousClock.now < deadline else {
        let detail = values.map { "\\($0.phase):\\($0.receivedBytes)/\\($0.totalBytes)" }.joined(separator: ", ")
        throw NSError(domain: "FixtureCancellation", code: 1, userInfo: [NSLocalizedDescriptionKey: "No active byte progress before cancellation deadline: " + detail])
      }
      try await Task.sleep(nanoseconds: 10_000_000)
    }
  }
}
@main struct Check {
  static func fixtureHealth() async throws {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 2
    configuration.timeoutIntervalForResource = 3
    let session = URLSession(configuration: configuration)
    defer { session.invalidateAndCancel() }
    let deadline = ProcessInfo.processInfo.systemUptime + 90
    var attempts = 0
    var lastCode = 0
    while ProcessInfo.processInfo.systemUptime < deadline {
      attempts += 1
      var request = URLRequest(url: URL(string: "http://127.0.0.1:${server.address().port}/fixture-health?hostId=${nonce}")!)
      request.setValue("Bearer synthetic-token", forHTTPHeaderField: "Authorization")
      do {
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
          throw NSError(domain: "FixtureReadiness", code: 1, userInfo: [NSLocalizedDescriptionKey: "Fixture health status mismatch"])
        }
        let object = try JSONSerialization.jsonObject(with: data) as? [String: String]
        guard object?["fixtureReady"] == "${nonce}" else {
          throw NSError(domain: "FixtureReadiness", code: 2, userInfo: [NSLocalizedDescriptionKey: "Fixture health marker mismatch"])
        }
        print("fixture-health ready; attempts=\\(attempts)")
        return
      } catch {
        let value = error as NSError
        guard value.domain == NSURLErrorDomain, [-1001, -1003, -1004, -1005, -1009].contains(value.code) else { throw error }
        lastCode = value.code
        print("fixture-health transport pending; attempt=\\(attempts) code=\\(lastCode)")
      }
      try await Task.sleep(nanoseconds: 250_000_000)
    }
    throw NSError(domain: "FixtureReadiness", code: 3, userInfo: [NSLocalizedDescriptionKey: "Simulator fixture HTTP not ready after bounded probes; attempts=\\(attempts) lastCode=\\(lastCode)"])
  }
  static func main() async throws {
    if CommandLine.arguments.contains("--fixture-health") {
      do { try await fixtureHealth() }
      catch {
        FileHandle.standardError.write(Data(((error as NSError).localizedDescription + "\\n").utf8))
        Darwin.exit(1)
      }
      return
    }
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "runtime")
    let client = Client(id: "${nonce}", endpoint: "http://127.0.0.1:${server.address().port}", readingTarget: target)
    let scenarios = ["large", "cached", "removed", "race", "changing", "legacy", "wrongHost", "wrongThread", "wrongSource", "wrongRuntime", "corrupt", "zero", "denied", "redirect", "cancel"]
    var ownedScopes = Set<URL>()
    defer { for scope in ownedScopes { try? FileManager.default.removeItem(at: scope) } }
    func remember(_ client: Client, _ file: RemoteAttachment) throws {
      ownedScopes.insert(try HostService.boundaryDirectory(client, file: file).deletingLastPathComponent())
    }
    for scenario in scenarios {
      var file = RemoteAttachment(id: "\\(scenario)_${nonce}_original", hostId: client.id, threadId: "thread", name: "report.txt", mediaType: "text/plain", sizeBytes: 8, sha256: HostService.hash(Data("original".utf8)))
      if scenario == "large" {
        file = try await HostService.resolveMessageFile(client, threadId: "thread", messageId: "large-message", reference: "large-file")
        precondition(file.sizeBytes == 24 * 1024 * 1024 + 1)
      }
      try remember(client, file)
      if scenario == "cancel" {
        let capture = ProgressCapture()
        let task = Task { try await HostService.download(client, file: file, progress: { capture.add($0) }) }
        defer { task.cancel() }
        try await capture.waitForActiveTransfer()
        task.cancel()
        do { _ = try await task.value; fatalError("cancelled download succeeded") }
        catch is CancellationError {}
        catch let error as URLError where error.code == .cancelled {}
        precondition(capture.values.contains { $0.phase == .downloading && $0.receivedBytes > 0 && $0.receivedBytes < $0.totalBytes })
        precondition(!capture.values.contains { $0.phase == .verifying })
        print("cancelled during active byte transfer")
        continue
      }
      do {
        let capture = ProgressCapture()
        let url = try await HostService.download(client, file: file, progress: { capture.add($0) })
        if scenario == "large" {
          let events = capture.values.filter { $0.phase == .downloading && $0.receivedBytes > 0 && $0.receivedBytes < $0.totalBytes }
          FileHandle.standardError.write(Data("events=\\(events.count) all=\\(capture.values.count)\\n".utf8))
          precondition(events.count >= 2 && events.contains { $0.bytesPerSecond > 0 })
          print("intermediate progress: " + events.map { "\\($0.receivedBytes)/\\($0.totalBytes) @ \\(Int($0.bytesPerSecond)) B/s" }.joined(separator: ", "))
        }
        let body = try String(contentsOf: url, encoding: .utf8)
        switch scenario {
        case "large": precondition(body.utf8.count == 24 * 1024 * 1024 + 1)
        case "cached":
          precondition(body == "cached-version-1")
          let refreshed = try await HostService.download(client, file: file)
          let refreshedBody = try String(contentsOf: refreshed, encoding: .utf8)
          precondition(refreshedBody == "cached-version-2")
          let originalBody = try String(contentsOf: url, encoding: .utf8)
          precondition(url != refreshed && originalBody == "cached-version-1", "An open old version must remain immutable")
        case "zero":
          precondition(body.isEmpty && capture.values.contains { $0.phase == .verifying && $0.receivedBytes == 0 })
        case "race": precondition(body == "race-version-2")
        case "removed":
          precondition(body == "removed-version-1")
          do { _ = try await HostService.download(client, file: file); fatalError("Removed file reused cached bytes") }
          catch HostServiceError.rejected(410, _) {}
        case "legacy": precondition(body == "original")
        default: fatalError("Unexpected success: \\(scenario)")
        }
      } catch HostServiceError.rejected(403, _) where scenario == "denied" {
      } catch HostServiceError.rejected(302, _) where scenario == "redirect" {
      } catch HostServiceError.rejected(409, _) where scenario == "changing" {
      } catch HostServiceError.wrongHost where scenario.hasPrefix("wrong") {
      } catch HostServiceError.message(_) where scenario == "corrupt" {
      }
    }
    let accounts = [AgentAccount(authority: "legacy", subject: "first"), AgentAccount(authority: "other", subject: "second")]
    let targets = [
      AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "runtime", account: accounts[0]),
      AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "runtime", account: accounts[1]),
      AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "other-workspace", runtimeId: "runtime"),
      AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "other-runtime"),
      target,
      AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}-other", workspaceId: "workspace", runtimeId: "runtime"),
      AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "runtime", account: accounts[0]),
    ]
    var urls: [URL] = []
    for (index, value) in targets.enumerated() {
      let scoped = Client(id: value.hostId, endpoint: client.endpoint, readingTarget: value)
      let file = RemoteAttachment(id: "stable_${nonce}_original", hostId: scoped.id, threadId: index == 4 ? "other-thread" : "thread", name: "same-original-name.txt", mediaType: "text/plain", sizeBytes: 22, sha256: HostService.hash(Data("stable-current-version".utf8)))
      try remember(scoped, file)
      let captured = ProgressCapture()
      let url = try await HostService.download(scoped, file: file, progress: { captured.add($0) })
      if index == 1 || index == 6 { precondition(captured.values.contains { $0.phase == .cached }) }
      let stableBytes = try Data(contentsOf: url)
      precondition(stableBytes == Data("stable-current-version".utf8))
      urls.append(url)
    }
    precondition(urls[0] == urls[1] && urls[0] == urls[6], "Codex account metadata must not reject or fragment cache")
    precondition(Set([urls[0], urls[2], urls[3], urls[4], urls[5]]).count == 5, "Full host/workspace/runtime/thread cache isolation")
    for name in [".", "..", "../outside", "windows\\\\file", "bad\\0name"] {
      let invalid = RemoteAttachment(id: "bad_${nonce}", hostId: client.id, threadId: "thread", name: name, mediaType: "text/plain", sizeBytes: 8, sha256: HostService.hash(Data("original".utf8)))
      do { _ = try await HostService.download(client, file: invalid); fatalError("Invalid resource name accepted") }
      catch HostServiceError.wrongHost {}
    }
    let legacy = Client(id: client.id, endpoint: client.endpoint, readingTarget: nil)
    let legacyFile = RemoteAttachment(id: "legacy_${nonce}_original", hostId: client.id, threadId: "thread", name: "report.txt", mediaType: "text/plain", sizeBytes: 8, sha256: HostService.hash(Data("original".utf8)))
    let legacyDirectory = try HostService.boundaryDirectory(legacy, file: legacyFile)
    let scopedDirectory = try HostService.boundaryDirectory(client, file: legacyFile)
    precondition(legacyDirectory != scopedDirectory, "Legacy host cache cannot impersonate a runtime namespace")
    let legacyNilFile = RemoteAttachment(id: "legacyNil_${nonce}_original", hostId: client.id, threadId: "thread", name: "report.txt", mediaType: "text/plain", sizeBytes: 8, sha256: HostService.hash(Data("original".utf8)))
    try remember(legacy, legacyNilFile)
    let legacyURL = try await HostService.download(legacy, file: legacyNilFile)
    let legacyBytes = try Data(contentsOf: legacyURL)
    precondition(legacyBytes == Data("original".utf8), "Old Host without reading target retains exact-version download")
    let otherOne = Client(id: client.id, endpoint: client.endpoint, readingTarget: AgentRuntimeTarget(agentId: "other", hostId: client.id, workspaceId: "workspace", runtimeId: "runtime", account: accounts[0]))
    let otherTwo = Client(id: client.id, endpoint: client.endpoint, readingTarget: AgentRuntimeTarget(agentId: "other", hostId: client.id, workspaceId: "workspace", runtimeId: "runtime", account: accounts[1]))
    let otherDirectoryOne = try HostService.boundaryDirectory(otherOne, file: legacyFile)
    let otherDirectoryTwo = try HostService.boundaryDirectory(otherTwo, file: legacyFile)
    precondition(otherDirectoryOne != otherDirectoryTwo, "Other providers retain account isolation")
    print("current bytes before cache; removed rejects old cache; bounded retry; old Host fallback; wrong target and corrupt bytes rejected; full captured binary target; shared KMP machine/thread/version cache; Codex account metadata compatible; invalid names rejected")
  }
}`;
  const file = join(directory, 'Check.swift'),
    binary = join(directory, 'check');
  await writeFile(file, swift, { mode: 0o600 });
  // Execute the actual shared identity facade, not a fixture reimplementation.
  enterPhase('simulator-select');
  const devices = JSON.parse(
    (await execute('xcrun', ['simctl', 'list', 'devices', 'available', '--json'])).stdout,
  ).devices;
  const selected = process.env.IOS_SIMULATOR_ID;
  if (selected) {
    const device = Object.values(devices)
      .flat()
      .find((value) => value.udid === selected);
    assert.equal(device?.state, 'Booted', 'Selected dedicated simulator must be booted');
    simulator = selected;
    selectedRuntime = Object.entries(devices).find(([, values]) =>
      values.some((value) => value.udid === selected),
    )?.[0];
  } else {
    const runtime = Object.keys(devices)
      .filter((value) => value.includes('iOS'))
      .sort()
      .at(-1);
    assert(runtime, 'iOS simulator runtime is required for real shared identity boundary');
    selectedRuntime = runtime;
    const template = devices[runtime].find((value) => value.deviceTypeIdentifier);
    assert(template, 'Available iOS device type is required');
    ownedSimulator = (
      await execute('xcrun', [
        'simctl',
        'create',
        'AgentHub Resource Boundary ' + nonce,
        template.deviceTypeIdentifier,
        runtime,
      ])
    ).stdout.trim();
    simulator = ownedSimulator;
    // A fresh hosted simulator can spend minutes in LaunchServices migration.
    // This is fixture preparation, separate from the unchanged business deadline.
    const bootDeadline = performance.now() + 10 * 60_000;
    const bootOptions = () => ({
      timeout: Math.max(1, Math.ceil(bootDeadline - performance.now())),
    });
    enterPhase('simulator-boot');
    await execute('xcrun', ['simctl', 'boot', simulator], bootOptions());
    enterPhase('simulator-bootstatus');
    const boot = await execute('xcrun', ['simctl', 'bootstatus', simulator, '-b'], bootOptions());
    await writeFile(join(directory, 'bootstatus.log'), boot.stdout, { mode: 0o600 });
    console.log(boot.stdout.trim().split('\n').slice(-8).join('\n'));
  }
  const sdk = (
    await execute('xcrun', ['--sdk', 'iphonesimulator', '--show-sdk-path'])
  ).stdout.trim();
  selectedSDK = sdk;
  const framework = resolve(
    '.build/native/mobile-core/bin/' + (arm ? 'iosSimulatorArm64' : 'iosX64') + '/debugFramework',
  );
  enterPhase('swift-compile');
  await execute(
    'xcrun',
    [
      'swiftc',
      '-parse-as-library',
      '-sdk',
      sdk,
      '-target',
      (arm ? 'arm64' : 'x86_64') + '-apple-ios17.0-simulator',
      '-F',
      framework,
      '-framework',
      'MobileCore',
      file,
      '-o',
      binary,
    ],
    { timeout: 120000 },
  );
  enterPhase('codesign');
  await execute('codesign', ['--force', '--sign', '-', binary]);
  const spawnEnvironment = { ...process.env, SIMCTL_CHILD_DYLD_FRAMEWORK_PATH: framework };
  const probe = () =>
    execute('xcrun', ['simctl', 'spawn', simulator, binary, '--fixture-health'], {
      timeout: 120_000,
      env: spawnEnvironment,
    });
  enterPhase('simulator-http-ready');
  const readiness = await probe();
  console.log(readiness.stdout.trim());
  enterPhase('reject-wrong-health-marker');
  let markerError;
  healthReply = nonce + '-mismatch';
  try {
    await probe();
  } catch (error) {
    markerError = error;
  } finally {
    healthReply = nonce;
  }
  assert(markerError, 'A wrong health marker must fail fixture readiness');
  assert.equal(markerError.code, 1);
  assert.match(markerError.stderr, /Fixture health marker mismatch/);
  assert.equal(counts.size, 0, 'Health probes must not execute download scenarios');
  const readinessCounts = { connections, healthRequests };
  enterPhase('download-assertions');
  const result = await execute('xcrun', ['simctl', 'spawn', simulator, binary], {
    timeout: 60000,
    env: spawnEnvironment,
  });
  let cancelDeadline;
  try {
    await Promise.race([
      cancelClosed.promise,
      new Promise((_, reject) => {
        cancelDeadline = setTimeout(
          () => reject(new Error('Cancelled download HTTP request stayed open')),
          5000,
        );
      }),
    ]);
  } finally {
    clearTimeout(cancelDeadline);
  }
  assert(
    cancelTransfer.closedEarly,
    'Cancelling the native download must close its incomplete HTTP response',
  );
  assert(cancelTransfer.bytesSent > 0 && cancelTransfer.bytesSent < cancelTransfer.totalBytes);
  assert.equal(counts.get('redirect/v1/attachments'), 1);
  assert.equal(counts.get('cached/v1/attachments/resolve'), 2);
  assert.equal(counts.get('cached/v1/attachments'), 2);
  assert.equal(counts.get('removed/v1/attachments/resolve'), 2);
  assert.equal(counts.get('removed/v1/attachments'), 1);
  assert.equal(counts.get('race/v1/attachments/resolve'), 2);
  assert.equal(counts.get('changing/v1/attachments/resolve'), 2);
  assert.equal(counts.get('changing/v1/attachments'), 2);
  for (const scenario of ['wrongHost', 'wrongThread', 'wrongSource', 'wrongRuntime'])
    assert.equal(counts.get(scenario + '/v1/attachments'), undefined);
  assert.equal(stableResolution, 7);
  assert.equal(counts.get('legacyNil/v1/attachments'), 1);
  assert.equal(counts.get('stable/v1/attachments'), 5);
  assert.equal(counts.get('bad/v1/attachments/resolve'), undefined);
  const report = {
    passed: true,
    fixtureSourceSha256: hash(await readFile(new URL(import.meta.url))),
    generatedSwiftSha256: hash(swift),
    cancelTransfer,
    sharedIdentitySourceSha256: hash(sharedSource),
    actualTargetTypesSha256: hash(targetTypes),
    binaryTargetsChecked: binaryTargets.length,
    isolationCases: 7,
    invalidNamesRejected: 5,
    simulator,
    productionSourceSha256: hash(source),
    discoverySourceSha256: hash(discovery),
    native: result.stdout.trim(),
    counts: Object.fromEntries(counts),
    fixtureReadiness: {
      host: 'passed',
      simulator: readiness.stdout.trim(),
      wrongMarkerRejected: true,
      beforeScenarios: readinessCounts,
      phases,
    },
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
} catch (error) {
  const diagnostic = {
    phase,
    simulator,
    runtime: selectedRuntime,
    sdk: selectedSDK,
    connections,
    healthRequests,
    scenarioCounts: Object.fromEntries(counts),
    phases,
    cancelTransfer,
    proxyEnvironmentPresent: Object.fromEntries(
      [
        'HTTP_PROXY',
        'HTTPS_PROXY',
        'ALL_PROXY',
        'NO_PROXY',
        'http_proxy',
        'https_proxy',
        'all_proxy',
        'no_proxy',
      ].map((key) => [key, Boolean(process.env[key])]),
    ),
    commandStdoutTail: typeof error.stdout === 'string' ? error.stdout.slice(-2500) : undefined,
  };
  try {
    await checkHostHealth();
    diagnostic.hostListenerAfterFailure = 'passed';
  } catch {
    diagnostic.hostListenerAfterFailure = 'failed';
  }
  try {
    const raw = (await execute('scutil', ['--proxy'], { timeout: 5_000 })).stdout;
    diagnostic.hostProxyFlags = Object.fromEntries(
      [
        ...raw.matchAll(
          /^\s*(HTTPEnable|HTTPSEnable|SOCKSEnable|ProxyAutoConfigEnable|ProxyAutoDiscoveryEnable)\s*:\s*([01])\s*$/gm,
        ),
      ].map((match) => [match[1], match[2] === '1']),
    );
    diagnostic.hostProxyMentionsLoopbackBypass = /127\.0\.0\.1|localhost/.test(raw);
  } catch {
    diagnostic.hostProxyFlags = 'unavailable';
  }
  await writeFile(
    join(directory, 'failure-diagnostics.json'),
    JSON.stringify(diagnostic, null, 2),
    { mode: 0o600 },
  );
  console.error('Native fixture failure diagnostics: ' + JSON.stringify(diagnostic));
  throw error;
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  if (ownedSimulator) {
    await execute('xcrun', ['simctl', 'shutdown', ownedSimulator]).catch(() => {});
    await execute('xcrun', ['simctl', 'delete', ownedSimulator]);
  }
}
