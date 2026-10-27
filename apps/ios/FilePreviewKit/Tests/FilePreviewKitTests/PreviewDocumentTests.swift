import XCTest
@testable import FilePreviewKit

final class PreviewDocumentTests: XCTestCase {
  func testGFMTableEscapedPipesListsAndDuplicateChineseHeadings() throws {
    let text = "# 工具清单\n\n# 工具清单\n\n| 工具 | 说明 |\n| --- | --- |\n| `browser` | a\\|b |\n\n- [x] 已完成\n- [ ] 待处理\n\n<script>alert(1)</script>\n"
    let value = try PreviewDocument.parse(Data(text.utf8))
    XCTAssertEqual(value.source, text)
    XCTAssertEqual(value.headings.map(\.anchor), ["工具清单", "工具清单-1"])
    let table = try XCTUnwrap(value.blocks.first { if case .table = $0.kind { return true }; return false })
    XCTAssertEqual(table.rows.count, 2)
    XCTAssertEqual(String(table.attributedRows[1][1].characters), "a|b")
    XCTAssertTrue(value.blocks.contains { if case .list("☑") = $0.kind { return true }; return false })
    XCTAssertTrue(value.blocks.contains { if case .html = $0.kind { return $0.text.contains("<script>") }; return false })
  }

  func testCodeCopyKeepsCRLFWhitespaceAndUnclosedFenceEOF() throws {
    for body in ["```swift\r\n  let x = 1  \r\n\r\n```\r\n", "~~~json\n{\"a\":1}\n~~~\n", "```\nno final newline"] {
      let value = try PreviewDocument.parse(Data(body.utf8))
      let code = try XCTUnwrap(value.blocks.first { if case .code = $0.kind { return true }; return false })
      let expected = body.hasPrefix("```swift") ? "  let x = 1  \r\n\r\n" : body.hasPrefix("~~~") ? "{\"a\":1}\n" : "no final newline"
      XCTAssertEqual(code.text, expected)
      XCTAssertEqual(value.source, body)
    }
  }

  func testBoundedReadRejectsOversizeAndBinaryWithoutTruncation() throws {
    let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: file) }
    try Data("# 超过读取预算\n".utf8).write(to: file)
    XCTAssertThrowsError(try PreviewDocument.load(file, maximumBytes: 3))
    XCTAssertThrowsError(try PreviewDocument.parse(Data([0, 1, 2])))
    XCTAssertThrowsError(try PreviewDocument.parse(Data([0xFF])))
    let utf16 = try XCTUnwrap("# 中文".data(using: .utf16))
    XCTAssertEqual(try PreviewDocument.parse(utf16).headings.first?.text, "中文")
  }

  func testLongInventoryKeepsEveryRowAndVersionChanges() throws {
    let source = "# Inventory\n\n| Tool | Detail |\n| --- | --- |\n" + (1...191).map { "| tool-\($0) | description \($0) |\n" }.joined()
    let first = try PreviewDocument.parse(Data(source.utf8))
    let table = try XCTUnwrap(first.blocks.first { if case .table = $0.kind { return true }; return false })
    XCTAssertEqual(table.rows.count, 192)
    XCTAssertTrue(table.rows.last?[0].contains("tool-191") == true)
    XCTAssertNotEqual(first.digest, try PreviewDocument.parse(Data((source + "changed").utf8)).digest)
  }
}
