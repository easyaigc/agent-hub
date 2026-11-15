import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.platform !== 'darwin') {
  console.log('SKIP: UploadWireOperation native cancellation requires macOS Foundation/KVO');
  process.exit(0);
}

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = await readFile(path.join(root, 'apps/ios/Sources/Uploads.swift'), 'utf8');
const start = source.indexOf('final class UploadWireOperation:');
const end = source.indexOf('\n}\n', start);
assert(start >= 0 && end > start, 'cannot locate UploadWireOperation');
const operation = source.slice(start, end + 2);
console.log(`Uploads.swift sha256=${createHash('sha256').update(source).digest('hex')}`);
console.log(`UploadWireOperation sha256=${createHash('sha256').update(operation).digest('hex')}`);

const swift = String.raw`
import Foundation
import Darwin

enum HostServiceError: Error { case invalidResponse }
${operation}

final class Progress: @unchecked Sendable {
  private let lock = NSLock()
  private var count: Int64 = 0
  func record(_ value: Int64) { lock.withLock { count = max(count, value) } }
  func read() -> Int64 { lock.withLock { count } }
}

@main struct Main {
  static func main() async {
    let url = URL(string: CommandLine.arguments[1])!
    let file = URL(fileURLWithPath: CommandLine.arguments[2])
    let payload = try! Data(contentsOf: file)
    guard payload.count == 16 * 1024 * 1024 else { fatalError("bad payload size") }
    for index in 0..<30 {
      let mode = index % 2 == 0 ? "dataTask" : "fromFile"
      let progress = Progress()
      let config = URLSessionConfiguration.ephemeral
      config.timeoutIntervalForRequest = 60
      let session = URLSession(configuration: config)
      var request = URLRequest(url: url.appendingPathComponent("\(index)"))
      request.httpMethod = "POST"
      if mode == "dataTask" { request.httpBody = payload }
      let operation = UploadWireOperation()
      let task = Task {
        try await operation.run(session: session, request: request, file: mode == "fromFile" ? file : nil, sent: { progress.record($0) })
      }
      let deadline = Date().addingTimeInterval(8)
      while progress.read() <= 0 && Date() < deadline {
        try? await Task.sleep(nanoseconds: 10_000_000)
      }
      let sent = progress.read()
      guard sent > 0 else { fputs("FAIL iteration=\(index + 1) mode=\(mode) no progress\n", stderr); exit(1) }
      let cancelStart = Date()
      task.cancel()
      // A stuck cancellation or continuation must terminate the process, not hang the suite.
      let watchdog = DispatchWorkItem { fputs("FAIL iteration=\(index + 1) mode=\(mode) cancellation timeout\n", stderr); exit(2) }
      DispatchQueue.global().asyncAfter(deadline: .now() + 5, execute: watchdog)
      do {
        _ = try await task.value
        fputs("FAIL iteration=\(index + 1) mode=\(mode) unexpectedly succeeded\n", stderr); exit(1)
      } catch {
        let ns = error as NSError
        guard ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled else {
          fputs("FAIL iteration=\(index + 1) mode=\(mode) unexpected error=\(error)\n", stderr); exit(1)
        }
      }
      watchdog.cancel()
      session.invalidateAndCancel()
      print("PASS iteration=\(index + 1) mode=\(mode) bytes=\(sent) cancel_ms=\(Int(Date().timeIntervalSince(cancelStart) * 1000))")
      fflush(stdout)
      // Keep the process alive briefly to expose late duplicate continuation resumes.
      try? await Task.sleep(nanoseconds: 50_000_000)
    }
    try? await Task.sleep(nanoseconds: 500_000_000)
    print("PASS native cancellation 30/30")
  }
}
`;

function run(command, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit' });
    let timedOut = false;
    let killTimer;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
      killTimer = setTimeout(() => child.kill('SIGKILL'), 2000);
    }, timeoutMs);
    child.on('error', reject);
    child.on('close', (code, signal) => {
      clearTimeout(timer);
      clearTimeout(killTimer);
      if (code === 0 && !timedOut) resolve();
      else
        reject(new Error(`${command} failed: code=${code} signal=${signal} timeout=${timedOut}`));
    });
  });
}

const directory = await mkdtemp(path.join(os.tmpdir(), 'upload-wire-cancel-'));
const sockets = new Set();
const server = http.createServer((request) => {
  // Deliberately do not consume the body or respond: the upload remains active.
  request.pause();
});
server.on('connection', (socket) => {
  sockets.add(socket);
  socket.on('close', () => sockets.delete(socket));
});
try {
  const swiftFile = path.join(directory, 'main.swift');
  const binary = path.join(directory, 'wire-cancel');
  const payloadFile = path.join(directory, 'payload');
  await writeFile(swiftFile, swift);
  await writeFile(payloadFile, Buffer.alloc(16 * 1024 * 1024, 0x5a));
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  await run('xcrun', ['swiftc', '-parse-as-library', swiftFile, '-o', binary], 60000);
  await run(binary, [`http://127.0.0.1:${server.address().port}`, payloadFile], 180000);
} finally {
  for (const socket of sockets) socket.destroy();
  if (server.listening)
    await new Promise((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    );
  await rm(directory, { recursive: true, force: true });
}
