import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, 'Missing actual Swift declaration: ' + marker);
  let depth = 0,
    opened = false;
  for (let index = start; index < source.length; index++) {
    if (source[index] === '{') {
      depth++;
      opened = true;
    }
    if (source[index] === '}' && --depth === 0 && opened) return source.slice(start, index + 1);
  }
  assert.fail('Unclosed actual Swift declaration');
}

test(
  'actual Swift receipt decoder preserves send/query identity, queue fields and unknown outcomes',
  { skip: process.platform !== 'darwin' },
  async (t) => {
    const source = await readFile(
      join(repository, 'apps/ios/Sources/EasyWorkAgentClient.swift'),
      'utf8',
    );
    const models = await readFile(join(repository, 'apps/ios/Sources/Models.swift'), 'utf8');
    const transport = await readFile(
      join(repository, 'apps/ios/Sources/EasyWorkTransport.swift'),
      'utf8',
    );
    await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(repository, '.local/easywork-receipt-offline-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const vectorFile = join(
      repository,
      'packages/protocol/fixtures/mobile/easywork-receipt-v1.json',
    );
    const vectorCases = JSON.parse(await readFile(vectorFile, 'utf8'));
    assert.equal(vectorCases.version, 1);
    const swift = join(directory, 'ReceiptFixture.swift'),
      binary = join(directory, 'receipt-fixture');
    const program = `import Foundation
enum L10n { static func tr(_ text: String) -> String { text } }
${declaration(models, 'struct DeliveryReceipt:')}
${declaration(transport, 'func easyWorkJSON(')}
${declaration(source, 'enum EasyWorkReceiptNormalization {')}
@main struct ReceiptFixture {
  static func main() throws {
    var checks = 0
    func expect(_ value: Bool, _ name: String) throws {
      if !value { throw NSError(domain: "OfflineReceiptFixture", code: 1, userInfo: [NSLocalizedDescriptionKey: name]) }
      checks += 1
    }
    func decode(_ value: [String: Any], _ fallback: String = "uncertain", turn: String? = nil, error: String? = nil) throws -> DeliveryReceipt {
      try EasyWorkReceiptNormalization.decode(value, clientMessageId: "fixture-operation", hostId: "fixture-host", threadId: "fixture-thread", defaultState: fallback, fallbackTurnId: turn, fallbackError: error)
    }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    for state in ["submitted", "queued", "completed", "failed", "uncertain", "cancelled"] {
      let raw: [String: Any] = ["state": state, "runId": "fixture-run", "queueSource": "native", "queuePresent": true, "canExecuteNow": false, "queueSyncError": "fixture-sync-error", "text": "fixture-input", "noticeContext": "historical", "laterTurnId": "fixture-later-run"]
      let sent = try decode(raw, "submitted"), queried = try decode(raw)
      try expect(try encoder.encode(sent) == encoder.encode(queried), "send/query same captured response")
      try expect(queried.turnId == "fixture-run" && queried.state == state, "exact run and outcome")
      try expect(queried.queueSource == "native" && queried.queuePresent == true && queried.canExecuteNow == false && queried.queueSyncError == "fixture-sync-error", "queue metadata retained")
    }
    try expect(try decode(["turnId": "fixture-turn"]).turnId == "fixture-turn", "turnId fallback")
    try expect(try decode(["runId": "fixture-run", "turnId": "fixture-turn"]).turnId == "fixture-run", "same runId precedence as send")
    try expect(try decode([:], "submitted", turn: "fixture-steer-turn").turnId == "fixture-steer-turn", "send-only expected-turn fallback")
    let unknown = try decode([:])
    try expect(unknown.state == "uncertain" && unknown.turnId == nil, "query absence stays unknown without a current-run fallback")
    let failed = try decode(["state": "failed"], error: "fixture-command-error")
    try expect(failed.state == "failed" && failed.error == "fixture-command-error", "outer error retained without changing state")
    try expect(try decode(["state": "failed", "error": "fixture-native-error"], error: "fixture-command-error").error == "fixture-native-error", "native error takes precedence")
    let anchored = try decode(["clientMessageId": "other-operation", "hostId": "other-host", "threadId": "other-thread"])
    try expect(anchored.clientMessageId == "fixture-operation" && anchored.hostId == "fixture-host" && anchored.threadId == "fixture-thread", "captured routing fields retained")
    var rejected = false
    do { _ = try decode(["queuePresent": "invalid-type"]) } catch is DecodingError { rejected = true }
    try expect(rejected, "malformed queue metadata still rejected")
    let vectorRoot = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
    precondition(vectorRoot["version"] as? Int == 1)
    let routing = vectorRoot["routing"] as! [String: String]
    for row in vectorRoot["cases"] as! [[String: Any]] {
      let name = row["name"] as! String, valid = row["valid"] as! Bool
      var value: DeliveryReceipt?, failure: Error?
      do {
        value = try EasyWorkReceiptNormalization.decode(row["raw"] as! [String: Any], clientMessageId: routing["clientMessageId"]!, hostId: routing["hostId"]!, threadId: routing["threadId"]!, defaultState: row["defaultState"] as! String, fallbackTurnId: row["fallbackTurnId"] as? String, fallbackError: row["fallbackError"] as? String)
      } catch { failure = error }
      if !valid { try expect(failure != nil, name); continue }
      guard let value else { throw failure ?? NSError(domain: "ReceiptVectors", code: 1) }
      let actual = try JSONSerialization.jsonObject(with: encoder.encode(value)) as! [String: Any]
      for (key, expected) in row["expected"] as! [String: Any] {
        let lhs = try easyWorkJSON(["value": actual[key] ?? NSNull()]), rhs = try easyWorkJSON(["value": expected])
        guard lhs == rhs else { throw NSError(domain: name + ":" + key, code: 2) }
      }
      try expect(true, name)
    }
    print("{\\"passed\\":true,\\"checks\\":\\(checks)}")
  }
}
`;
    await writeFile(swift, program, { mode: 0o600 });
    const env = Object.fromEntries(
      Object.entries(process.env).filter(([key]) =>
        /^(PATH|HOME|TMPDIR|SDKROOT|DEVELOPER_DIR)$/i.test(key),
      ),
    );
    await run(
      '/usr/bin/swiftc',
      [
        '-parse-as-library',
        '-module-cache-path',
        join(directory, 'module-cache'),
        '-o',
        binary,
        swift,
      ],
      { env, timeout: 30000 },
    );
    const result = JSON.parse((await run(binary, [vectorFile], { env, timeout: 5000 })).stdout);
    assert.deepEqual(result, { passed: true, checks: 26 + vectorCases.cases.length });
  },
);
