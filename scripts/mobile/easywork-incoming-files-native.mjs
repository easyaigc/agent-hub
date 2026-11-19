import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, basename } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('EasyWork incoming files native smoke skipped: macOS Swift required.');
  process.exit(0);
}
const evidence = process.env.EASYWORK_INCOMING_NATIVE_EVIDENCE_DIR
  ? resolve(process.env.EASYWORK_INCOMING_NATIVE_EVIDENCE_DIR)
  : undefined;
if (evidence) await mkdir(evidence, { recursive: true });
const paths = [
  'apps/ios/Sources/IncomingFileTransfers.swift',
  'apps/ios/Sources/HostService.swift',
  'apps/ios/Sources/EasyWorkReading.swift',
  'apps/ios/Sources/EasyWorkTransport.swift',
];
const sources = await Promise.all(paths.map((p) => readFile(p, 'utf8')));
function section(s, a, b) {
  const i = s.indexOf(a),
    j = s.indexOf(b, i + a.length);
  if (i < 0 || j < 0 || s.indexOf(a, i + a.length) >= 0) throw Error(`Extraction failed: ${a}`);
  return s.slice(i, j);
}
const harness = String.raw`
import Foundation
import CoreFoundation
import CryptoKit
import Darwin
enum HostServiceError: Error { case invalidResponse, message(String) }
enum L10n { static func tr(_ s: String) -> String { s } }
${section(sources[1], 'struct FileDownloadProgress:', '// Opt-in metadata')}
enum EasyWorkReading {
${section(sources[2], '  static func integer(_ value: Any?) throws -> Int {', '  static func position(')}
}
${section(sources[3], 'extension Data {', '\nfunc easyWorkJSON')}
${sources[0]}
func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
func record(_ data: Data, status: String = "waiting") -> [String: Any] {
  ["purpose":"transfer", "direction":"desktop-to-mobile", "transferId":"original-transfer", "resourceId":"original-resource", "mobileDeviceId":"device", "name":"test.bin", "contentType":"application/octet-stream", "sha256":digest(data), "status":status, "createdAt":"2026-01-01", "size":data.count, "transferredBytes": status == "completed" ? data.count : 0]
}
func make(_ r: [String: Any]) throws -> IncomingComputerFile { try EasyWorkIncomingFiles.record(r, hostId: "host", deviceId: "device") }
func change(_ r: [String: Any], _ k: String, _ v: Any) -> [String: Any] { var x = r; x[k] = v; return x }
func check(_ b: Bool, _ message: String) { if !b { fatalError(message) } }
final class Fixture {
 var chunks = 0, acks = 0
 var mutation: ((inout [String: Any]) -> Void)?
 var failAck = false
 let data: Data
 init(_ data: Data) { self.data = data }
 func request(_ method: String, _ args: [String: Any], _ operation: String?) async throws -> [String: Any] {
  check(args["transferId"] as? String == "original-transfer" && args["resourceId"] as? String == "original-resource", "original IDs")
  if method == "resource/download-complete" {
   acks += 1; check(operation != nil, "operation ID")
   if failAck { throw HostServiceError.invalidResponse }
   return ["transfer": record(data, status: "completed")]
  }
  check(method == "resource/download" && operation == nil, "named request")
  chunks += 1
  let offset = args["offset"] as! Int, length = args["length"] as! Int
  let end = min(offset + length, data.count)
  var response: [String: Any] = ["resourceId":"original-resource", "transferId":"original-transfer", "offset":offset, "nextOffset":end, "dataBase64":data.subdata(in: offset..<end).base64URL, "complete":end == data.count]
  mutation?(&response)
  return response
 }
}
@main struct Main {
 static func main() async throws {
  let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
  let bytes = Data((0..<400_001).map { UInt8($0 % 251) })
  var cases = 0
  func directory(_ label: String) -> URL { root.appendingPathComponent(label, isDirectory: true) }
  func rejectRecord(_ label: String, _ r: [String: Any]) { do { _ = try make(r); fatalError("accepted \(label)") } catch { cases += 1 } }
  let base = record(bytes)
  for (k,v) in [("mobileDeviceId", "other"), ("threadId", "thread"), ("purpose", "attachment"), ("direction", "mobile-to-desktop"), ("name", "../bad"), ("resourceId", "../bad"), ("sha256", "BAD")] { rejectRecord(k, change(base,k,v)) }
  for v: Any in [true, -1, 1.5] { rejectRecord("size", change(base,"size",v)); rejectRecord("transferredBytes", change(base,"transferredBytes",v)) }
  rejectRecord("completed incomplete", change(base,"status","completed"))
  let file = try make(base), fixture = Fixture(bytes)
  let url = try await EasyWorkIncomingFiles.download(file, directory: directory("normal"), progress:nil, request:fixture.request)
  check(try Data(contentsOf:url) == bytes, "download bytes"); check(fixture.chunks == 3 && fixture.acks == 1, "multi chunk and ACK"); cases += 1
  let completed = try make(record(bytes, status:"completed"))
  let offline = try EasyWorkIncomingFiles.cached(completed, directory:directory("normal"))
  check(offline == url, "completed cache"); cases += 1
  let empty = Data(), zero = Fixture(empty)
  let zeroURL = try await EasyWorkIncomingFiles.download(make(record(empty)), directory:directory("zero"), progress:nil, request:zero.request)
  check(try Data(contentsOf:zeroURL).isEmpty && zero.chunks == 0 && zero.acks == 1, "zero bytes"); cases += 1
  let mutations: [(String, (inout [String: Any]) -> Void)] = [
   ("resource", { $0["resourceId"] = "wrong" }), ("transfer", { $0["transferId"] = "wrong" }),
   ("offset", { $0["offset"] = 1 }), ("next", { $0["nextOffset"] = 1 }),
   ("bool", { $0["complete"] = 1 }), ("empty", { $0["dataBase64"] = "" }),
   ("long", { $0["dataBase64"] = Data(repeating: 1, count: 192*1024+1).base64URL })]
  for (name, mutate) in mutations {
   let f = Fixture(bytes); f.mutation = mutate; let dir = directory(name)
   do { _ = try await EasyWorkIncomingFiles.download(file, directory:dir, progress:nil, request:f.request); fatalError("accepted \(name)") } catch {}
   check(try f.acks == 0 && !FileManager.default.fileExists(atPath: try EasyWorkIncomingFiles.destination(file, directory:dir).path), "invalid published or ACK")
   cases += 1
  }
  let mismatchData = Data(repeating: 7, count: bytes.count), mismatch = Fixture(mismatchData), mismatchDir = directory("digest")
  do { _ = try await EasyWorkIncomingFiles.download(file, directory:mismatchDir, progress:nil, request:mismatch.request); fatalError("accepted digest") } catch {}
  check(try mismatch.acks == 0 && !FileManager.default.fileExists(atPath:try EasyWorkIncomingFiles.destination(file,directory:mismatchDir).path), "digest published"); cases += 1
  let retry = Fixture(bytes), retryDir = directory("retry"); retry.failAck = true
  do { _ = try await EasyWorkIncomingFiles.download(file,directory:retryDir,progress:nil,request:retry.request); fatalError("ACK succeeded") } catch {}
  let retryPath = try EasyWorkIncomingFiles.destination(file,directory:retryDir)
  check(try Data(contentsOf:retryPath) == bytes && retry.chunks == 3 && retry.acks == 1, "ACK failure cache")
  retry.failAck = false
  _ = try await EasyWorkIncomingFiles.download(file,directory:retryDir,progress:nil,request:retry.request)
  check(retry.chunks == 3 && retry.acks == 2, "ACK retry downloaded"); cases += 1
  try Data(repeating: 0, count: bytes.count).write(to: retryPath)
  check(try EasyWorkIncomingFiles.cached(file,directory:retryDir) == nil, "modified cache hit"); cases += 1
  let symlinkDir = directory("symlink"), target = directory("target")
  try FileManager.default.createDirectory(at: symlinkDir, withIntermediateDirectories:true)
  try FileManager.default.createDirectory(at: target, withIntermediateDirectories:true)
  try FileManager.default.createSymbolicLink(at:symlinkDir.appendingPathComponent(file.snapshotKey), withDestinationURL:target)
  do { _ = try EasyWorkIncomingFiles.cached(file,directory:symlinkDir); fatalError("accepted symlink") } catch {}
  check(try FileManager.default.contentsOfDirectory(atPath:target.path).isEmpty, "symlink touched target"); cases += 1
  let rootLink = directory("root-link")
  try FileManager.default.createSymbolicLink(at:rootLink, withDestinationURL:target)
  do { _ = try EasyWorkIncomingFiles.cached(file,directory:rootLink); fatalError("accepted root symlink") } catch {}
  check(try FileManager.default.contentsOfDirectory(atPath:target.path).isEmpty, "root symlink touched target"); cases += 1
  let cancelDir = directory("cancel"), cancelFixture = Fixture(bytes)
  let task = Task { try await EasyWorkIncomingFiles.download(file,directory:cancelDir,progress:nil) { method,args,op in
    let reply = try await cancelFixture.request(method,args,op); withUnsafeCurrentTask { $0?.cancel() }; return reply
  } }
  do { _ = try await task.value; fatalError("cancel accepted") } catch {}
  let cancelParent = try EasyWorkIncomingFiles.destination(file,directory:cancelDir).deletingLastPathComponent()
  check(try cancelFixture.acks == 0 && (try FileManager.default.contentsOfDirectory(atPath:cancelParent.path)).isEmpty, "cancel cleanup"); cases += 1
  print("PASS \(cases) cases")
 }
}
`;
const temporary = await mkdtemp(join(tmpdir(), 'incoming-native-'));
const source = join(temporary, 'Harness.swift');
await writeFile(source, harness);
const harnessHash = createHash('sha256').update(harness).digest('hex');
if (evidence) {
  const saved = join(evidence, `Harness-${harnessHash}.swift`);
  try {
    await writeFile(saved, harness, { flag: 'wx' });
  } catch (error) {
    if (error.code !== 'EEXIST' || (await readFile(saved, 'utf8')) !== harness) throw error;
  }
}
const hashes = Object.fromEntries(
  paths.map((p, i) => [p, createHash('sha256').update(sources[i]).digest('hex')]),
);
try {
  const env = {
    ...process.env,
    DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
    CLANG_MODULE_CACHE_PATH: join(temporary, 'module-cache'),
  };
  execFileSync('xcrun', ['swiftc', '-parse-as-library', source, '-o', join(temporary, 'test')], {
    env,
    stdio: 'inherit',
  });
  const output = execFileSync(join(temporary, 'test'), [join(temporary, 'data')], {
    env,
    encoding: 'utf8',
  });
  process.stdout.write(output);
  if (evidence)
    await writeFile(
      join(evidence, `${basename(temporary)}-report.json`),
      JSON.stringify(
        {
          hashes,
          harnessHash,
          scriptSha256: createHash('sha256')
            .update(await readFile('scripts/mobile/easywork-incoming-files-native.mjs'))
            .digest('hex'),
          node: process.version,
          output,
          exit: 0,
        },
        null,
        2,
      ) + '\n',
      { flag: 'wx' },
    );
  await rm(temporary, { recursive: true, force: true });
} catch (error) {
  if (evidence)
    await writeFile(
      join(evidence, `${basename(temporary)}-failure.json`),
      JSON.stringify(
        { hashes, harnessHash, temporary, status: error.status, message: error.message },
        null,
        2,
      ) + '\n',
      { flag: 'wx' },
    );
  throw error;
}
