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
const execute = promisify(execFile);
await mkdir('.local/attachment-download-native', { recursive: true, mode: 0o700 });
const directory = await mkdtemp(resolve('.local/attachment-download-native/run-'));
const source = await readFile('apps/ios/Sources/HostService.swift', 'utf8');
const models = await readFile('apps/ios/Sources/Models.swift', 'utf8');
const methods = source.slice(
  source.indexOf('  static func baseURL('),
  source.indexOf('  static func nextTurnSuggestion('),
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
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const send = (code, value) =>
    res.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
  assert.equal(req.headers.authorization, 'Bearer synthetic-token');
  assert([nonce, nonce + '-other'].includes(url.searchParams.get('hostId')));
  const id = url.searchParams.get('id');
  const scenario = id?.split('_')[0];
  const key = scenario + url.pathname;
  counts.set(key, (counts.get(key) ?? 0) + 1);
  if (url.pathname === '/v1/attachments/resolve') {
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
    const content = `${scenario}-version-${n}`;
    return send(200, {
      hostId: scenario === 'wrongHost' ? 'other-host' : nonce,
      threadId: 'thread',
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
  const content =
    scenario === 'legacy' || scenario === 'legacyNil'
      ? 'original'
      : `${scenario}-version-${id.split('_').at(-1)}`;
  res.writeHead(200).end(scenario === 'corrupt' ? 'invalid-bytes' : content);
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
try {
  const swift = `import Foundation
import CryptoKit
import MobileCore
enum L10n { static func tr(_ text: String) -> String { text } }
${errors}
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
  func data(for request: URLRequest, maxBytes: Int? = nil) async throws -> (Data, URLResponse) { try await URLSession.shared.data(for: request) }
}
struct HostService {
${methods}
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
  static func boundaryDirectory(_ client: Client, file: RemoteAttachment) throws -> URL { try resourceDirectory(client, file: file) }
${downloads}
}
@main struct Check {
  static func main() async throws {
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "runtime")
    let client = Client(id: "${nonce}", endpoint: "http://127.0.0.1:${server.address().port}", readingTarget: target)
    let scenarios = ["cached", "removed", "race", "changing", "legacy", "wrongHost", "wrongThread", "wrongSource", "wrongRuntime", "corrupt"]
    var ownedScopes = Set<URL>()
    defer { for scope in ownedScopes { try? FileManager.default.removeItem(at: scope) } }
    func remember(_ client: Client, _ file: RemoteAttachment) throws {
      ownedScopes.insert(try HostService.boundaryDirectory(client, file: file).deletingLastPathComponent())
    }
    for scenario in scenarios {
      let file = RemoteAttachment(id: "\\(scenario)_${nonce}_original", hostId: client.id, threadId: "thread", name: "report.txt", mediaType: "text/plain", sizeBytes: 8, sha256: HostService.hash(Data("original".utf8)))
      try remember(client, file)
      do {
        let url = try await HostService.download(client, file: file)
        let body = try String(contentsOf: url, encoding: .utf8)
        switch scenario {
        case "cached":
          precondition(body == "cached-version-1")
          let refreshed = try await HostService.download(client, file: file)
          let refreshedBody = try String(contentsOf: refreshed, encoding: .utf8)
          precondition(refreshedBody == "cached-version-2")
          let originalBody = try String(contentsOf: url, encoding: .utf8)
          precondition(url != refreshed && originalBody == "cached-version-1", "An open old version must remain immutable")
        case "race": precondition(body == "race-version-2")
        case "removed":
          precondition(body == "removed-version-1")
          do { _ = try await HostService.download(client, file: file); fatalError("Removed file reused cached bytes") }
          catch HostServiceError.rejected(410, _) {}
        case "legacy": precondition(body == "original")
        default: fatalError("Unexpected success: \\(scenario)")
        }
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
      let url = try await HostService.download(scoped, file: file)
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
  const devices = JSON.parse(
    (await execute('xcrun', ['simctl', 'list', 'devices', 'available', '--json'])).stdout,
  ).devices;
  const selected = process.env.IOS_SIMULATOR_ID;
  let simulator;
  if (selected) {
    const device = Object.values(devices)
      .flat()
      .find((value) => value.udid === selected);
    assert.equal(device?.state, 'Booted', 'Selected dedicated simulator must be booted');
    simulator = selected;
  } else {
    const runtime = Object.keys(devices)
      .filter((value) => value.includes('iOS'))
      .sort()
      .at(-1);
    assert(runtime, 'iOS simulator runtime is required for real shared identity boundary');
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
    await execute('xcrun', ['simctl', 'boot', simulator]);
    await execute('xcrun', ['simctl', 'bootstatus', simulator, '-b'], { timeout: 120000 });
  }
  const arm = process.arch === 'arm64';
  await execute(
    process.execPath,
    [
      'scripts/mobile/gradle.mjs',
      ':mobile-core:linkDebugFramework' + (arm ? 'IosSimulatorArm64' : 'IosX64'),
    ],
    { timeout: 180000 },
  );
  const sdk = (
    await execute('xcrun', ['--sdk', 'iphonesimulator', '--show-sdk-path'])
  ).stdout.trim();
  const framework = resolve(
    '.build/native/mobile-core/bin/' + (arm ? 'iosSimulatorArm64' : 'iosX64') + '/debugFramework',
  );
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
  await execute('codesign', ['--force', '--sign', '-', binary]);
  const result = await execute('xcrun', ['simctl', 'spawn', simulator, binary], {
    timeout: 60000,
    env: { ...process.env, SIMCTL_CHILD_DYLD_FRAMEWORK_PATH: framework },
  });
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
    sharedIdentitySourceSha256: hash(sharedSource),
    actualTargetTypesSha256: hash(targetTypes),
    binaryTargetsChecked: binaryTargets.length,
    isolationCases: 7,
    invalidNamesRejected: 5,
    simulator,
    productionSourceSha256: hash(source),
    native: result.stdout.trim(),
    counts: Object.fromEntries(counts),
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  if (ownedSimulator) {
    await execute('xcrun', ['simctl', 'shutdown', ownedSimulator]).catch(() => {});
    await execute('xcrun', ['simctl', 'delete', ownedSimulator]);
  }
}
