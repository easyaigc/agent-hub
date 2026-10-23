import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { resolve, join } from 'node:path';

// Actual Swift URLSession code against delayed HTTP. No model or phone evidence.
assert.equal(process.platform, 'darwin');
const execute = promisify(execFile);
await mkdir('.local/phone-vpn-fix-20261015', { recursive: true, mode: 0o700 });
const directory = await mkdtemp(resolve('.local/phone-vpn-fix-20261015/transport-'));
const source = await readFile('apps/ios/Sources/CodexDiscovery.swift', 'utf8');
const routes = source.slice(
  source.indexOf('final class CodexHostRoutes:'),
  source.indexOf('enum CodexDiscoveryAPI'),
);
let reads = 0,
  writes = 0,
  cancelledReads = 0;
const server = createServer(async (req, res) => {
  if (req.method === 'POST') {
    writes++;
    for await (const _ of req) {
      /* consume the one submitted body */
    }
    await new Promise((done) => setTimeout(done, 900));
    res.writeHead(200).end('written-once');
  } else {
    reads++;
    if (reads === 1) {
      await new Promise((done) => {
        const timer = setTimeout(done, 8000);
        res.on('close', () => {
          clearTimeout(timer);
          if (!res.writableFinished) cancelledReads++;
          done();
        });
      });
    }
    if (!res.destroyed) res.writeHead(200).end('fresh-history');
  }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
try {
  const swift = `import Foundation
import Network
struct Candidate { var endpoint: String }
struct Connection { var candidates: [Candidate]; var fingerprint: String }
struct Client { var codexDirect: Connection? }
final class PairedHostTrust: NSObject, URLSessionDelegate, @unchecked Sendable {
  init(endpoint: String, fingerprint: String) {}
}
${routes}
@main struct Check {
  static func main() async throws {
    let base = URL(string: "http://127.0.0.1:${server.address().port}")!
    var read = URLRequest(url: base.appendingPathComponent("v1/history")); read.timeoutInterval = 10
    var write = URLRequest(url: base.appendingPathComponent("v1/replies")); write.httpMethod = "POST"; write.httpBody = Data("immutable-intent".utf8)
    let router = CodexHostRoutes()
    let reading = Task { try await router.data(for: read) }
    let writing = Task { try await router.data(for: write) }
    try await Task.sleep(for: .milliseconds(350))
    router.resetReadConnections()
    do { _ = try await reading.value; fatalError("Stalled read survived reset") }
    catch let error as URLError { precondition(error.code == .cancelled) }
    let (body, response) = try await writing.value
    precondition((response as! HTTPURLResponse).statusCode == 200 && String(decoding: body, as: UTF8.self) == "written-once")
    let (fresh, _) = try await router.data(for: read)
    precondition(String(decoding: fresh, as: UTF8.self) == "fresh-history")
    print("stalled read cancelled; in-flight write completed once; fresh connection read succeeded")
  }
}`;
  const file = join(directory, 'Check.swift'),
    binary = join(directory, 'check');
  await writeFile(file, swift, { mode: 0o600 });
  await execute('xcrun', ['swiftc', '-parse-as-library', file, '-o', binary], { timeout: 120000 });
  const native = await execute(binary, [], { timeout: 15000 });
  assert.equal(reads, 2);
  assert.equal(writes, 1);
  assert.equal(cancelledReads, 1);
  const report = {
    passed: true,
    reads,
    writes,
    cancelledReads,
    native: native.stdout.trim(),
    scope: 'actual Swift/Foundation with synthetic HTTP; not physical VPN',
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
