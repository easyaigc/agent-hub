import Foundation

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
