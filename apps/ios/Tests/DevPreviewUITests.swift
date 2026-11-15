import XCTest

final class DevPreviewUITests: XCTestCase {
  private var base: URL { URL(string: ProcessInfo.processInfo.environment["DEV_PREVIEW_CONTROL_URL"]!)! }
  private func request(_ path: String, method: String = "GET") throws -> [String: Any] {
    let done = expectation(description: path)
    var output: [String: Any]?
    var status = 0
    var request = URLRequest(url: base.appendingPathComponent(path))
    request.httpMethod = method
    URLSession.shared.dataTask(with: request) { data, response, _ in
      status = (response as? HTTPURLResponse)?.statusCode ?? 0
      if let data { output = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }
      done.fulfill()
    }.resume()
    wait(for: [done], timeout: 20)
    XCTAssertEqual(status, 200, "control request failed: \(path)")
    return try XCTUnwrap(output, path)
  }
  @MainActor func testPreviewPageApiHMRAndClose() throws {
    continueAfterFailure = false
    let config = try request("control/config")
    let nonce = try XCTUnwrap(config["nonce"] as? String)
    let appConfig = try XCTUnwrap(config["appConfig"] as? [String: Any])
    let data = try JSONSerialization.data(withJSONObject: appConfig)
    let app = XCUIApplication()
    let easywork = config["mode"] as? String == "easywork"
    app.launchArguments = [easywork ? "-easywork-dev-preview-acceptance" : "-dev-preview-acceptance", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launchEnvironment[easywork ? "EASYWORK_PREVIEW_CONFIG" : "DEV_PREVIEW_CONFIG"] = String(decoding: data, as: UTF8.self)
    app.launch()
    let pageA = app.staticTexts["PAGE-\(nonce)-A"]
    XCTAssertTrue(pageA.waitForExistence(timeout: 40), "initial Vite page absent: \(app.debugDescription)")
    for labels in [["Back", "后退"], ["Forward", "前进"], ["Reload", "刷新"]] {
      XCTAssertTrue(labels.contains { app.buttons[$0].exists }, "missing \(labels) control: \(app.debugDescription)")
    }
    let api = app.buttons["Call API"]
    XCTAssertTrue(api.waitForExistence(timeout: 10)); api.tap()
    XCTAssertTrue(app.staticTexts["API-\(nonce)"].waitForExistence(timeout: 15), "same-origin API response absent")
    _ = try request("control/change", method: "POST")
    XCTAssertTrue(app.staticTexts["PAGE-\(nonce)-B"].waitForExistence(timeout: 30), "Vite HMR update absent without reload: \(app.debugDescription)")
    let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "dev-preview-vite-hmr"; screenshot.lifetime = .keepAlways; add(screenshot)
    let close = app.buttons["dev-preview-close"]
    XCTAssertTrue(close.waitForExistence(timeout: 10), "close control missing: \(app.debugDescription)")
    close.tap()
    _ = try request("control/closed", method: "POST")
  }
}
