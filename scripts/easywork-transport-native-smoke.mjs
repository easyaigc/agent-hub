import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import { promisify } from 'node:util';

// Transport boundary evidence only: real Foundation + pinned TLS + WebSocket
// frames. This does not claim a physical phone, encrypted protocol or model run.
assert.equal(process.platform, 'darwin', 'Requires macOS Foundation and Swift');
const execute = promisify(execFile);
const environment = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR || '/Applications/Xcode.app/Contents/Developer',
};
await mkdir('.local/easywork-transport', { recursive: true });
const directory = await mkdtemp(path.resolve('.local/easywork-transport/native-'));
const sockets = new Set();
let server;
function frame(payload) {
  const data = Buffer.from(payload);
  if (data.length < 126) return Buffer.concat([Buffer.from([0x81, data.length]), data]);
  const header = Buffer.alloc(10);
  header[0] = 0x81;
  header[1] = 127;
  header.writeBigUInt64BE(BigInt(data.length), 2);
  return Buffer.concat([header, data]);
}
try {
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=127.0.0.1',
      '-keyout',
      path.join(directory, 'key.pem'),
      '-out',
      path.join(directory, 'cert.pem'),
    ],
    { stdio: 'pipe' },
  );
  const certificate = await readFile(path.join(directory, 'cert.pem'));
  const fingerprint = createHash('sha256')
    .update(new X509Certificate(certificate).raw)
    .digest('hex');
  server = https.createServer({
    key: await readFile(path.join(directory, 'key.pem')),
    cert: certificate,
  });
  server.on('upgrade', (request, socket) => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
    const accept = createHash('sha1')
      .update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
      .digest('base64');
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
        accept +
        '\r\n\r\n',
    );
    const oversized =
      new URL(request.url, 'https://localhost').searchParams.get('bindingId') === 'oversized';
    socket.write(frame('x'.repeat(oversized ? 32 * 1024 * 1024 + 1 : 2 * 1024 * 1024)));
    if (!oversized) socket.write(frame('next-response'));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const endpoint = `https://127.0.0.1:${server.address().port}`;
  const source = await readFile('apps/ios/Sources/EasyWorkTransport.swift', 'utf8');
  const declarations = await readFile('apps/ios/Sources/AgentClient.swift', 'utf8');
  const candidate = declarations.slice(
    declarations.indexOf('struct AgentConnectionCandidate:'),
    declarations.indexOf('enum AgentConnectionPreference:'),
  );
  const trust = source.slice(
    source.indexOf('final class PairedHostTrust:'),
    source.indexOf('/// First connection'),
  );
  const transport = source.slice(
    source.indexOf('protocol AgentTransport:'),
    source.indexOf('actor RelayAgentTransport:'),
  );
  const swift = `import Foundation
import CryptoKit
import Security
${candidate}
enum HostServiceError: Error { case invalidAddress, invalidResponse }
enum HostService {
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
${trust}
${transport}
@main struct Check {
  static func main() async throws {
    let endpoint = CommandLine.arguments[1], pin = CommandLine.arguments[2]
    let delegate = PairedHostTrust(endpoint: endpoint, fingerprint: pin)
    let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
    let raw = session.webSocketTask(with: URL(string: endpoint.replacingOccurrences(of: "https:", with: "wss:") + "/channel?bindingId=baseline")!)
    raw.resume()
    do { _ = try await raw.receive(); fatalError("The baseline must reproduce the oversized history failure") }
    catch { precondition((error as NSError).domain == NSPOSIXErrorDomain && (error as NSError).code == 40, "Unexpected baseline failure: \\(error)") }
    raw.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel()
    let candidate = AgentConnectionCandidate(id: "fixture", transportId: "lan-wss", endpoint: endpoint, peerFingerprint: pin)
    let fixed = try LANAgentTransport(candidate: candidate, bindingId: "fixed")
    let large = try await fixed.receive()
    precondition(large == Data(repeating: 120, count: 2 * 1024 * 1024))
    let next = try await fixed.receive()
    precondition(next == Data("next-response".utf8), "The large history must not break the next request")
    await fixed.close()
    let bounded = try LANAgentTransport(candidate: candidate, bindingId: "oversized")
    do { _ = try await bounded.receive(); fatalError("The receive limit must remain finite") }
    catch { precondition((error as NSError).domain == NSPOSIXErrorDomain && (error as NSError).code == 40, "Unexpected boundary failure: \\(error)") }
    await bounded.close()
    print("Foundation baseline oversize reproduced; actual LAN transport received 2 MiB and next response; 32 MiB bound enforced")
  }
}
`;
  const file = path.join(directory, 'Transport.swift');
  const binary = path.join(directory, 'transport');
  await writeFile(file, swift);
  await execute(
    'xcrun',
    ['swiftc', '-parse-as-library', file, 'apps/ios/Sources/Localization.swift', '-o', binary],
    {
      env: environment,
      timeout: 60000,
    },
  );
  const result = await execute(binary, [endpoint, fingerprint], {
    env: environment,
    timeout: 30000,
  });
  console.log(result.stdout.trim());
} finally {
  for (const socket of sockets) socket.destroy();
  if (server) await new Promise((resolve) => server.close(resolve));
  await rm(directory, { recursive: true, force: true });
}
