import Foundation
import CoreFoundation

enum EasyWorkDocumentFileResolution {
  static let capability = "attachments:resolve-document-link:v1"
  static func identifier(_ value: Any?) -> Bool {
    guard let text = value as? String, !text.isEmpty, text.utf8.count <= 1024,
      !text.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { return false }
    return true
  }
  static func source(_ value: [String: Any]) -> Bool {
    Set(value.keys) == Set(["resourceId", "grantId", "version", "messageId", "sha256"])
      && ["resourceId", "grantId", "version", "messageId"].allSatisfy { identifier(value[$0]) }
      && (value["sha256"] as? String)?.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil
  }
  static func attachment(target: [String: Any], threadId: String, source: [String: Any], reference: String,
    request: (String, [String: Any]) async throws -> [String: Any]) async throws -> [String: Any] {
    guard identifier(threadId), self.source(source), !reference.isEmpty, reference.utf8.count <= 8192 else {
      throw HostServiceError.invalidResponse
    }
    try Task.checkCancellation()
    let value = try await request("resource/resolve-document-link", ["version": 1, "target": target,
      "threadId": threadId, "source": source, "reference": reference])
    // A cancelled lookup must never publish a late child, even for a non-cooperative transport.
    try Task.checkCancellation()
    guard let version = value["version"] as? NSNumber,
      CFGetTypeID(version) != CFBooleanGetTypeID(), version.doubleValue == 1,
      let returnedTarget = value["target"] as? [String: Any], NSDictionary(dictionary: target).isEqual(to: returnedTarget),
      value["threadId"] as? String == threadId, value["reference"] as? String == reference,
      let returnedSource = value["source"] as? [String: Any], self.source(returnedSource),
      NSDictionary(dictionary: source).isEqual(to: returnedSource),
      let file = value["attachment"] as? [String: Any],
      let id = file["resourceId"] as? String, identifier(id), ![".", ".."].contains(id), !id.contains("\\"),
      (id as NSString).lastPathComponent == id,
      let name = file["name"] as? String, identifier(name), ![".", ".."].contains(name), !name.contains("\\"),
      (name as NSString).lastPathComponent == name,
      let media = file["contentType"] as? String, !media.isEmpty, media.utf8.count <= 255,
      let size = file["size"] as? NSNumber, CFGetTypeID(size) != CFBooleanGetTypeID(),
      size.doubleValue >= 0, size.doubleValue <= 9007199254740991, size.doubleValue.rounded() == size.doubleValue,
      let hash = file["sha256"] as? String, hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      let child = file["documentSource"] as? [String: Any],
      Set(child.keys) == Set(["grantId", "version", "messageId"]),
      ["grantId", "version", "messageId"].allSatisfy({ identifier(child[$0]) }),
      child["messageId"] as? String == source["messageId"] as? String
    else { throw HostServiceError.invalidResponse }
    return file
  }
}
