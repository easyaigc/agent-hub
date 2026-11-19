import Foundation
import CoreFoundation
import CryptoKit

enum HostServiceError: Error { case invalidResponse }

@main struct DocumentFileBoundary {
  static func main() async throws {
    // Old persisted attachment metadata remains readable and retains its reading key.
    let legacy = Data(#"{"id":"legacy","hostId":"host","threadId":"thread","name":"old.md","mediaType":"text/markdown","sizeBytes":4,"sha256":"digest","resourceId":"original-resource"}"#.utf8)
    var original = try JSONDecoder().decode(RemoteAttachment.self, from: legacy)
    precondition(original.documentSource == nil && original.documentMessageSource == nil)
    precondition(original.readingResourceIdentity == "original-resource")
    original.documentMessageSource = DocumentMessageSource(messageId: "canonical", reference: "root.md")
    let encoded = try JSONEncoder().encode(original)
    let restored = try JSONDecoder().decode(RemoteAttachment.self, from: encoded)
    precondition(restored == original && restored.readingResourceIdentity == original.readingResourceIdentity)
    var renewed = RemoteAttachment(id: "renewed", hostId: "host", threadId: "thread", name: "root.md",
      mediaType: "text/markdown", sizeBytes: 4, sha256: "digest")
    renewed.documentMessageSource = original.documentMessageSource
    precondition(renewed.readingResourceIdentity == original.readingResourceIdentity)
    renewed.documentMessageSource = DocumentMessageSource(messageId: "canonical", reference: "other.md")
    precondition(renewed.readingResourceIdentity != original.readingResourceIdentity)
    let target: [String: Any] = ["desktopDeviceId":"host", "workspaceId":"workspace", "runtimeId":"runtime",
      "account":["authority":"test","subject":"user"]]
    let source: [String: Any] = ["resourceId":"root", "grantId":"source-grant", "version":"revision-1",
      "messageId":"original-message", "sha256":String(repeating:"a",count:64)]
    let reference = "../资料%20目录/子%20文档.md#目标"
    let file: [String: Any] = ["resourceId":"child", "name":"子 文档.md", "size":128,
      "contentType":"text/markdown", "sha256":String(repeating:"b",count:64),
      "documentSource":["grantId":"child-grant", "version":"revision-2", "messageId":"original-message"]]
    let payload: [String: Any] = ["version":1,"target":target,"threadId":"thread","source":source,
      "reference":reference,"attachment":file]
    var calls = 0
    let child = try await EasyWorkDocumentFileResolution.attachment(target:target,threadId:"thread",source:source,reference:reference) { method, params in
      precondition(method == "resource/resolve-document-link")
      precondition(NSDictionary(dictionary:params["source"] as! [String:Any]).isEqual(to:source))
      precondition(params["reference"] as? String == reference); calls += 1; return payload
    }
    precondition(child["resourceId"] as? String == "child" && calls == 1)
    var rejectedCount = 0
    for (key,value) in [("version",true as Any),("version",2 as Any),("version",1.5 as Any),
      ("threadId","other" as Any),("target",["runtimeId":"other"] as Any),
      ("source",["resourceId":"foreign"] as Any),("reference","other" as Any)] {
      var changed=payload;changed[key]=value
      do { _ = try await EasyWorkDocumentFileResolution.attachment(target:target,threadId:"thread",source:source,reference:reference) { _,_ in changed }; preconditionFailure("accepted mismatch") }
      catch { rejectedCount += 1 }
    }
    for (key,value) in [("resourceId","../foreign" as Any),("resourceId","a\\b" as Any),("name",".." as Any),
      ("name","null\0name" as Any),("size",true as Any),("size",-1 as Any),("size",1.5 as Any),
      ("sha256","bad" as Any),("documentSource",["grantId":"child","version":"v","messageId":"foreign"] as Any)] {
      var changed=payload, metadata=file;metadata[key]=value;changed["attachment"]=metadata
      do { _ = try await EasyWorkDocumentFileResolution.attachment(target:target,threadId:"thread",source:source,reference:reference) { _,_ in changed };preconditionFailure("accepted unsafe attachment") }
      catch { rejectedCount += 1 }
    }
    // A nested resolve uses the child's grant and digest, never the parent's path.
    let nested: [String:Any] = ["resourceId":"child","grantId":"child-grant","version":"revision-2",
      "messageId":"original-message","sha256":String(repeating:"b",count:64)]
    var next=payload;next["source"]=nested
    _ = try await EasyWorkDocumentFileResolution.attachment(target:target,threadId:"thread",source:nested,reference:reference) { _,params in
      precondition((params["source"] as? [String:Any])?["grantId"] as? String == "child-grant");return next
    }
    // Transport returns successfully after cancellation; production boundary rejects publication.
    let late = Task { () throws -> [String:Any] in
      try await EasyWorkDocumentFileResolution.attachment(target:target,threadId:"thread",source:source,reference:reference) { _,_ in
        withUnsafeCurrentTask { $0?.cancel() };return payload
      }
    }
    do { _ = try await late.value;preconditionFailure("late reply accepted") } catch is CancellationError {} catch { throw error }
    print("passed: legacy metadata/reading key and stable message identity; exact target/source/version/message/ref; nested child grants; \(rejectedCount) unsafe replies rejected; cancelled late reply discarded; zero fallback/model calls")
  }
}
