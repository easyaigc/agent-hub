import Foundation
import CryptoKit
import UniformTypeIdentifiers

enum HostServiceError: Error { case invalidResponse, wrongHost }
struct LocalAttachment { let id: String; let name: String; let size: Int; let fileName: String }
struct DesktopCreationFile: Equatable { var id: String; var name: String; var mediaType: String; var sizeBytes: Int; var sha256: String }
struct Target: Codable { var agentId: String; var account: String? }
struct Connection { var target: Target }
struct Client { let id: String; var agentConnection: Connection? = nil; var codexDirect: Connection? = nil; var readingTarget: Target? = nil }
struct UploadManifest { let hostId: String; let id: String; let name: String; let mediaType: String; let sizeBytes: Int; let sha256: String; let purpose: String; let threadId: String?; let creationId: String?; let attachmentId: String? }
struct RemoteAttachment { var hostId: String; var threadId: String; var name: String; var mediaType: String; var sha256: String; var sizeBytes: Int }
struct Receipt { let result: RemoteAttachment; func value<T>(_ type: T.Type) throws -> T { guard let result = result as? T else { throw HostServiceError.invalidResponse }; return result } }
enum EasyWorkAttachmentUpload { static func displayName(_ name: String) -> String { name } }
protocol AgentClient {}
struct TestClient: AgentClient {}
// PRODUCTION_DECLARATIONS
@main struct Main {
 static func main() async throws {
  let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
  defer { try? FileManager.default.removeItem(at: source) }
  try Data("abc".utf8).write(to: source)
  let client = Client(id: "host")
  let file = LocalAttachment(id: "1F18BDCA-4133-4AF9-AE86-7D62A9D5FA02", name: "fixture", size: 3, fileName: "retained-bytes")
  let hash = HostService.hash(Data("abc".utf8))
  let expected = DesktopCreationFile(id: file.id, name: file.name, mediaType: "application/octet-stream", sizeBytes: 3, sha256: hash)
  let valid = RemoteAttachment(hostId: "host", threadId: "thread", name: file.name, mediaType: expected.mediaType, sha256: hash, sizeBytes: 3)
  UploadCenter.shared.result = valid
  let test = TestClient()
  var bad = [DesktopCreationFile]()
  var v = expected; v.id = "other"; bad.append(v)
  v = expected; v.name = "other.txt"; bad.append(v)
  v = expected; v.mediaType = "image/png"; bad.append(v)
  v = expected; v.sizeBytes = 4; bad.append(v)
  v = expected; v.sha256 = String(repeating: "0", count: 64); bad.append(v)
  for item in bad {
   do { _ = try await test.uploadFile(client, threadId: "thread", file: file, source: source, manifest: item); fatalError("mismatch accepted") } catch HostServiceError.invalidResponse {}
   precondition(UploadCenter.shared.calls == 0)
  }
  let wrongSizeFile = LocalAttachment(id: file.id, name: file.name, size: 4, fileName: file.fileName)
  do { _ = try await test.uploadFile(client, threadId: "thread", file: wrongSizeFile, source: source, manifest: expected); fatalError("source size accepted") } catch HostServiceError.invalidResponse {}
  precondition(UploadCenter.shared.calls == 0)
  let invalidFile = LocalAttachment(id: "not-a-uuid", name: file.name, size: file.size, fileName: file.fileName)
  var invalidExpected = expected; invalidExpected.id = invalidFile.id
  do { _ = try await test.uploadFile(client, threadId: "thread", file: invalidFile, source: source, manifest: invalidExpected); fatalError("invalid ID accepted") } catch HostServiceError.invalidResponse {}
  precondition(UploadCenter.shared.calls == 0)
  let result = try await test.uploadFile(client, threadId: "thread", file: file, source: source, manifest: expected)
  precondition(UploadCenter.shared.calls == 1 && result.hostId == valid.hostId && result.threadId == valid.threadId && result.name == valid.name && result.mediaType == valid.mediaType && result.sha256 == valid.sha256 && result.sizeBytes == valid.sizeBytes)
  precondition(UploadCenter.shared.captures[0] == expected && UploadCenter.shared.manifests[0].id == expected.id && UploadCenter.shared.manifests[0].hostId == client.id && UploadCenter.shared.manifests[0].threadId == "thread")
  _ = try await test.uploadFile(client, threadId: "thread", file: file, source: source, manifest: expected)
  precondition(UploadCenter.shared.captures[1] == expected && UploadCenter.shared.manifests[1].id == expected.id && UploadCenter.shared.manifests[1].hostId == client.id && UploadCenter.shared.manifests[1].threadId == "thread")
  let clone = LocalAttachment(id: "D4D3BA70-356C-4D51-9495-F03F9D00E17A", name: file.name, size: file.size, fileName: file.fileName)
  var clonedExpected = expected; clonedExpected.id = clone.id
  _ = try await test.uploadFile(client, threadId: "thread", file: clone, source: source, manifest: clonedExpected)
  let sourceBytes = try Data(contentsOf: source)
  precondition(UploadCenter.shared.captures[2] == clonedExpected && UploadCenter.shared.manifests[2].id == clone.id && clone.id != expected.id && clone.name == file.name && clone.fileName == file.fileName && sourceBytes == Data("abc".utf8))
  var receipts = [RemoteAttachment]()
  var receipt = valid; receipt.hostId = "other"; receipts.append(receipt)
  receipt = valid; receipt.threadId = "other"; receipts.append(receipt)
  receipt = valid; receipt.name = "other"; receipts.append(receipt)
  receipt = valid; receipt.mediaType = "other"; receipts.append(receipt)
  receipt = valid; receipt.sha256 = "other"; receipts.append(receipt)
  receipt = valid; receipt.sizeBytes = 4; receipts.append(receipt)
  for item in receipts {
   UploadCenter.shared.result = item
   do { _ = try await test.uploadFile(client, threadId: "thread", file: file, source: source, manifest: expected); fatalError("bad receipt accepted") } catch HostServiceError.wrongHost {}
  }
  precondition(UploadCenter.shared.calls == 9)
  print("manifest-bound upload boundary passed (auxiliary, controlled transfer)")
 }
}
