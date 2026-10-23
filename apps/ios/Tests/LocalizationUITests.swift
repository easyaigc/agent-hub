import XCTest

final class LocalizationUITests: XCTestCase {
  private let app = XCUIApplication()

  override func setUpWithError() throws {
    continueAfterFailure = false
  }

  private func launch(language: String, restore: Bool = false) {
    app.launchArguments = ["-ui-testing", "-AppleLanguages", "(\(language))", "-AppleLocale", language == "en" ? "en_US" : "zh_CN"]
    if restore { app.launchArguments.append("-restore-testing") }
    app.launch()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
  }

  private func settings() {
    app.buttons["open-sidebar"].tap()
    let button = app.buttons["open-settings"]
    XCTAssertTrue(button.waitForExistence(timeout: 5))
    button.tap()
    XCTAssertTrue(app.buttons["language-picker"].waitForExistence(timeout: 5))
  }

  private func choose(_ title: String) {
    app.buttons["language-picker"].tap()
    app.buttons[title].firstMatch.tap()
  }

  private func screenshot(_ name: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }

  func testSwitchLanguagePreservesDraftAndPersists() {
    launch(language: "en")
    settings()
    choose("English")
    XCTAssertTrue(app.navigationBars["Settings"].exists)
    screenshot("english-settings")
    app.buttons["Done"].tap()
    let input = app.descendants(matching: .any).matching(identifier: "composer-input").firstMatch
    XCTAssertTrue(input.waitForExistence(timeout: 5))
    input.tap()
    input.typeText("Draft 100% English")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    settings()
    choose("中文")
    XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
    screenshot("chinese-settings")
    app.buttons["完成"].tap()
    XCTAssertEqual(input.value as? String, "Draft 100% English")
    screenshot("chinese-draft-preserved")
    app.terminate()
    launch(language: "en", restore: true)
    XCTAssertEqual(input.value as? String, "Draft 100% English")
    settings()
    XCTAssertTrue(app.navigationBars["设置"].exists)
    choose("English")
    XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    app.buttons["Done"].tap()
    XCTAssertEqual(input.value as? String, "Draft 100% English")
    app.buttons["client-switch"].tap()
    XCTAssertTrue(app.buttons["add-client"].waitForExistence(timeout: 5))
    XCTAssertEqual(app.buttons["add-client"].label, "Add client")
    screenshot("english-clients")
  }

  func testFollowSystemUsesChineseAndEnglish() {
    launch(language: "en")
    settings()
    // The saved choice may be either language from a preceding test.
    app.buttons["language-picker"].tap()
    let system = app.buttons["System"].firstMatch
    if system.exists { system.tap() } else { app.buttons["跟随系统"].firstMatch.tap() }
    XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    app.terminate()
    launch(language: "zh-Hans", restore: true)
    settings()
    XCTAssertTrue(app.navigationBars["设置"].exists)
    screenshot("system-chinese-settings")
    app.terminate()
    launch(language: "en", restore: true)
    settings()
    XCTAssertTrue(app.navigationBars["Settings"].exists)
    screenshot("system-english-settings")
  }

  @MainActor func testOriginalDesktopLanguageSwitchDEV() async throws {
    let environment = ProcessInfo.processInfo.environment
    guard let address = environment["LANGUAGE_DEV_FIXTURE"] ?? environment["TEST_RUNNER_LANGUAGE_DEV_FIXTURE"],
      let url = URL(string: address) else { throw XCTSkip("Requires the dedicated real desktop DEV harness") }
    let (data, _) = try await URLSession.shared.data(from: url)
    let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    let endpoint = try XCTUnwrap(fixture["endpoint"])
    let token = try XCTUnwrap(fixture["token"])
    let marker = try XCTUnwrap(fixture["marker"])
    launch(language: "en")
    settings(); choose("English"); app.buttons["Done"].tap()
    app.buttons["client-switch"].tap()
    let add = app.buttons["add-client"]
    for _ in 0..<6 where !add.isHittable { app.swipeUp() }
    add.tap()
    app.buttons["codex-connection-method"].tap()
    app.buttons["Manual connection"].tap()
    let field = app.textFields["client-address"]
    XCTAssertTrue(field.waitForExistence(timeout: 10))
    field.tap()
    field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (field.value as? String ?? "").count))
    field.typeText(endpoint)
    app.buttons["Done typing"].tap()
    app.secureTextFields["client-token"].tap()
    app.secureTextFields["client-token"].typeText(token)
    app.buttons["Done typing"].tap()
    app.buttons["connect-client"].tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.secureTextFields["client-token"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 90), .completed)
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: app.buttons["new-chat"])
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    app.buttons["new-chat"].tap()
    XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 15))
    app.buttons["choose-projectless"].tap()
    app.buttons["generation-settings"].tap()
    app.descendants(matching: .any).matching(identifier: "effort-picker").firstMatch.tap()
    app.buttons["Extra high"].tap()
    app.buttons["generation-done"].tap()
    let input = app.descendants(matching: .any).matching(identifier: "composer-input").firstMatch
    let prompt = "Reply only \(marker). Do not use tools."
    input.tap(); input.typeText(prompt)
    app.buttons["composer-dismiss-keyboard"].tap()
    settings(); choose("中文"); app.buttons["完成"].tap()
    XCTAssertEqual(input.value as? String, prompt)
    screenshot("real-desktop-chinese-draft")
    app.buttons["send-message"].tap()
    let allow = app.buttons["ai-sharing-allow"]
    if allow.waitForExistence(timeout: 3) {
      for _ in 0..<6 where !allow.isHittable { app.swipeUp() }
      allow.tap()
      let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: allow)
      XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed)
      let send = app.buttons["send-message"]
      let sendReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: send)
      XCTAssertEqual(XCTWaiter.wait(for: [sendReady], timeout: 10), .completed)
      send.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 180))
    screenshot("real-desktop-chinese-result")
    settings(); choose("English"); app.buttons["Done"].tap()
    XCTAssertTrue(app.staticTexts[marker].exists)
    screenshot("real-desktop-english-result")
  }
}
