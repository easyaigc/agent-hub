import XCTest

/// Synthetic capture with the real controller, navigation, draft store and scene lifecycle.
/// These tests do not claim microphone or upstream ASR coverage.
@MainActor
final class VoiceLifecycleUITests: XCTestCase {
  private var app: XCUIApplication!
  private var composer: XCUIElement {
    app.descendants(matching: .any).matching(identifier: "composer-input").firstMatch
  }

  override func setUpWithError() throws {
    continueAfterFailure = false
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture"]
    app.launchEnvironment["VOICE_TRANSCRIPTION_FIXTURE_DELAY_MS"] = "20000"
    app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 15))
  }

  override func tearDownWithError() throws { app.terminate() }

  private func wait(_ predicate: String, on element: XCUIElement, timeout: TimeInterval = 10) {
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(
      predicate: NSPredicate(format: predicate), object: element)], timeout: timeout), .completed)
  }

  private func start(_ draft: String = "lifecycle-original") {
    composer.tap(); composer.typeText(draft)
    app.buttons["voice-input"].tap()
    let stop = app.buttons["stop-dictation"]
    wait("exists == true AND isEnabled == true", on: stop)
    stop.tap()
    wait("exists == true AND isEnabled == false", on: stop)
    XCTAssertFalse(app.staticTexts["voice-failure-title"].exists)
    screenshot("transcribing")
  }

  private func completed(_ draft: String = "lifecycle-original") {
    XCTAssertTrue(composer.waitForExistence(timeout: 35))
    let expected = draft + "\n语音输入测试"
    let finished = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      self.composer.value as? String == expected
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 35), .completed)
    XCTAssertEqual(composer.value as? String, draft + "\n语音输入测试")
    XCTAssertFalse(app.staticTexts["voice-failure-title"].exists)
    XCTAssertFalse(app.buttons["stop-dictation"].exists)
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label == %@", draft + "\n语音输入测试")).firstMatch.exists,
      "Transcription is an unsent draft")
    screenshot("completed-unsent")
  }

  private func openSidebar() {
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 10))
  }
  private func closeSidebar() {
    app.buttons["close-sidebar"].tap()
    XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
  }
  private func screenshot(_ suffix: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name + "-" + suffix; attachment.lifetime = .keepAlways
    add(attachment)
  }

  func testTranscriptionSurvivesSidebarButtonAndSearch() {
    start()
    openSidebar()
    app.buttons["sidebar-search-toggle"].tap()
    let search = app.textFields["sidebar-search"]
    XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("导航")
    screenshot("sidebar-search-during-transcription")
    closeSidebar()
    completed()
  }

  func testTranscriptionSurvivesSidebarDragAndCancelledDrag() {
    start()
    let edge = app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.45))
    let partial = app.coordinate(withNormalizedOffset: CGVector(dx: 0.07, dy: 0.45))
    edge.press(forDuration: 0.1, thenDragTo: partial, withVelocity: .slow, thenHoldForDuration: 0.4)
    if app.buttons["close-sidebar"].exists { closeSidebar() }
    edge.press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.45)))
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    screenshot("sidebar-drag-during-transcription")
    closeSidebar()
    completed()
  }

  func testTranscriptionSurvivesSettingsSheet() {
    start()
    openSidebar(); app.buttons["open-settings"].tap()
    let done = app.buttons["完成"].firstMatch
    XCTAssertTrue(done.waitForExistence(timeout: 5))
    screenshot("settings-during-transcription")
    done.tap()
    if app.buttons["close-sidebar"].exists { closeSidebar() }
    completed()
  }

  func testTranscriptionSurvivesMinimizing() {
    start()
    XCUIDevice.shared.press(.home)
    XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10))
    // Keep the app backgrounded long enough to exercise its scene handler.
    let until = Date().addingTimeInterval(3)
    while Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    app.activate()
    completed()
  }

  func testTranscriptionStaysWithOriginalConversation() {
    start()
    openSidebar()
    let other = app.buttons["recent-thread-query"].exists ? app.buttons["recent-thread-query"] : app.buttons["thread-query"]
    XCTAssertTrue(other.waitForExistence(timeout: 5)); other.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    XCTAssertFalse((composer.value as? String ?? "").contains("lifecycle-original"))
    composer.tap(); composer.typeText("other-draft")
    // The original job may finish while another draft is being edited.
    let until = Date().addingTimeInterval(21)
    while Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    XCTAssertEqual(composer.value as? String, "other-draft")
    openSidebar()
    let original = app.buttons["recent-thread-navigation"].exists ? app.buttons["recent-thread-navigation"] : app.buttons["thread-navigation"]
    XCTAssertTrue(original.waitForExistence(timeout: 5)); original.tap()
    completed()
  }

  func testTranscriptionStaysWithOriginalClient() {
    start()
    app.buttons["client-switch"].tap()
    app.buttons["client-demo-studio"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    composer.tap(); composer.typeText("studio-draft")
    let until = Date().addingTimeInterval(21)
    while Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    XCTAssertEqual(composer.value as? String, "studio-draft")
    app.buttons["client-switch"].tap()
    app.buttons["client-demo-mac"].tap()
    completed()
  }

  func testDiscardStillRejectsLateTranscription() {
    start()
    app.buttons["cancel-dictation"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "lifecycle-original")
    composer.tap(); composer.typeText("-new-edit")
    let editedDraft = composer.value as? String
    XCTAssertTrue(editedDraft?.contains("lifecycle-original") == true)
    XCTAssertTrue(editedDraft?.contains("-new-edit") == true)
    let until = Date().addingTimeInterval(21)
    while Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    XCTAssertEqual(composer.value as? String, editedDraft)
    XCTAssertFalse((composer.value as? String ?? "").contains("语音输入测试"))
    XCTAssertFalse(app.buttons["stop-dictation"].exists)
    screenshot("discarded-late-result-rejected")
  }
}
