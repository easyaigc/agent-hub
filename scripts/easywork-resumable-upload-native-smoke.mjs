import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('EasyWork resumable upload native Swift smoke skipped: macOS Swift required.');
  process.exit(0);
}

const uploads = await readFile('apps/ios/Sources/Uploads.swift', 'utf8');
const reading = await readFile('apps/ios/Sources/EasyWorkReading.swift', 'utf8');
function section(source, start, end) {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from + start.length);
  if (from < 0 || to < 0 || source.indexOf(start, from + start.length) >= 0) {
    throw new Error(`Cannot uniquely extract ${start}`);
  }
  return source.slice(from, to);
}
const types = section(uploads, 'struct UploadManifest:', 'struct UploadReceipt:');
const validation = section(
  uploads,
  'enum EasyWorkUploadValidation {',
  '\nextension EasyWorkAgentClient {',
);
const integer = section(
  reading,
  '  static func integer(_ value: Any?) throws -> Int {',
  '  static func position(',
);
const harness = String.raw`
import Foundation
import CoreFoundation
enum HostServiceError: Error { case invalidResponse }
${types}
enum EasyWorkReading {
${integer}
}
${validation}
let manifest = UploadManifest(hostId: "host", id: "transfer", name: "file.txt", mediaType: "text/plain", sizeBytes: 10, sha256: "digest", purpose: "attachment", threadId: "thread", creationId: nil, attachmentId: nil)
let base: [String: Any] = ["transferId": "transfer", "threadId": "thread", "mobileDeviceId": "device", "direction": "mobile-to-desktop", "purpose": "attachment", "name": "file.txt", "contentType": "text/plain", "size": 10, "transferredBytes": 4, "status": "uploading"]
let attachment: [String: Any] = ["id": "transfer", "resourceId": "resource", "name": "file.txt", "contentType": "text/plain", "size": 10, "sha256": "digest"]
func reject(_ label: String, _ operation: () throws -> Void) {
  do { try operation(); fatalError("Accepted invalid \(label)") }
  catch HostServiceError.invalidResponse { }
  catch { fatalError("Unexpected error for \(label): \(error)") }
}
func response(_ record: [String: Any], _ item: [String: Any]? = nil) -> [String: Any] {
  var result: [String: Any] = ["transfer": record]
  if let item { result["attachment"] = item }
  return result
}
func changed(_ input: [String: Any], _ key: String, _ value: Any) -> [String: Any] {
  var copy = input; copy[key] = value; return copy
}
let partial = try EasyWorkUploadValidation.transfer(response(base), manifest: manifest, deviceId: "device")
let partialOffset = try EasyWorkReading.integer(partial["transferredBytes"])
precondition(partialOffset == 4)
var completed = base
completed["transferredBytes"] = 10; completed["status"] = "completed"; completed["sha256"] = "digest"; completed["resourceId"] = "resource"
let result = try EasyWorkUploadValidation.completion(response(completed, attachment), manifest: manifest, deviceId: "device")
precondition(result.id == "resource" && result.threadId == "thread" && result.sizeBytes == 10 && result.sha256 == "digest")
try EasyWorkUploadValidation.chunk(["nextOffset": 4, "complete": false], end: 4, size: 10)
try EasyWorkUploadValidation.chunk(["nextOffset": 10, "complete": true], end: 10, size: 10)
for (key, value) in [("transferId", "other"), ("threadId", "other"), ("mobileDeviceId", "other"), ("direction", "desktop-to-mobile"), ("purpose", "other"), ("name", "other"), ("contentType", "other")] {
  reject("transfer \(key)") { _ = try EasyWorkUploadValidation.transfer(response(changed(base, key, value)), manifest: manifest, deviceId: "device") }
}
reject("empty device") { _ = try EasyWorkUploadValidation.transfer(response(base), manifest: manifest, deviceId: "") }
for value in [11, -1] {
  reject("offset \(value)") { _ = try EasyWorkUploadValidation.transfer(response(changed(base, "transferredBytes", value)), manifest: manifest, deviceId: "device") }
}
for value: Any in [11, true, 1.5] {
  reject("transfer size \(value)") { _ = try EasyWorkUploadValidation.transfer(response(changed(base, "size", value)), manifest: manifest, deviceId: "device") }
}
for (key, value) in [("status", "uploading"), ("sha256", "wrong"), ("resourceId", "wrong")] {
  reject("completed transfer \(key)") { _ = try EasyWorkUploadValidation.completion(response(changed(completed, key, value), attachment), manifest: manifest, deviceId: "device") }
}
reject("incomplete completed offset") { _ = try EasyWorkUploadValidation.completion(response(changed(completed, "transferredBytes", 9), attachment), manifest: manifest, deviceId: "device") }
for (key, value) in [("id", "wrong"), ("resourceId", "wrong"), ("resourceId", ""), ("name", "wrong"), ("contentType", "wrong"), ("sha256", "wrong")] {
  reject("attachment \(key)") { _ = try EasyWorkUploadValidation.completion(response(completed, changed(attachment, key, value)), manifest: manifest, deviceId: "device") }
}
reject("attachment size") { _ = try EasyWorkUploadValidation.completion(response(completed, changed(attachment, "size", 11)), manifest: manifest, deviceId: "device") }
for value: Any in [1, "true", NSNull()] {
  reject("non-boolean complete") { try EasyWorkUploadValidation.chunk(["nextOffset": 10, "complete": value], end: 10, size: 10) }
}
reject("wrong chunk offset") { try EasyWorkUploadValidation.chunk(["nextOffset": 3, "complete": false], end: 4, size: 10) }
reject("early complete") { try EasyWorkUploadValidation.chunk(["nextOffset": 4, "complete": true], end: 4, size: 10) }
reject("missing complete") { try EasyWorkUploadValidation.chunk(["nextOffset": 10, "complete": false], end: 10, size: 10) }
print("EasyWork resumable upload native smoke passed")
`;
const directory = await mkdtemp(join(tmpdir(), 'easywork-resumable-upload-smoke-'));
try {
  const source = join(directory, 'main.swift');
  const executable = join(directory, 'smoke');
  await writeFile(source, harness);
  execFileSync('xcrun', ['swiftc', source, '-o', executable], {
    stdio: 'inherit',
    env: { ...process.env, CLANG_MODULE_CACHE_PATH: join(directory, 'module-cache') },
  });
  execFileSync(executable, [], { stdio: 'inherit' });
} finally {
  await rm(directory, { recursive: true, force: true });
}
