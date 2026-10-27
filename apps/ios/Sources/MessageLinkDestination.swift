import Foundation
import MobileCore

enum MessageLinkDestination: Equatable {
  case attachment(String), web(URL), external(URL), computerFile, computerWeb, unsupported

  static func resolve(_ url: URL) -> Self {
    let id: String?
    if url.scheme?.lowercased() == "codex-attachment" {
      id = url.host == "open"
        ? URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "id" })?.value
        : url.host
    } else { id = nil }
    let kind = MessageLinkRules.shared.destination(scheme: url.scheme, host: url.host,
      hasCredentials: url.user != nil || url.password != nil, attachmentId: id,
      windowsDrive: url.absoluteString.range(of: #"^[A-Za-z]:[/\\]"#, options: .regularExpression) != nil,
      valid: true)
    switch kind {
    case "attachment": return .attachment(id!)
    case "web": return .web(url)
    case "external": return .external(url)
    case "computerFile": return .computerFile
    case "computerWeb": return .computerWeb
    default: return .unsupported
    }
  }
}
