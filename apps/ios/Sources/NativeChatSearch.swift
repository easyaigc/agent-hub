import Foundation
import MobileCore

// System Unicode services only; common rules own search selection and snippet sizes.
final class NativeChatSearchText: NSObject, ChatSearchText, @unchecked Sendable {
  static let shared = NativeChatSearchText()
  func first(text: String, query: String) -> ChatSearchSpan? {
    guard !query.isEmpty,
      let match = text.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]),
      match.upperBound > match.lowerBound else { return nil }
    return ChatSearchSpan(start: Int32(match.lowerBound.utf16Offset(in: text)),
      end: Int32(match.upperBound.utf16Offset(in: text)))
  }
  func ranges(text: String, query: String) -> [ChatSearchSpan] {
    guard !text.isEmpty, !query.isEmpty else { return [] }
    var ranges: [ChatSearchSpan] = []
    var position = text.startIndex
    while position < text.endIndex,
      let match = text.range(of: query, options: [.caseInsensitive, .diacriticInsensitive], range: position..<text.endIndex) {
      guard match.upperBound > position, match.upperBound > match.lowerBound else { break }
      ranges.append(ChatSearchSpan(start: Int32(match.lowerBound.utf16Offset(in: text)),
        end: Int32(match.upperBound.utf16Offset(in: text))))
      position = match.upperBound
    }
    return ranges
  }
  func offset(text: String, start: Int32, characters: Int32) -> Int32 {
    let index = String.Index(utf16Offset: min(max(0, Int(start)), text.utf16.count), in: text)
    let end = characters < 0 ? text.startIndex : text.endIndex
    let result = text.index(index, offsetBy: Int(characters), limitedBy: end) ?? end
    return Int32(result.utf16Offset(in: text))
  }
}
