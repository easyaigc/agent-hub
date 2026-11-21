import Foundation
import CryptoKit
import CoreFoundation

enum HostServiceError: Error { case invalidResponse, wrongHost, unavailable, rejected(Int, String), message(String) }
enum L10n { static func tr(_ s: String) -> String { s } }
struct Client: Codable, Sendable { var id: String }
extension Data { var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } }
struct ComputerFileManifest { let hostId: String; let id: String; let name: String; let mediaType: String; let sizeBytes: Int; let sha256: String }
struct EasyWorkFileLimits { static let wireMaximum = 9_007_199_254_740_991; let chunk: Int }
struct EasyWorkAgentClient {
  func session(_ client: Client) async throws -> FakeChannel { channel }
  func independentFileLimits(_ channel: FakeChannel) async throws -> EasyWorkFileLimits { fatalError("unexpected independent transfer limits") }
  func prepareFileTransfer(_ client: Client, file: ComputerFileManifest) async throws { fatalError("unexpected independent transfer prepare") }
}
// PRODUCTION_DECLARATIONS
final class FakeChannel {
 let mobileDeviceId = "device"
 let target = EasyWorkTarget(account: AgentAccount(authority: "https://acceptance.invalid", subject: "synthetic"), desktopDeviceId: "desktop", workspaceId: "workspace", runtimeId: "local")
 var calls: [(String, [String: Any], String?)] = []
 var statusMissing = false
 var commandExists = false
 var status = "transferring"
 var manifest: UploadManifest!
 func record(completed: Bool = false) -> [String: Any] {
  ["transferId":manifest.id, "threadId":manifest.threadId!, "mobileDeviceId":mobileDeviceId,
   "direction":"mobile-to-desktop", "purpose":"attachment", "name":manifest.name,
   "contentType":manifest.mediaType, "size":manifest.sizeBytes, "transferredBytes":completed ? manifest.sizeBytes : 0,
   "status":completed ? "completed" : status, "sha256":manifest.sha256, "resourceId":"resource"]
 }
 func request(_ method: String, _ args: [String: Any], id: String? = nil) async throws -> Data {
  calls.append((method,args,id))
  if method == "resource/upload-status" && statusMissing { statusMissing = false; throw HostServiceError.rejected(404, "missing") }
  let object: Any
  switch method {
  case "command/status": object = commandExists ? ["state":"accepted"] : NSNull()
  case "resource/upload-chunk": object = ["nextOffset":manifest.sizeBytes, "complete":true]
  case "resource/upload-complete": object = ["transfer":record(completed:true), "attachment":["id":manifest.id,"resourceId":"resource","name":manifest.name,"contentType":manifest.mediaType,"sha256":manifest.sha256,"size":manifest.sizeBytes]]
  default: object = ["transfer":record()]
  }
  return try JSONSerialization.data(withJSONObject: object, options: [.fragmentsAllowed])
 }
}
let channel = FakeChannel()
@main struct Main {
 static func main() async throws {
  let bytes = Data("abc".utf8), hash = HostService.hash(Data("abc".utf8))
  let id = "1F18BDCA-4133-4AF9-AE86-7D62A9D5FA02"
  let captured = DesktopCreationFile(id:id, name:"path/raw.txt", mediaType:"text/plain", sizeBytes:3, sha256:hash)
  let m = UploadManifest(hostId:"host", id:id, name:"raw.txt", mediaType:"text/plain", sizeBytes:3, sha256:hash, purpose:"attachment", threadId:"thread", creationId:nil, attachmentId:nil)
  channel.manifest = m
  let job = UploadJob(client:Client(id:"host"), manifest:m, phase:"paused", capturedFile:captured)
  let encoded = try JSONEncoder().encode(job)
  let restored = try JSONDecoder().decode(UploadJob.self, from:encoded)
  precondition(restored.capturedFile == captured)
  var old = try JSONSerialization.jsonObject(with:encoded) as! [String:Any]; old.removeValue(forKey:"capturedFile")
  let oldData = try JSONSerialization.data(withJSONObject:old)
  let decodedOld = try JSONDecoder().decode(UploadJob.self, from:oldData)
  precondition(decodedOld.capturedFile == nil)
  var other = captured; other = DesktopCreationFile(id: UUID().uuidString, name:other.name, mediaType:other.mediaType, sizeBytes:other.sizeBytes, sha256:other.sha256)
  async let first: Bool = UploadRequestContext.$capturedFile.withValue(captured) { await Task.yield(); return UploadRequestContext.capturedFile == captured }
  async let second: Bool = UploadRequestContext.$capturedFile.withValue(other) { await Task.yield(); return UploadRequestContext.capturedFile == other }
  let (a,b) = await (first,second); precondition(a && b && UploadRequestContext.capturedFile == nil)
  let service = EasyWorkAgentClient(), client = Client(id:"host")
  func run(_ action:UploadAction, _ capture:DesktopCreationFile? = captured, data:Data? = nil) async throws -> UploadReceipt {
   try await UploadRequestContext.$capturedFile.withValue(capture) { try await service.uploadRequest(client, manifest:m, action:action, bytes:data, sent:nil) }
  }
  channel.status = "waiting"
  _ = try await run(.prepare)
  precondition(channel.calls.map(\.0) == ["resource/upload-status"])
  channel.status = "transferring"
  channel.calls = []; channel.statusMissing = true; channel.commandExists = true
  do { _ = try await run(.prepare); fatalError("existing command accepted") } catch HostServiceError.message(_) {}
  precondition(channel.calls.map(\.0) == ["resource/upload-status", "command/status"] && channel.calls[1].1["commandId"] as? String == "upload-" + id)
  channel.calls = []; channel.statusMissing = true; channel.commandExists = false
  _ = try await run(.prepare)
  precondition(channel.calls.map(\.0) == ["resource/upload-status", "command/status", "resource/upload", "resource/upload-status"])
  precondition(channel.calls[2].2 == "upload-" + id && channel.calls[2].1["name"] as? String == captured.name && channel.calls[2].1["transferId"] as? String == id)
  channel.calls = []; channel.statusMissing = true
  _ = try await run(.prepare)
  precondition(channel.calls[2].2 == "upload-" + id && channel.calls[2].1["name"] as? String == captured.name)
  channel.calls = []; _ = try await run(.chunk(0), data:bytes); _ = try await run(.chunk(0), data:bytes)
  precondition(channel.calls.filter { $0.0 == "resource/upload-chunk" }.map(\.2) == ["chunk-" + id + "-0", "chunk-" + id + "-0"])
  channel.calls = []; _ = try await run(.complete); _ = try await run(.complete)
  precondition(channel.calls.map(\.2) == ["complete-" + id, "complete-" + id])
  channel.calls = []; _ = try await run(.chunk(0), nil, data:bytes)
  precondition(channel.calls[0].2 == "chunk-" + id + "-0-" + String(hash.prefix(12)))
  let bads = [DesktopCreationFile(id:UUID().uuidString,name:captured.name,mediaType:captured.mediaType,sizeBytes:3,sha256:hash), DesktopCreationFile(id:id,name:"other",mediaType:captured.mediaType,sizeBytes:3,sha256:hash), DesktopCreationFile(id:id,name:captured.name,mediaType:"other",sizeBytes:3,sha256:hash), DesktopCreationFile(id:id,name:captured.name,mediaType:captured.mediaType,sizeBytes:4,sha256:hash), DesktopCreationFile(id:id,name:captured.name,mediaType:captured.mediaType,sizeBytes:3,sha256:"bad")]
  for bad in bads { channel.calls = []; do { _ = try await run(.prepare, bad); fatalError("bad tuple") } catch HostServiceError.invalidResponse {} ; precondition(channel.calls.isEmpty) }
  let large = UploadManifest(hostId:"host", id:id, name:"raw.txt", mediaType:"text/plain", sizeBytes:20*1024*1024+1, sha256:hash, purpose:"attachment", threadId:"thread", creationId:nil, attachmentId:nil)
  channel.calls = []
  do { _ = try await UploadRequestContext.$capturedFile.withValue(captured) { try await service.uploadRequest(client, manifest:large, action:.prepare, bytes:nil, sent:nil) }; fatalError("large accepted") } catch HostServiceError.message(_) {}
  precondition(channel.calls.isEmpty && captured.id == id && captured.sha256 == hash)
  print("captured upload commands passed (auxiliary, fake channel)")
 }
}
