import Foundation
import CryptoKit
import Markdown

public struct PreviewBlock: Identifiable, Sendable {
  public enum Kind: Sendable { case paragraph, heading(Int), code(String), quote, list(String), table, divider, html }
  public let id: Int
  public let kind: Kind
  public let markdown: String
  public let text: String
  public let sourceLine: Int
  public let indent: Int
  public let rows: [[String]]
  public let images: [PreviewImage]
  public let anchor: String?
  public let attributed: AttributedString
  public let attributedRows: [[AttributedString]]
}

public struct PreviewImage: Sendable {
  public let label: String
  public let destination: String
}

public struct PreviewDocument: Sendable {
  public let source: String
  public let digest: String
  public let blocks: [PreviewBlock]
  public var headings: [PreviewBlock] { blocks.filter { if case .heading = $0.kind { return true }; return false } }

  public static func load(_ url: URL, maximumBytes: Int) throws -> PreviewDocument {
    let file = try FileHandle(forReadingFrom: url)
    defer { try? file.close() }
    let bytes = try file.read(upToCount: maximumBytes + 1) ?? Data()
    guard bytes.count <= maximumBytes else { throw PreviewError.tooLarge }
    return try parse(bytes)
  }

  public static func parse(_ bytes: Data) throws -> PreviewDocument {
    let source: String?
    if bytes.starts(with: [0xFF, 0xFE]) || bytes.starts(with: [0xFE, 0xFF]) {
      source = String(data: bytes, encoding: .utf16)
    } else { source = String(data: bytes, encoding: .utf8) }
    guard let source, !source.contains("\0") else { throw PreviewError.encoding }
    let root = Document(parsing: source)
    var pending: [(any Markup, Int)] = [(root, 0)]
    var count = 0
    while let (node, depth) = pending.popLast() {
      count += 1
      guard count <= 20_000, depth <= 64 else { throw PreviewError.complexity }
      pending.append(contentsOf: node.children.map { ($0, depth + 1) })
    }
    var builder = Builder(source: source)
    try builder.visit(root, indent: 0)
    return PreviewDocument(source: source, digest: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), blocks: builder.blocks)
  }
}

public enum PreviewError: Error { case tooLarge, encoding, complexity }

private struct Builder {
  let source: String
  var blocks: [PreviewBlock] = []
  var anchors: [String: Int] = [:]
  var visited = 0

  mutating func visit(_ node: any Markup, indent: Int) throws {
    visited += 1
    guard visited <= 20_000, indent <= 32 else { throw PreviewError.complexity }
    let text = plain(node)
    let line = node.range?.lowerBound.line ?? 1
    func images(_ value: any Markup) -> [PreviewImage] {
      if let image = value as? Markdown.Image { return [PreviewImage(label: plain(image), destination: image.source ?? "")] }
      return value.children.flatMap { images($0) }
    }
    func append(_ kind: PreviewBlock.Kind, markdown: String? = nil, text override: String? = nil,
      rows: [[String]] = [], anchor: String? = nil) {
      let value = markdown ?? node.children.map { $0.format() }.joined()
      blocks.append(PreviewBlock(id: blocks.count, kind: kind, markdown: value,
        text: override ?? text, sourceLine: line, indent: indent, rows: rows, images: images(node), anchor: anchor,
        attributed: styled(value), attributedRows: rows.map { $0.map(styled) }))
    }
    switch node {
    case let heading as Heading:
      let base = slug(text)
      let count = anchors[base, default: 0]; anchors[base] = count + 1
      append(.heading(heading.level), anchor: count == 0 ? base : base + "-\(count)")
    case let code as CodeBlock:
      append(.code(code.language ?? ""), markdown: "", text: originalCode(code))
    case let table as Table:
      var rows = [Array(table.head.cells).map { $0.children.map { $0.format() }.joined() }]
      for row in table.body.rows { rows.append(row.cells.map { $0.children.map { $0.format() }.joined() }) }
      guard rows.count <= 1_000, rows.allSatisfy({ $0.count <= 32 }) else { throw PreviewError.complexity }
      append(.table, markdown: "", rows: rows)
    case is ThematicBreak: append(.divider)
    case let html as HTMLBlock: append(.html, markdown: "", text: html.rawHTML)
    case is Paragraph: append(.paragraph)
    case is BlockQuote:
      append(.quote, markdown: "")
      for child in node.children { try visit(child, indent: indent + 1) }
    case let list as OrderedList:
      for (index, item) in list.listItems.enumerated() {
        try listItem(item, marker: "\(list.startIndex + UInt(index)).", indent: indent)
      }
    case let list as UnorderedList:
      for item in list.listItems { try listItem(item, marker: "•", indent: indent) }
    default:
      for child in node.children { try visit(child, indent: indent) }
    }
  }

  mutating func listItem(_ item: ListItem, marker: String, indent: Int) throws {
    let label = item.checkbox.map { $0 == .checked ? "☑" : "☐" } ?? marker
    if let first = item.children.first(where: { _ in true }), !(first is Paragraph) {
      blocks.append(PreviewBlock(id: blocks.count, kind: .list(label), markdown: "", text: "",
        sourceLine: item.range?.lowerBound.line ?? 1, indent: indent, rows: [], images: [], anchor: nil,
        attributed: AttributedString(""), attributedRows: []))
      for child in item.children { try visit(child, indent: indent + 1) }
      return
    }
    let start = blocks.count
    for child in item.children { try visit(child, indent: indent + 1) }
    guard start < blocks.count else { return }
    let block = blocks[start]
    blocks[start] = PreviewBlock(id: block.id, kind: .list(label), markdown: block.markdown,
      text: block.text, sourceLine: block.sourceLine, indent: indent, rows: block.rows, images: block.images, anchor: nil,
      attributed: block.attributed, attributedRows: block.attributedRows)
  }

  func originalCode(_ code: CodeBlock) -> String {
    // Keep fenced code's original CRLF, whitespace and final newline for Copy.
    guard let range = code.range else { return code.code }
    let lines = source.components(separatedBy: "\n")
    let start = range.lowerBound.line - 1
    guard lines.indices.contains(start) else { return code.code }
    let opening = lines[start].drop(while: { $0 == " " })
    guard let fence = opening.first, fence == "`" || fence == "~" else { return code.code }
    let width = opening.prefix(while: { $0 == fence }).count
    guard width >= 3 else { return code.code }
    var end = min(range.upperBound.line, lines.count)
    if end > start + 1 {
      let closing = lines[end - 1].trimmingCharacters(in: .whitespacesAndNewlines)
      if closing.count >= width && closing.allSatisfy({ $0 == fence }) { end -= 1 }
    }
    guard end > start + 1 else { return "" }
    var result = lines[(start + 1)..<end].joined(separator: "\n")
    if end < lines.count { result += "\n" }
    return result
  }
}

private func styled(_ text: String) -> AttributedString {
  (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(text)
}

private func plain(_ node: any Markup) -> String {
  if let value = node as? Markdown.Text { return value.string }
  if let value = node as? InlineCode { return value.code }
  if node is SoftBreak || node is LineBreak { return "\n" }
  if let value = node as? CodeBlock { return value.code }
  return node.children.map { plain($0) }.joined()
}

private func slug(_ title: String) -> String {
  let value = title.lowercased().unicodeScalars.filter {
    CharacterSet.alphanumerics.contains($0) || CharacterSet.nonBaseCharacters.contains($0) || $0 == "-" || $0 == "_" || CharacterSet.whitespaces.contains($0)
  }.map { CharacterSet.whitespaces.contains($0) ? "-" : String($0) }.joined()
  return value.isEmpty ? "section" : value
}
