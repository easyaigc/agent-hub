import XCTest

// Requires a dedicated acceptance app and a prepared, disposable conversation.
// UI assertions are auxiliary evidence; a fixture/desktop receipt must independently
// count submissions, particularly for negative cases and exactly-once delivery.
final class ComposerSwipeSendUITests: XCTestCase {
  private var app: XCUIApplication!
  private var input: XCUIElement { app.descendants(matching: .any)["composer-input"].firstMatch }
  private var zone: XCUIElement { app.descendants(matching: .any)["composer-swipe-send"].firstMatch }

  override func setUpWithError() throws {
    continueAfterFailure = false
    guard let bundle = ProcessInfo.processInfo.environment["SWIPE_SEND_TEST_BUNDLE"],
          bundle == "com.easyai.agenthub.swipesend.acceptance" else {
      throw XCTSkip("Set SWIPE_SEND_TEST_BUNDLE to a dedicated acceptance bundle with a prepared disposable conversation")
    }
    app = XCUIApplication(bundleIdentifier: bundle)
    app.launchArguments = ["-ui-testing", "-voice-input-fixture"]
    app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
    app.launch()
    if app.buttons["close-sidebar"].waitForExistence(timeout: 5) { app.buttons["close-sidebar"].tap() }
    XCTAssertTrue(input.waitForExistence(timeout: 20), "Prepare an isolated conversation before running")
  }

  private func draft(_ text: String) {
    input.tap()
    input.typeText(text)
    XCTAssertTrue((input.value as? String ?? "").contains(text))
    XCTAssertTrue(zone.waitForExistence(timeout: 5))
    let screenshot = XCTAttachment(screenshot: app.screenshot())
    screenshot.name = "swipe-zone"; screenshot.lifetime = .keepAlways; add(screenshot)
  }

  private func drag(_ dx: CGFloat, _ dy: CGFloat) {
    let start = zone.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: dx, dy: dy)))
  }

  func testShortHorizontalAndTapPreserveDraft() {
    let marker = "swipe-negative-\(UUID().uuidString)"
    draft(marker)
    zone.tap()
    drag(0, -30)
    drag(100, -15)
    drag(0, 100)
    XCTAssertTrue((input.value as? String ?? "").contains(marker))
  }

  func testEditingAndScrollingTextDoNotSend() {
    let marker = "edit-\(UUID().uuidString)"
    draft(marker + String(repeating: " long text", count: 80))
    input.swipeUp()
    input.tap()
    input.typeText("Z")
    XCTAssertTrue((input.value as? String ?? "").contains(marker))
    XCTAssertTrue((input.value as? String ?? "").contains("Z"))
  }

  func testEmptyDraftHasNoSwipeZone() {
    // Run on a fresh disposable conversation, with no restored draft.
    XCTAssertFalse(zone.exists, "Empty or ghost suggestion must not expose a send region")
  }

  func testUpwardSwipeSubmitsDraft() {
    let marker = "swipe-positive-\(UUID().uuidString)"
    draft(marker)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    drag(0, -100)
    let cleared = XCTNSPredicateExpectation(predicate: NSPredicate { [self] _, _ in
      !(input.value as? String ?? "").contains(marker)
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [cleared], timeout: 10), .completed)
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 10))
    XCTAssertEqual(app.staticTexts.matching(identifier: marker).count, 1)
    // The caller must match this marker against the independent receipt exactly once.
    let attachment = XCTAttachment(string: marker)
    attachment.name = "submitted-marker"; attachment.lifetime = .keepAlways; add(attachment)
  }
  func testVoiceFixtureRemainsDraftUntilSwipe() {
    let marker = "voice-" + UUID().uuidString
    draft(marker)
    app.buttons["voice-input"].tap()
    let stop = app.buttons["stop-dictation"]
    XCTAssertTrue(stop.waitForExistence(timeout: 10))
    stop.tap()
    let expected = marker + "\n语音输入测试"
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { [self] _, _ in
      (input.value as? String ?? "").contains(expected)
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 35), .completed)
    XCTAssertFalse(app.staticTexts[expected].exists)
    drag(0, -100)
    XCTAssertTrue(app.staticTexts[expected].waitForExistence(timeout: 10))
    XCTAssertEqual(app.staticTexts.matching(identifier: expected).count, 1)
  }

}
