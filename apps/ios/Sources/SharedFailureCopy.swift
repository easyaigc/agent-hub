// Generated from design/mobile/connection-failures.json; edit the definition, then regenerate.
enum SharedConnectionFailureKind {
  case secureConnection, certificate, noNetwork, timeout, unreachable, lost
}
enum SharedFailureCopy {
  static let defaultTitle: LocalizedText = "无法完成操作"
  static let connectionTitle: LocalizedText = "暂时无法连接电脑"
  static func message(_ kind: SharedConnectionFailureKind) -> LocalizedText {
    switch kind {
    case .secureConnection: return "与电脑的安全连接未能建立。请确认电脑已联网、连接服务正在运行，稍后再试；若仍无法连接，请在电脑端检查连接设置。"
    case .certificate: return "暂时无法验证这台电脑的安全连接。请在电脑端检查连接设置后重试；如果最近重新配置过连接，请重新添加这台电脑。"
    case .noNetwork: return "手机当前没有可用的网络。请检查 Wi-Fi 或移动网络；如果通过 VPN 连接，也请确认 VPN 已连接。"
    case .timeout: return "连接电脑超时。请确认电脑已开机并联网，且连接服务正在运行，然后重试。"
    case .unreachable: return "暂时无法连接这台电脑。请确认电脑已开机并联网，且连接服务正在运行；如果通过 VPN 连接，也请确认 VPN 已连接。"
    case .lost: return "与电脑的连接已中断。请检查手机和电脑的网络，连接恢复后再试。"
    }
  }
}
