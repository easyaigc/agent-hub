import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { test } from 'node:test';
const run = promisify(execFile);
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, marker);
  let depth = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw new Error(`Unclosed declaration: ${marker}`);
}
test(
  'authenticated production candidate parsing, merging and stale adoption guards',
  { skip: process.platform !== 'darwin' },
  async (t) => {
    const source = async (name) => readFile(`apps/ios/Sources/${name}.swift`, 'utf8');
    const [client, easy, discovery, host, transport, models, failures] = await Promise.all(
      [
        'AgentClient',
        'EasyWorkAgentClient',
        'EasyWorkDiscovery',
        'HostService',
        'EasyWorkTransport',
        'NotificationModels',
        'OperationFailure',
      ].map(source),
    );
    const dir = await mkdtemp(path.join(os.tmpdir(), 'easywork-candidates-'));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const decl = (s, m) => declaration(s, m);
    const swift = `import Foundation
import Darwin
import CryptoKit
enum L10n { static func tr(_ s: String) -> String { s } }
enum HostServiceError: Error { case invalidAddress, invalidResponse, wrongHost, message(String) }
enum HostService {
${decl(host, '  static func baseURL(')}
static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
${decl(failures, 'struct ConnectionAuthorizationIdentity<')}
${decl(models, 'struct AgentAccount:')}
${decl(models, 'struct AgentRuntimeTarget:')}
${decl(client, 'struct AgentConnectionCandidate:')}
${decl(client, 'enum AgentConnectionPreference:')}
${decl(client, 'struct AgentConnection:')}
${decl(client, 'struct AgentConnectionMetadata:')}
${decl(transport, 'struct EasyWorkTarget:')}
${decl(transport, 'func easyWorkJSON(')}
${decl(discovery, 'struct EasyWorkEndpointHint:')}
enum EasyWorkPairing {
${decl(discovery, '  static func localEndpoint(')}
${decl(discovery, '  static func directCandidates(')}
}
${decl(easy, 'enum EasyWorkAuthenticatedConnectionCandidates {')}
@main struct Check {
 static func main() throws {
  let account = AgentAccount(authority: "https://fixture.invalid", subject: "fixture")
  let target = EasyWorkTarget(account: account, desktopDeviceId: "desktop", workspaceId: "workspace", runtimeId: "runtime")
  let pin = String(repeating: "a", count: 64)
  func route(_ endpoint: String, _ pin: String) -> [String: Any] { ["transportId":"lan-wss", "endpoint":endpoint, "peerFingerprint":pin] }
  func read(_ rows: [[String: Any]], _ t: EasyWorkTarget? = nil) throws -> [AgentConnectionCandidate]? { try EasyWorkAuthenticatedConnectionCandidates.read(["target":(t ?? target).dictionary, "connectionCandidates":rows], target: target) }
  func reject(_ rows: [[String: Any]], _ t: EasyWorkTarget? = nil) { do { _ = try read(rows, t); fatalError("invalid candidates accepted") } catch {} }
  let absent = try EasyWorkAuthenticatedConnectionCandidates.read([:], target: target); precondition(absent == nil)
  let empty = try read([]); precondition(empty == [])
  var wrong = target; wrong.workspaceId = "other"; reject([], wrong)
  let valid = route("https://192.168.1.5:443/", pin)
  for invalid in ["https://8.8.8.8", "https://user@192.168.1.2", "https://192.168.1.2/path", "http://192.168.1.2"] { reject([valid, route(invalid, pin)]) }
  reject([route("https://192.168.1.2", "bad")])
  reject([valid, route("https://192.168.1.5", String(repeating: "b", count: 64))])
  let future = try read([["transportId":"future", "endpoint":"https://8.8.8.8"], valid]); precondition(future?.count == 1)
  reject(Array(repeating: valid, count: 33))
  let parsed = try read([valid])!; precondition(parsed[0].endpoint == "https://192.168.1.5")
  let old = try EasyWorkPairing.directCandidates(["https://192.168.1.6"], fingerprint: pin)[0]
  let relay = AgentConnectionCandidate(id:"relay", transportId:"relay-http", endpoint:"https://fixture.invalid", peerFingerprint:nil)
  let merged = EasyWorkAuthenticatedConnectionCandidates.merging(parsed, into:[old,relay])
  precondition(merged.count == 3 && merged.contains(old) && merged.contains(relay))
  let many = (0..<40).map { AgentConnectionCandidate(id:"old-\\($0)", transportId:"lan-wss", endpoint:"https://192.168.2.\\($0)", peerFingerprint:pin) }
  let bounded = EasyWorkAuthenticatedConnectionCandidates.merging(parsed, into:many + [relay]); precondition(bounded.count == 33 && bounded.contains(relay))
  let rt = AgentRuntimeTarget(agentId:"easywork", hostId:"desktop", workspaceId:"workspace", runtimeId:"runtime", account:account)
  let original = AgentConnection(target:rt, candidates:[old,relay], preference:.automatic, credentialRef:"fixture")
  var updated = original; updated.candidates = merged
  let metadata = AgentConnectionMetadata(title:nil, updatedConnection:updated)
  precondition(metadata.connectionUpdate(captured:original,current:original) == updated)
  precondition(AgentConnectionMetadata(title:nil).connectionUpdate(captured:original,current:original) == nil)
  var concurrent = original; concurrent.candidates.append(parsed[0]); precondition(metadata.connectionUpdate(captured:original,current:concurrent) == nil)
  concurrent = original; concurrent.preference = .relayOnly; precondition(metadata.connectionUpdate(captured:original,current:concurrent) == nil)
  concurrent = original; concurrent.credentialRef = "other"; precondition(metadata.connectionUpdate(captured:original,current:concurrent) == nil)
  concurrent = original; concurrent.target.workspaceId = "other"; precondition(metadata.connectionUpdate(captured:original,current:concurrent) == nil)
  for variant in 0..<3 { var bad = updated; if variant == 0 { bad.target.workspaceId = "other" }; if variant == 1 { bad.credentialRef = "other" }; if variant == 2 { bad.preference = .lanOnly }; precondition(AgentConnectionMetadata(title:nil, updatedConnection:bad).connectionUpdate(captured:original,current:original) == nil) }
  print("candidate boundary passed")
 }
}`;
    const file = path.join(dir, 'Check.swift'),
      bin = path.join(dir, 'check');
    await writeFile(file, swift, { mode: 0o600 });
    const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
    await run(
      '/usr/bin/xcrun',
      [
        'swiftc',
        '-parse-as-library',
        '-module-cache-path',
        path.join(dir, 'cache'),
        file,
        '-o',
        bin,
      ],
      { env, timeout: 60000 },
    );
    const result = await run(bin, [], { env, timeout: 5000 });
    assert.match(result.stdout, /candidate boundary passed/);
  },
);
