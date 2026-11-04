import Foundation

/// A read can reconnect after preserving its completed or pending operation.
protocol RecoverableConnectionReadFailure: Error {}

#if DEBUG
@MainActor enum ConnectionNoticeDiagnostics {
  private static var events: [String] = []
  static func record(_ event: String) {
    guard ProcessInfo.processInfo.environment["EASYWORK_CONNECTION_NOTICE_DIAGNOSTICS"] == "1" else { return }
    events.append(event)
    events = Array(events.suffix(64))
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("connection-notice-diagnostics.json")
    if let data = try? JSONEncoder().encode(events) { try? data.write(to: url, options: .atomic) }
  }
}
#endif

/// Adapters identify definitive device-authorization failures without exposing
/// wire codes or matching translated messages in the shared presentation layer.
protocol ConnectionAuthorizationFailure: Error {
  var authorizationNoticeKey: String? { get }
}

/// Routes may change while an authorization remains the same.
struct ConnectionAuthorizationIdentity<Target: Equatable>: Equatable {
  let target: Target
  let credentialRef: String
}

/// Per live connection, suppress repeated automatic alerts after dismissal.
/// Failure state, explicit user feedback, credentials and commands are unchanged.
struct ConnectionAuthorizationNotices<Identity: Equatable> {
  private var presented: [String: (identity: Identity, failure: String)] = [:]
  mutating func shouldPresent(target: String, identity: Identity, failure: String?) -> Bool {
    guard let failure else { return true }
    if let old = presented[target], old.identity == identity, old.failure == failure { return false }
    presented[target] = (identity, failure)
    return true
  }
  mutating func reset(_ target: String) { presented.removeValue(forKey: target) }
}


// Presentation only: preserve the original transport error and never retry a command here.
struct OperationFailure {
  let title: String
  let message: String

  init(message: String) {
    title = L10n.tr(SharedFailureCopy.defaultTitle)
    self.message = message
  }

  init(_ error: Error) {
    let connectionMessage = Self.connectionMessage(error)
    title = connectionMessage == nil ? L10n.tr(SharedFailureCopy.defaultTitle) : L10n.tr(SharedFailureCopy.connectionTitle)
    message = connectionMessage ?? error.localizedDescription
  }

  private static func connectionMessage(_ error: Error) -> String? {
    let failure = error as NSError
    guard failure.domain == NSURLErrorDomain else { return nil }
    let kind: SharedConnectionFailureKind
    switch URLError.Code(rawValue: failure.code) {
    case .secureConnectionFailed:
      kind = .secureConnection
    case .serverCertificateHasBadDate, .serverCertificateUntrusted,
      .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
      .clientCertificateRejected, .clientCertificateRequired, .appTransportSecurityRequiresSecureConnection:
      kind = .certificate
    case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff:
      kind = .noNetwork
    case .timedOut:
      kind = .timeout
    case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .resourceUnavailable:
      kind = .unreachable
    case .networkConnectionLost, .backgroundSessionWasDisconnected:
      kind = .lost
    default:
      return nil
    }
    return L10n.tr(SharedFailureCopy.message(kind))
  }
}

extension Error {
  var userFacingDescription: String { OperationFailure(self).message }
}
