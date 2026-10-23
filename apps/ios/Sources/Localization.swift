import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
  case system, chinese = "zh-Hans", english = "en"
  var id: String { rawValue }
  var title: String {
    switch self {
    case .system: return L10n.tr("跟随系统")
    case .chinese: return "中文"
    case .english: return "English"
    }
  }

  func resolved(preferredLanguages: [String] = Locale.preferredLanguages) -> String {
    guard self == .system else { return rawValue }
    for language in preferredLanguages.map({ $0.lowercased() }) {
      if language.hasPrefix("zh") { return "zh-Hans" }
      if language.hasPrefix("en") { return "en" }
    }
    return "en"
  }
}

/// Literal keys and interpolated values stay separate, so names, message bodies,
/// paths, and percent signs in values never become localization keys or formats.
struct LocalizedText: ExpressibleByStringLiteral, ExpressibleByStringInterpolation {
  let key: String
  let arguments: [String]

  init(stringLiteral value: String) {
    key = value.replacingOccurrences(of: "%", with: "%%")
    arguments = []
  }

  init(stringInterpolation: StringInterpolation) {
    key = stringInterpolation.key
    arguments = stringInterpolation.arguments
  }

  struct StringInterpolation: StringInterpolationProtocol {
    var key = ""
    var arguments: [String] = []
    init(literalCapacity: Int, interpolationCount: Int) {
      key.reserveCapacity(literalCapacity)
      arguments.reserveCapacity(interpolationCount)
    }
    mutating func appendLiteral(_ literal: String) {
      key += literal.replacingOccurrences(of: "%", with: "%%")
    }
    mutating func appendInterpolation<T>(_ value: T) {
      key += "%@"
      arguments.append(String(describing: value))
    }
  }
}

enum L10n {
  static let storageKey = "app-language"
  private static var sharedDefaults: UserDefaults? {
    guard let group = Bundle.main.object(forInfoDictionaryKey: "NotificationAppGroup") as? String,
      !group.contains("$(") else { return nil }
    return UserDefaults(suiteName: group)
  }

  static var selection: AppLanguage {
    let defaults = Bundle.main.bundleURL.pathExtension == "appex" ? sharedDefaults : .standard
    return AppLanguage(rawValue: defaults?.string(forKey: storageKey) ?? "system") ?? .system
  }
  static var language: String { selection.resolved() }
  static var locale: Locale { Locale(identifier: language) }
  static var privacyPolicyURL: URL {
    URL(string: "https://51easyai.com/agent-hub/" + (language == "en" ? "privacy-en.html" : "privacy.html"))!
  }
  static var supportURL: URL {
    URL(string: "https://51easyai.com/agent-hub/" + (language == "en" ? "support-en.html" : "support.html"))!
  }

  /// Notification extensions use their own defaults, so mirror only this display
  /// preference into the App Group. No conversation or operation data is changed.
  static func syncNotificationLanguage() {
    sharedDefaults?.set(selection.rawValue, forKey: storageKey)
  }

  private static let bundles: [String: Bundle] = {
    Dictionary(uniqueKeysWithValues: ["zh-Hans", "en"].compactMap { language in
      Bundle.main.path(forResource: language, ofType: "lproj")
        .flatMap(Bundle.init(path:)).map { (language, $0) }
    })
  }()

  static func tr(_ text: LocalizedText) -> String {
    let bundle = bundles[language] ?? Bundle.main
    let format = bundle.localizedString(forKey: text.key, value: text.key, table: "Localizable")
    return String(format: format, locale: locale, arguments: text.arguments)
  }
}
