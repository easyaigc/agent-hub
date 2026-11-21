import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('EasyWork remote cancellation boundary skipped: macOS Swift required.');
  process.exit(0);
}

function section(text, start, end) {
  const from = text.indexOf(start);
  assert(from >= 0 && text.indexOf(start, from + start.length) < 0, start);
  const to = text.indexOf(end, from + start.length);
  assert(to > from, end);
  return text.slice(from, to);
}

const uploads = await readFile('apps/ios/Sources/Uploads.swift', 'utf8');
const reading = await readFile('apps/ios/Sources/EasyWorkReading.swift', 'utf8');
const transport = await readFile('apps/ios/Sources/EasyWorkTransport.swift', 'utf8');
const notifications = await readFile('apps/ios/Sources/NotificationModels.swift', 'utf8');
const baselineIndex = process.argv.indexOf('--baseline-source');
const implementation =
  baselineIndex < 0 ? uploads : await readFile(process.argv[baselineIndex + 1], 'utf8');
const declarations = section(uploads, 'struct UploadManifest:', 'enum UploadRequestContext {');
const validation = section(
  uploads,
  'enum EasyWorkUploadValidation {',
  '\nextension EasyWorkAgentClient {',
);
const identity = section(uploads, 'enum EasyWorkCapturedUploadIdentity {', '/// Preserve EasyWork');
const request = section(
  implementation,
  '  func uploadRequest(_ client: Client, manifest m:',
  '\n}\n\n#if DEBUG',
);
const stop = section(
  implementation,
  '  private func stop(_ client:',
  '  private func call(_ client:',
);
const target = section(transport, 'struct EasyWorkTarget:', 'struct EasyWorkBinding:');
const account = notifications.match(/^struct AgentAccount:.*$/m)?.[0];
assert(account);
const integer = section(
  reading,
  '  static func integer(_ value: Any?) throws -> Int {',
  '  static func position(',
);

const harness = String.raw`
import Foundation
import CoreFoundation
import CryptoKit
import Darwin
enum HostServiceError: Error { case invalidResponse, wrongHost, unavailable, message(String), rejected(Int, String) }
enum L10n { static func tr(_ text: String) -> String { text } }
enum HostService { static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() } }
extension Data { var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } }
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]) }
${account}
${target}
${declarations}
struct Client { let id: String }
struct DesktopCreationFile { let id: String; let name: String; let mediaType: String; let sizeBytes: Int; let sha256: String }
enum UploadRequestContext { @TaskLocal static var capturedFile: DesktopCreationFile? = nil }
enum EasyWorkAttachmentUpload { static func displayName(_ value: String) -> String { value } }
enum EasyWorkReading {
${integer}
}
${identity}
${validation}

actor Channel {
  let target = EasyWorkTarget(account: AgentAccount(authority: "https://acceptance.invalid", subject: "synthetic"), desktopDeviceId: "desktop", workspaceId: "workspace", runtimeId: "local")
  let mobileDeviceId = "mobile"
  let mode: String
  var status: String
  var calls: [(String, String)] = []
  init(_ mode: String) { self.mode = mode; status = mode == "already-cancelled" ? "cancelled" : mode == "already-completed" ? "completed" : "transferring" }
  func state() -> String { status }
  func methods() -> [String] { calls.map { $0.0 } }
  func cancelIDs() -> [String] { calls.filter { $0.0 == "resource/transfer-cancel" }.map { $0.1 } }
  func request(_ method: String, _ params: [String: Any], id: String = UUID().uuidString) async throws -> Data {
    calls.append((method, id))
    if method == "runtime/context" {
      var value = target.dictionary
      if mode == "wrong-target" { value["workspaceId"] = "different" }
      return try easyWorkJSON(["target": value, "capabilities": mode == "old-capability" ? [] : ["file-transfers"]])
    }
    check(params["transferId"] as? String == manifest.id, "wrong original transfer ID")
    if method == "resource/transfer-cancel" {
      check(id == "cancel-" + manifest.id, "unstable cancellation ID")
      status = mode == "completion-race" ? "completed" : "cancelled"
      if mode == "unknown-cancel" { throw URLError(.networkConnectionLost) }
    } else if method == "resource/upload-complete" {
      check(status == "completed" && id == "complete-" + manifest.id, "invalid completion reconciliation")
    } else { check(method == "resource/upload-status", "Unexpected write: \(method)") }
    var record: [String: Any] = ["transferId": manifest.id, "threadId": "thread", "mobileDeviceId": "mobile", "direction": "mobile-to-desktop", "purpose": "attachment", "name": manifest.name, "contentType": manifest.mediaType, "size": manifest.sizeBytes, "expectedSha256": manifest.sha256, "transferredBytes": status == "completed" ? 10 : 4, "status": status]
    if method == "resource/transfer-cancel" {
      if mode == "wrong-device" { record["mobileDeviceId"] = "different" }
      if mode == "wrong-file" { record["transferId"] = "different" }
      if mode == "wrong-hash" { record["expectedSha256"] = String(repeating: "b", count: 64) }
      if mode == "wrong-state" { record["status"] = "transferring" }
    }
    var response: [String: Any] = ["transfer": record]
    if status == "completed" {
      record["sha256"] = manifest.sha256; record["resourceId"] = "resource"; response["transfer"] = record
      if method == "resource/upload-complete" { response["attachment"] = ["id": manifest.id, "resourceId": "resource", "name": manifest.name, "contentType": manifest.mediaType, "size": 10, "sha256": manifest.sha256] }
    }
    return try easyWorkJSON(response)
  }
}
struct EasyWorkAgentClient {
  let channel: Channel
  func session(_ client: Client) async throws -> Channel { channel }
${request}
}
struct StopController {
  let adapter: EasyWorkAgentClient
  func run() async throws -> UploadReceipt { try await stop(client, manifest: manifest) }
  private func call(_ client: Client, manifest: UploadManifest, action: UploadAction, bytes: Data? = nil) async throws -> UploadReceipt {
    try await adapter.uploadRequest(client, manifest: manifest, action: action, bytes: bytes, sent: nil)
  }
${stop}
}
let client = Client(id: "host")
let manifest = UploadManifest(hostId: "host", id: "F1617651-3173-4A18-A2E5-15775DE40DC1", name: "cancel.txt", mediaType: "text/plain", sizeBytes: 10, sha256: String(repeating: "a", count: 64), purpose: "attachment", threadId: "thread", creationId: nil, attachmentId: nil)
func fail(_ reason: String) -> Never { FileHandle.standardError.write(Data(("FAIL " + reason + "\n").utf8)); Darwin.exit(1) }
func check(_ value: Bool, _ reason: String) { if !value { fail(reason) } }
func expectError(_ mode: String) async throws {
  let channel = Channel(mode), controller = StopController(adapter: EasyWorkAgentClient(channel: channel))
  do { _ = try await controller.run(); fail("Accepted invalid \(mode)") }
  catch HostServiceError.wrongHost { check(mode == "wrong-target", "wrong error category") }
  catch HostServiceError.invalidResponse { check(["wrong-device", "wrong-file", "wrong-hash", "wrong-state"].contains(mode), "wrong error category") }
  catch HostServiceError.message { check(mode == "old-capability", "wrong error category") }
  if ["old-capability", "wrong-target"].contains(mode) { check(await channel.cancelIDs().isEmpty, "unsupported or wrong-target cancel write") }
  print("PASS \(mode)")
}
@main struct Main {
  static func main() async throws {
    for mode in ["active", "already-cancelled", "already-completed", "completion-race"] {
      let channel = Channel(mode), controller = StopController(adapter: EasyWorkAgentClient(channel: channel))
      let receipt = try await controller.run(); try receipt.validate(manifest)
      let completed = ["already-completed", "completion-race"].contains(mode)
      check(receipt.phase == (completed ? "completed" : "cancelled"), "wrong terminal phase")
      check(await channel.state() == receipt.phase, "local stop did not cancel computer staging")
      let calls = await channel.methods()
      check(!calls.contains("resource/upload") && !calls.contains("resource/upload-chunk"), "replayed upload bytes or prepare")
      if mode.hasPrefix("already-") { check(await channel.cancelIDs().isEmpty, "repeated terminal cancel") }
      if mode == "active" {
        check(receipt.committedBytes == 4, "lost original committed offset")
        let second = try await controller.run(); check(second.phase == "cancelled", "terminal recovery changed")
        check(await channel.cancelIDs().count == 1, "repeated completed cancel")
      }
      print("PASS \(mode)")
    }
    for mode in ["old-capability", "wrong-target", "wrong-device", "wrong-file", "wrong-hash", "wrong-state"] { try await expectError(mode) }
    let channel = Channel("unknown-cancel"), controller = StopController(adapter: EasyWorkAgentClient(channel: channel))
    do { _ = try await controller.run(); fail("Unknown cancel reported as success") }
    catch let error as URLError { check(error.code == .networkConnectionLost, "unknown result replaced") }
    let reconciled = try await controller.run()
    check(reconciled.phase == "cancelled" && reconciled.committedBytes == 4, "original status not recovered")
    check(await channel.cancelIDs() == ["cancel-" + manifest.id], "unknown cancel republished")
    check(await channel.methods() == ["resource/upload-status", "runtime/context", "resource/transfer-cancel", "resource/upload-status"], "unexpected writes during recovery")
    print("PASS unknown-cancel-original-status-only")
    print("Eleven production cancel/status boundaries passed; controlled channel, no real Host or model.")
  }
}
`;

const directory = await mkdtemp(join(tmpdir(), 'easywork-upload-cancel-'));
try {
  const source = join(directory, 'main.swift');
  const executable = join(directory, 'boundary');
  await writeFile(source, harness);
  execFileSync(
    'xcrun',
    ['swiftc', '-parse-as-library', '-swift-version', '5', source, '-o', executable],
    {
      stdio: 'inherit',
      env: { ...process.env, CLANG_MODULE_CACHE_PATH: join(directory, 'module-cache') },
    },
  );
  execFileSync(executable, [], { stdio: 'inherit', timeout: 30_000 });
} finally {
  await rm(directory, { recursive: true, force: true });
}
