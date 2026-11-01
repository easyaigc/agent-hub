import XCTest

final class RecentProjectsUITests: XCTestCase {
  private var app: XCUIApplication!

  override func setUpWithError() throws {
    continueAfterFailure = false
    guard let bundle = ProcessInfo.processInfo.environment["RECENT_PROJECTS_TEST_BUNDLE"],
          bundle == "com.easyai.agenthub.recentprojects" else {
      throw XCTSkip("Requires the dedicated recent projects acceptance bundle")
    }
    app = XCUIApplication(bundleIdentifier: bundle)
  }

  @MainActor private func chooseSheet() {
    let button = app.buttons["new-chat"]
    XCTAssertTrue(button.waitForExistence(timeout: 20))
    button.tap()
    XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10))
  }

  @MainActor private func switchComputer(_ id: String) {
    app.buttons["open-sidebar"].tap()
    app.buttons["sidebar-client-switch"].tap()
    let computer = app.buttons["client-demo-" + id]
    XCTAssertTrue(computer.waitForExistence(timeout: 5))
    computer.tap()
  }

  @MainActor private func screenshot(_ name: String) {
    let shot = XCTAttachment(screenshot: app.screenshot())
    shot.name = name
    shot.lifetime = .keepAlways
    add(shot)
  }

  @MainActor func testRecentProjectsOrderingIsolationAndPersistence() {
    app.launchArguments = ["-ui-testing", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
    app.launch()
    chooseSheet()
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'choose-recent-project-'")).count, 0)
    app.buttons["choose-projectless"].tap()
    chooseSheet()
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'choose-recent-project-'")).count, 0)
    app.buttons["取消"].tap()
    chooseSheet()
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'choose-recent-project-'")).count, 0)
    app.buttons["choose-project-mobile"].tap()
    chooseSheet()
    app.buttons["choose-project-api"].tap()
    chooseSheet()
    let mobile = app.buttons["choose-recent-project-mobile"]
    let api = app.buttons["choose-recent-project-api"]
    XCTAssertTrue(mobile.waitForExistence(timeout: 5))
    XCTAssertTrue(api.frame.minY < mobile.frame.minY)
    mobile.tap()
    XCTAssertTrue(app.buttons["client-switch"].label.contains("Agent Hub"))
    XCTAssertTrue(app.staticTexts["有什么可以帮忙的？"].waitForExistence(timeout: 5))
    chooseSheet()
    XCTAssertEqual(app.buttons.matching(identifier: "choose-recent-project-mobile").count, 1)
    XCTAssertTrue(mobile.frame.minY < api.frame.minY)
    screenshot("recent-projects-reordered")
    app.buttons["取消"].tap()
    switchComputer("studio")
    chooseSheet()
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'choose-recent-project-'")).count, 0)
    app.buttons["choose-project-web"].tap()
    chooseSheet()
    XCTAssertTrue(app.buttons["choose-recent-project-web"].exists)
    screenshot("recent-projects-studio")
    app.buttons["取消"].tap()
    switchComputer("mac")
    chooseSheet()
    XCTAssertTrue(mobile.waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["choose-recent-project-web"].exists)
    app.buttons["取消"].tap()
    app.terminate()
    app.launchArguments.append("-restore-testing")
    app.launch()
    chooseSheet()
    XCTAssertTrue(mobile.waitForExistence(timeout: 5))
    XCTAssertTrue(mobile.frame.minY < api.frame.minY)
    screenshot("recent-projects-restored")
    app.buttons["取消"].tap()
    app.buttons["open-sidebar"].tap()
    app.buttons["sidebar-search-toggle"].tap()
    let search = app.textFields["sidebar-search"]
    XCTAssertTrue(search.waitForExistence(timeout: 5))
    search.tap()
    search.typeText("查询")
    let thread = app.buttons["thread-query"]
    XCTAssertTrue(thread.waitForExistence(timeout: 5))
    thread.tap()
    XCTAssertTrue(app.buttons["client-switch"].label.contains("api-service"))
    chooseSheet()
    XCTAssertTrue(api.waitForExistence(timeout: 5))
    XCTAssertTrue(api.frame.minY < mobile.frame.minY)
    screenshot("recent-projects-opened-thread")
  }
}
