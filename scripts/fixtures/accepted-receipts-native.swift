  @MainActor func testAcceptedReceiptsRecoveryDEV() async throws {
    let control = URL(string: "__RECEIPT_CONTROL__")!
    func command(_ name: String) async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: control.appendingPathComponent(name))
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    let (data, _) = try await URLSession.shared.data(from: control)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate(); app.launchArguments = ["-ui-testing"]; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let marker = "RECEIPT_NEW_" + fixture.nonce
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 40))
    XCTAssertFalse(app.staticTexts["消息执行结果待确认"].exists)
    let composer = app.descendants(matching: .any).matching(identifier: "composer-input").firstMatch
    XCTAssertTrue(composer.waitForExistence(timeout: 15))
    composer.tap(); composer.typeText("RECEIPT_DRAFT_" + fixture.nonce)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    shot("accepted-eight-after-host-restart")
    let settled = try await command("settle")
    XCTAssertEqual(settled["settled"] as? Int, 8)
    XCTAssertFalse(app.staticTexts["消息执行结果待确认"].exists)
    XCTAssertEqual(composer.value as? String, "RECEIPT_DRAFT_" + fixture.nonce)
    _ = try await command("unknown-on")
    XCTAssertTrue(app.staticTexts["消息执行结果待确认"].waitForExistence(timeout: 20))
    XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "消息执行结果待确认")).count, 1)
    XCTAssertEqual(composer.value as? String, "RECEIPT_DRAFT_" + fixture.nonce)
    shot("synthetic-unknown-still-visible")
    _ = try await command("unknown-off")
    let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.staticTexts["消息执行结果待确认"])
    XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 25), .completed)
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 30))
    XCTAssertEqual(composer.value as? String, "RECEIPT_DRAFT_" + fixture.nonce)
    XCTAssertFalse(app.staticTexts["消息执行结果待确认"].exists)
    shot("accepted-eight-relaunch-draft-preserved")
    _ = try await command("native-complete")
  }
