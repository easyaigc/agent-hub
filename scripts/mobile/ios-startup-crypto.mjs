import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Compile the production reader verbatim, with a process-local counted vault.
const source = await readFile('apps/ios/Sources/EasyWorkPairing.swift', 'utf8');
const start = source.indexOf('enum AgentCache {');
const end = source.indexOf('\nextension SessionStore {', start);
assert(start >= 0 && end > start, 'AgentCache extraction boundaries changed');
const directory = await mkdtemp(join(tmpdir(), 'agent-hub-startup-crypto-'));
try {
  const swift = `import Foundation
import CryptoKit
enum HostServiceError: Error { case invalidResponse, message(String) }
enum L10n { static func tr(_ value: String) -> String { value } }
struct SavedSession: Codable { var value: String }
extension Data {
  var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
  init?(base64URL: String) {
    var value = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    value += String(repeating: "=", count: (4 - value.count % 4) % 4)
    self.init(base64Encoded: value)
  }
}
enum CredentialVault {
  static var stored: String?; static var reads = 0
  static func existingToken(_ id: String) throws -> String? { reads += 1; return stored }
  static func set(_ value: String, id: String) throws { stored = value }
}
${source.slice(start, end)}
@main struct Check {
  static func main() throws {
    let pages = try (0..<12).map { try AgentCache.seal("page-\\($0)") }
    CredentialVault.reads = 0
    var reader = AgentCache.ReadSession()
    let plain = try JSONEncoder().encode("plain")
    let decodedPlain = try reader.open(plain, as: String.self)
    precondition(decodedPlain == "plain")
    precondition(CredentialVault.reads == 0, "Plaintext must not read the key")
    for (index, page) in pages.enumerated() {
      let decoded = try reader.open(page, as: String.self)
      precondition(decoded == "page-\\(index)")
    }
    precondition(CredentialVault.reads == 1, "One restore must read the key once")
    var tampered = pages[0]; tampered[tampered.count - 1] ^= 1
    do { _ = try reader.open(tampered, as: String.self); fatalError("Tampered page accepted") } catch { }
    var next = AgentCache.ReadSession()
    let reopened = try next.open(pages[0], as: String.self)
    precondition(reopened == "page-0" && CredentialVault.reads == 2, "A new restore must read again")
    print("Production AgentCache: 12 pages, one key read; tampering rejected; new reader re-reads; plaintext no key read")
  }
}
`;
  const file = join(directory, 'reader.swift');
  const executable = join(directory, 'reader');
  await writeFile(file, swift, { mode: 0o600 });
  execFileSync('xcrun', ['swiftc', '-parse-as-library', file, '-o', executable], {
    stdio: 'inherit',
  });
  execFileSync(executable, { stdio: 'inherit', timeout: 15000 });
} finally {
  await rm(directory, { recursive: true, force: true });
}
