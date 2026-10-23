import Foundation

// Presentation only: preserve the original transport error and never retry a command here.
struct OperationFailure {
  let title: String
  let message: String

  init(message: String) {
    title = L10n.tr("无法完成操作")
    self.message = message
  }

  init(_ error: Error) {
    let connectionMessage = Self.connectionMessage(error)
    title = connectionMessage == nil ? L10n.tr("无法完成操作") : L10n.tr("暂时无法连接电脑")
    message = connectionMessage ?? error.localizedDescription
  }

  private static func connectionMessage(_ error: Error) -> String? {
    let failure = error as NSError
    guard failure.domain == NSURLErrorDomain else { return nil }
    switch URLError.Code(rawValue: failure.code) {
    case .secureConnectionFailed:
      return L10n.tr("与电脑的安全连接未能建立。请确认电脑已联网、连接服务正在运行，稍后再试；若仍无法连接，请在电脑端检查连接设置。")
    case .serverCertificateHasBadDate, .serverCertificateUntrusted,
      .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
      .clientCertificateRejected, .clientCertificateRequired, .appTransportSecurityRequiresSecureConnection:
      return L10n.tr("暂时无法验证这台电脑的安全连接。请在电脑端检查连接设置后重试；如果最近重新配置过连接，请重新添加这台电脑。")
    case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff:
      return L10n.tr("手机当前没有可用的网络。请检查 Wi-Fi 或移动网络；如果通过 VPN 连接，也请确认 VPN 已连接。")
    case .timedOut:
      return L10n.tr("连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。")
    case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .resourceUnavailable:
      return L10n.tr("暂时无法连接这台电脑。请确认电脑已开机并联网，且连接服务正在运行；如果通过 VPN 连接，也请确认 VPN 已连接。")
    case .networkConnectionLost, .backgroundSessionWasDisconnected:
      return L10n.tr("与电脑的连接已中断。请检查手机和电脑的网络，连接恢复后再试。")
    default:
      return nil
    }
  }
}

extension Error {
  var userFacingDescription: String { OperationFailure(self).message }
}
