import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { promisify } from 'node:util';

const run = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, 'Missing production declaration: ' + marker);
  let depth = 0;
  let opened = false;
  for (let index = start; index < source.length; index++) {
    if (source[index] === '{') {
      depth++;
      opened = true;
    }
    if (source[index] === '}' && --depth === 0 && opened) return source.slice(start, index + 1);
  }
  assert.fail('Unclosed production declaration: ' + marker);
}

test(
  'canonical EasyWork compression preserves run identity and terminal watermarks',
  {
    skip: process.platform !== 'darwin',
  },
  async () => {
    const [helper, models, reading, transport] = await Promise.all(
      ['EasyWorkCompression', 'Models', 'EasyWorkReading', 'EasyWorkTransport'].map((name) =>
        readFile(join(repository, 'apps/ios/Sources/' + name + '.swift'), 'utf8'),
      ),
    );
    await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(repository, '.local/easywork-compression-'));
    let passed = false;
    try {
      const swift = String.raw`import Foundation
import CoreFoundation
import Darwin
enum HostServiceError: Error { case invalidResponse }
enum L10n { static func tr(_ value: String) -> String { value } }
// Unrelated optional payloads stay nil. ChatMessage and the complete
// compression, integer and JSON functions below are production source.
struct RemoteAttachment: Codable, Equatable {}
struct AgentContentPart: Codable, Equatable {}
struct ReadContentProjection: Codable, Equatable {}
struct PlanArtifactSnapshot: Codable, Equatable {}
${declaration(models, 'struct ChatMessage:')}
enum EasyWorkReading {
${declaration(reading, 'static func integer(')}
}
${declaration(transport, 'extension Data {')}
${declaration(transport, 'func easyWorkJSON(')}
${helper}
var count = 0
func expect(_ condition: Bool, _ purpose: String) throws {
  count += 1
  if !condition { throw NSError(domain: "EasyWorkCompression", code: count,
    userInfo: [NSLocalizedDescriptionKey: purpose]) }
}
func state(pending: [[String: Any]] = [], latest: [String: Any]? = nil) -> [String: Any] {
  var value: [String: Any] = ["threadId": "thread", "runId": "run", "branchRevision": 3,
    "cursor": ["source": "protocol", "sequence": 12], "pending": pending]
  if let latest { value["latestEvent"] = latest }
  return value
}
func entry(_ type: String, _ fields: [String: Any] = [:], operation: String? = "op") -> [String: Any] {
  var event: [String: Any] = ["type": type, "traceId": "trace"]
  if let operation { event["compressionOperationId"] = operation }
  event.merge(fields) { _, new in new }
  return ["createdAt": "2026-11-20T00:00:00.000Z", "event": event]
}
func snapshot(_ compression: Any) -> [String: Any] {
  ["thread": ["id": "thread", "branchRevision": 3],
   "activeRun": ["threadId": "thread", "runId": "run", "branchRevision": 3],
   "contextCompressionState": compression]
}
let started = "context_compression_started"
let created = "context_compression_block_created"
let rejected = "context_compression_block_rejected"
let ordinary = ChatMessage(id: "ordinary", role: "assistant", text: "keep original")
func project(_ value: [String: Any], _ messages: [ChatMessage] = [ordinary]) -> [ChatMessage] {
  EasyWorkCompressionProjection.applying(value, threadId: "thread", branchRevision: 3, to: messages)
}
func runCases() throws {
  try expect(project([:]) == [ordinary], "Old peers without optional state keep history")
  let running = snapshot(state(pending: [entry(started)]))
  let rows = project(running)
  try expect(rows.count == 2 && rows[0] == ordinary, "Progress does not replace the original reply")
  try expect(rows[1].status == "inProgress" && rows[1].runId == "run", "Pending belongs to the active run")
  try expect(rows[1].kind == "contextCompaction", "Use existing native compression presentation")
  try expect(project(running, rows) == rows, "Repeated canonical reads do not duplicate progress")
  let operationB = entry(started, ["generationId": "same-generation"], operation: "other")
  let operationA = entry(started, ["generationId": "same-generation"])
  let parallel = project(snapshot(state(pending: [operationA, operationB])))
  try expect(parallel.count == 3 && parallel[1].id != parallel[2].id, "Distinct operations cannot collapse by generation")
  try expect(project(snapshot(state(pending: [entry(started), entry(started)]))) == [ordinary], "Duplicate operation identities cannot acquire UI authority")
  let completed = project(snapshot(state(latest: entry(created, ["blockRef": "checkpoint", "logicalStatus": "installed", "persistenceState": "durable"]))))
  try expect(completed.last?.status == "completed", "Installed checkpoint confirms completion")
  try expect(project(snapshot(state(latest: entry(started))), rows) == [ordinary], "Empty pending watermark never resurrects a retained started event")
  let contradictory = snapshot(state(pending: [entry(started)], latest: entry(created, ["blockRef": "checkpoint"])))
  try expect(project(contradictory) == [ordinary], "One operation cannot be both pending and terminal")
  let fallback = entry(created, ["blockRef": "checkpoint", "logicalStatus": "installed",
    "persistenceState": "durable", "generationStatus": "failed", "installStatus": "structural_fallback"])
  try expect(project(snapshot(state(latest: fallback))).last?.status == "completed", "Structural installation survives failed semantic generation")
  for (fields, expected) in [
    (["blockRef": "checkpoint", "logicalStatus": "failed"], "failed"),
    (["blockRef": "checkpoint", "persistenceState": "degraded"], "failed"),
    (["blockRef": "checkpoint", "resultStatus": "model_failed"], "failed"),
    (["blockRef": "checkpoint", "logicalStatus": "skipped"], "skipped"),
    (["blockRef": "checkpoint", "resultStatus": "skipped"], "skipped"),
    (["blockRef": "checkpoint", "logicalStatus": "future"], "unknown"),
    (["blockRef": "checkpoint", "persistenceState": "future"], "unknown"),
    ([:], "unknown"),
  ] as [([String: Any], String)] {
    try expect(project(snapshot(state(latest: entry(created, fields)))).last?.status == expected,
      "Separate installed, failed, skipped and unsupported created outcome: \(fields)")
  }
  for value in ["failed", "cancelled", "skipped", "future"] {
    try expect(project(snapshot(state(latest: entry(rejected, ["status": value])))).last?.status == (value == "future" ? "unknown" : value),
      "Preserve rejection terminal outcome: \(value)")
  }
  for field in ["logicalStatus", "persistenceState", "resultStatus", "generationStatus"] {
    try expect(project(snapshot(state(latest: entry(created, ["blockRef": "checkpoint", field: true])))).last?.status == "unknown",
      "Malformed optional \(field) is not legacy metadata")
  }
  try expect(project(snapshot(state(latest: entry(rejected, ["status": NSNull()])))).last?.status == "unknown", "Malformed rejection status remains unknown")
  let oldProgress = ChatMessage(id: "legacy", role: "tool", text: "", kind: "contextCompaction", status: "inProgress", runId: "run")
  let otherProgress = ChatMessage(id: "other", role: "tool", text: "", kind: "contextCompaction", status: "inProgress", runId: "other")
  let oldComplete = ChatMessage(id: "complete", role: "tool", text: "", kind: "contextCompaction", status: "completed", runId: "run")
  try expect(project(snapshot(state()), [ordinary, oldProgress, otherProgress, oldComplete]) == [ordinary, otherProgress, oldComplete], "Only clear stale progress from the current run")
  for (key, value) in [("threadId", "other"), ("runId", "other"), ("branchRevision", 4)] as [(String, Any)] {
    var invalid = state(pending: [entry(started)]); invalid[key] = value
    try expect(project(snapshot(invalid)) == [ordinary], "Reject another state identity: \(key)")
  }
  for section in ["thread", "activeRun"] {
    var invalid = running
    var fields = invalid[section] as! [String: Any]; fields[section == "thread" ? "id" : "threadId"] = "other"; invalid[section] = fields
    try expect(project(invalid) == [ordinary], "Validate canonical owner identity: \(section)")
  }
  var noRun = running; noRun.removeValue(forKey: "activeRun")
  try expect(project(noRun, [ordinary, oldComplete]) == [ordinary, oldComplete], "Cold terminal history remains usable without active run")
  for number in [true, -1, 1.5, 9_007_199_254_740_992.0] as [Any] {
    var invalid = state(pending: [entry(started)]); invalid["cursor"] = ["source": "protocol", "sequence": number]
    try expect(project(snapshot(invalid)) == [ordinary], "Reject invalid cursor: \(number)")
    invalid = state(pending: [entry(started)]); invalid["branchRevision"] = number
    try expect(project(snapshot(invalid)) == [ordinary], "Reject invalid branch integer: \(number)")
  }
  var future = state(pending: [entry(started)]); future["optionalFutureField"] = ["version": 27]
  try expect(project(snapshot(future)) == rows, "Additive metadata keeps known semantics")
  var malformed = state(); malformed["pending"] = ["invalid"]
  try expect(project(snapshot(malformed), [ordinary, oldComplete]) == [ordinary, oldComplete], "Malformed optional state preserves ordinary history")
  try expect(project(snapshot(state(pending: [entry(started, ["compressionOperationId": ""])]))) == [ordinary], "Invalid explicit identity never becomes legacy")
  let legacy = project(snapshot(state(pending: [entry(started, ["generationId": "generation"], operation: nil)])))
  try expect(legacy.last?.status == "inProgress", "Generation-only legacy identity remains supported")
  print("EasyWork canonical compression \(count) assertions passed")
}
do { try runCases() }
catch {
  FileHandle.standardError.write(Data("\(error)\n".utf8))
  exit(1)
}
`;
      await writeFile(join(directory, 'input.swift'), swift);
      const inputSha256 = createHash('sha256').update(swift).digest('hex');
      console.log('Native compression input SHA-256:', inputSha256);
      await writeFile(join(directory, 'input.sha256'), inputSha256 + '\n');
      await run(
        'xcrun',
        [
          'swiftc',
          '-module-cache-path',
          join(directory, 'modules'),
          join(directory, 'input.swift'),
          '-o',
          join(directory, 'test'),
        ],
        { timeout: 120000 },
      );
      const result = await run(join(directory, 'test'), [], { timeout: 30000 });
      assert.match(result.stdout, /EasyWork canonical compression [1-9][0-9]* assertions passed/);
      console.log(result.stdout.trim());
      passed = true;
    } finally {
      if (passed) await rm(directory, { recursive: true });
      else console.error('Compression native failure inputs preserved:', directory);
    }
  },
);
