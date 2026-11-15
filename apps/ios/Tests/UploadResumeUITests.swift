import XCTest

final class UploadResumeUITests: XCTestCase {
  override func setUpWithError() throws {
    try super.setUpWithError()
    guard ProcessInfo.processInfo.environment["UPLOAD_FIXTURE_URL"] != nil else {
      throw XCTSkip("Run with the dedicated upload acceptance fixture.")
    }
  }
  private let base = URL(string: ProcessInfo.processInfo.environment["UPLOAD_FIXTURE_URL"] ?? "http://127.0.0.1:18763")!
  private func json(_ path: String) throws -> [String: Any] {
    let expectation = expectation(description: path)
    var output: [String: Any]?
    URLSession.shared.dataTask(with: base.appendingPathComponent(path)) { data, _, _ in
      if let data { output = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }
      expectation.fulfill()
    }.resume()
    wait(for: [expectation], timeout: 10)
    return try XCTUnwrap(output, path)
  }
  private func count(_ state: [String: Any]) -> Int { state["committed"] as? Int ?? 0 }
  private func waitForBytes(_ minimum: Int, timeout: TimeInterval = 45) throws -> [String: Any] {
    let end = Date().addingTimeInterval(timeout)
    repeat {
      let state = try json("control/state")
      if count(state) >= minimum { return state }
      Thread.sleep(forTimeInterval: 0.4)
    } while Date() < end
    XCTFail("fixture did not reach \(minimum) bytes")
    return try json("control/state")
  }
  @MainActor private func recordUI(_ app: XCUIApplication, label: String) {
    let screenshot = XCTAttachment(screenshot: app.screenshot())
    screenshot.name = "upload-diagnostic-\(label)-screen"; screenshot.lifetime = .keepAlways; add(screenshot)
    let hierarchy = app.debugDescription
    print("UPLOAD_UI_HIERARCHY_\(label): \(hierarchy)")
    let detail = XCTAttachment(string: hierarchy)
    detail.name = "upload-diagnostic-\(label)-hierarchy"; detail.lifetime = .keepAlways; add(detail)
  }
  @MainActor func testUploadPauseDisconnectAndRestart() throws {
    continueAfterFailure = false
    let config = try json("control/config")
    let manifest = try XCTUnwrap(config["manifest"] as? [String: Any])
    let id = try XCTUnwrap(manifest["id"] as? String)
    let encoded = try JSONSerialization.data(withJSONObject: config)
    let app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.uploadresume.acceptance")
    app.launchArguments = ["-upload-resume-acceptance", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launchEnvironment["UPLOAD_ACCEPTANCE_CONFIG"] = String(decoding: encoded, as: UTF8.self)
    app.launch()
    defer { app.terminate() }
    let start = app.buttons["acceptance-start-upload"]
    XCTAssertTrue(start.waitForExistence(timeout: 20)); start.tap()
    _ = try waitForBytes(2 * 1024 * 1024)
    recordUI(app, label: "resume-before-pause")
    let pause = app.buttons["upload-pause-resume-" + id]
    XCTAssertTrue(pause.waitForExistence(timeout: 10), "pause control missing: \(app.debugDescription)")
    XCTAssertGreaterThanOrEqual(pause.frame.height, 44, "pause touch target must be at least 44pt")
    let speedDeadline = Date().addingTimeInterval(10)
    var speedLabel: String?
    repeat {
      speedLabel = app.staticTexts.allElementsBoundByIndex.map(\.label).first { $0.hasSuffix("/s") }
      if speedLabel == nil { Thread.sleep(forTimeInterval: 0.3) }
    } while speedLabel == nil && Date() < speedDeadline
    let speed = try XCTUnwrap(speedLabel, "speed must be visible: \(app.debugDescription)")
    let expression = try NSRegularExpression(pattern: #"^([0-9]+(?:\.[0-9]+)?)\s*(B|kB|KB|MB|GB)/s$"#)
    let match = try XCTUnwrap(expression.firstMatch(in: speed, range: NSRange(speed.startIndex..., in: speed)), "unrecognized speed: \(speed)")
    let value = try XCTUnwrap(Double(String(speed[try XCTUnwrap(Range(match.range(at: 1), in: speed))])))
    let unit = String(speed[try XCTUnwrap(Range(match.range(at: 2), in: speed))])
    XCTAssertNotEqual(unit, "GB", "weak fixture cannot sustain gigabytes per second")
    let multiplier: Double = unit == "MB" ? 1_000_000 : ((unit == "kB" || unit == "KB") ? 1_000 : 1)
    XCTAssertGreaterThan(value, 0)
    XCTAssertLessThanOrEqual(value * multiplier, 5_000_000, "180ms per 256KiB cannot sustain this speed: \(speed)")
    let labels = app.staticTexts.allElementsBoundByIndex.map(\.label)
    XCTAssertFalse(labels.contains { $0.range(of: #"\b0\s+(?:seconds?|秒)"#, options: .regularExpression) != nil }, "remaining time must not be zero: \(labels)")
    let visible = app.debugDescription
    XCTAssertTrue(visible.contains("64") || visible.contains("67"), "byte total must be visible")
    let screenshot = XCTAttachment(screenshot: app.screenshot()); screenshot.name = "upload-in-progress"; screenshot.lifetime = .keepAlways; add(screenshot)
    pause.tap()
    Thread.sleep(forTimeInterval: 2)
    let paused = try json("control/state")
    Thread.sleep(forTimeInterval: 3)
    XCTAssertEqual(count(try json("control/state")), count(paused), "pause must stop durable progress")
    XCTAssertGreaterThan(count(paused), 0)
    pause.tap()
    _ = try waitForBytes(count(paused) + 1024 * 1024)
    let disconnected = try waitForBytes(20 * 1024 * 1024, timeout: 150)
    XCTAssertEqual(disconnected["disconnects"] as? Int, 1, "fixture must interrupt one actual response")
    XCTAssertEqual(disconnected["timeouts"] as? Int, 1, "fixture must observe the timed-out request")
    app.terminate()
    _ = try json("control/restart")
    let before = count(try json("control/state"))
    XCTAssertGreaterThan(before, 0)
    app.launch()
    let resume = app.buttons["upload-pause-resume-" + id]
    XCTAssertTrue(resume.waitForExistence(timeout: 20), "journal must restore the job")
    resume.tap()
    let done = try waitForBytes(64 * 1024 * 1024, timeout: 180)
    XCTAssertEqual(count(done), 64 * 1024 * 1024)
    let deadline = Date().addingTimeInterval(30)
    var final = done
    while Date() < deadline {
      final = try json("control/state")
      if final["phase"] as? String == "completed" { break }
      Thread.sleep(forTimeInterval: 0.5)
    }
    XCTAssertEqual(final["phase"] as? String, "completed")
    XCTAssertEqual(final["publications"] as? Int, 1)
    XCTAssertEqual(final["hashOK"] as? Bool, true)
    XCTAssertEqual(final["resumedAfterRestart"] as? Bool, true)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "complete")).firstMatch.waitForExistence(timeout: 15))
    let completed = XCTAttachment(screenshot: app.screenshot()); completed.name = "upload-completed"; completed.lifetime = .keepAlways; add(completed)
  }
  @MainActor func testCancelResponseLostAndRestart() throws {
    continueAfterFailure = false
    let config = try json("control/config")
    let manifest = try XCTUnwrap(config["manifest"] as? [String: Any])
    let id = try XCTUnwrap(manifest["id"] as? String)
    let app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.uploadresume.acceptance")
    app.launchArguments = ["-upload-resume-acceptance", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launchEnvironment["UPLOAD_ACCEPTANCE_CONFIG"] = String(decoding: try JSONSerialization.data(withJSONObject: config), as: UTF8.self)
    app.launch()
    defer { app.terminate() }
    let start = app.buttons["acceptance-start-upload"]
    XCTAssertTrue(start.waitForExistence(timeout: 20)); start.tap()
    _ = try waitForBytes(2 * 1024 * 1024)
    recordUI(app, label: "cancel-before-action")
    let cancel = app.buttons["upload-cancel-" + id]
    XCTAssertTrue(cancel.waitForExistence(timeout: 15), "cancel control missing: \(app.debugDescription)")
    XCTAssertGreaterThanOrEqual(cancel.frame.height, 44, "cancel touch target must be at least 44pt")
    cancel.tap()
    let deadline = Date().addingTimeInterval(20)
    var state = try json("control/state")
    while state["cancelDropped"] as? Bool != true && Date() < deadline {
      Thread.sleep(forTimeInterval: 0.3); state = try json("control/state")
    }
    XCTAssertEqual(state["cancelDropped"] as? Bool, true, "cancel response was not lost")
    XCTAssertEqual(state["cancelBusyInjected"] as? Int, 1, "fixture must reject one busy cancellation")
    XCTAssertGreaterThanOrEqual(state["cancelRequests"] as? Int ?? 0, 2, "client must reconcile and retry busy cancellation")
    app.terminate()
    _ = try json("control/restart")
    let stoppedBytes = count(try json("control/state"))
    app.launch()
    recordUI(app, label: "cancel-after-restart")
    let query = app.buttons["upload-pause-resume-" + id]
    XCTAssertTrue(query.waitForExistence(timeout: 20), "pending cancellation must be restored: \(app.debugDescription)")
    query.tap()
    let queryDeadline = Date().addingTimeInterval(20)
    repeat {
      Thread.sleep(forTimeInterval: 0.4); state = try json("control/state")
    } while (state["statusAfterRestart"] as? Int ?? 0) == 0 && Date() < queryDeadline
    Thread.sleep(forTimeInterval: 3)
    state = try json("control/state")
    XCTAssertGreaterThan(state["statusAfterRestart"] as? Int ?? 0, 0)
    XCTAssertEqual(state["chunksAfterCancel"] as? Int, 0, "must not send bytes after cancellation")
    XCTAssertEqual(count(state), stoppedBytes, "committed bytes must not grow after restart")
    XCTAssertEqual(state["cancelsAfterRestart"] as? Int, 0, "recovery should query, not repeat cancellation")
    XCTAssertEqual(state["publications"] as? Int, 0)
  }

  @MainActor func testVerifyingCancellationRecovery() throws {
    continueAfterFailure = false
    let config = try json("control/config")
    let manifest = try XCTUnwrap(config["manifest"] as? [String: Any])
    let id = try XCTUnwrap(manifest["id"] as? String)
    let app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.uploadresume.acceptance")
    app.launchArguments = ["-upload-resume-acceptance", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launchEnvironment["UPLOAD_ACCEPTANCE_CONFIG"] = String(decoding: try JSONSerialization.data(withJSONObject: config), as: UTF8.self)
    app.launch()
    defer { app.terminate() }
    let start = app.buttons["acceptance-start-upload"]
    XCTAssertTrue(start.waitForExistence(timeout: 20)); start.tap()
    let deadline = Date().addingTimeInterval(45)
    var state = try json("control/state")
    while state["publicationUncertain"] as? Bool != true && Date() < deadline {
      Thread.sleep(forTimeInterval: 0.2); state = try json("control/state")
    }
    XCTAssertEqual(state["publicationUncertain"] as? Bool, true, "fixture must persist publication before losing its result")
    XCTAssertEqual(state["phase"] as? String, "verifying")
    XCTAssertEqual(state["publications"] as? Int, 1)
    recordUI(app, label: "verifying-before-cancel")
    let cancel = app.buttons["upload-cancel-" + id]
    XCTAssertTrue(cancel.waitForExistence(timeout: 10), "cancel control missing: \(app.debugDescription)")
    cancel.tap()
    let conflictDeadline = Date().addingTimeInterval(20)
    repeat { Thread.sleep(forTimeInterval: 0.2); state = try json("control/state") }
    while (state["cancelConflict"] as? Int ?? 0) == 0 && Date() < conflictDeadline
    XCTAssertGreaterThan(state["cancelConflict"] as? Int ?? 0, 0, "production store must reject cancellation during publication")
    let blockedDeadline = Date().addingTimeInterval(20)
    repeat { Thread.sleep(forTimeInterval: 0.2); state = try json("control/state") }
    while state["recoveryCompleteBlocked"] as? Bool != true && Date() < blockedDeadline
    XCTAssertEqual(state["recoveryCompleteBlocked"] as? Bool, true, "fixture must interrupt the first completion reconciliation")
    app.terminate()
    _ = try json("control/restart")
    app.launch()
    let query = app.buttons["upload-pause-resume-" + id]
    XCTAssertTrue(query.waitForExistence(timeout: 20), "pending cancellation must survive restart: \(app.debugDescription)")
    query.tap()
    let recoveryDeadline = Date().addingTimeInterval(30)
    repeat { Thread.sleep(forTimeInterval: 0.3); state = try json("control/state") }
    while state["phase"] as? String != "completed" && Date() < recoveryDeadline
    XCTAssertEqual(state["phase"] as? String, "completed")
    XCTAssertGreaterThan(state["statusAfterRestart"] as? Int ?? 0, 0)
    XCTAssertGreaterThan(state["recoveryCompletes"] as? Int ?? 0, 0)
    XCTAssertEqual(state["publications"] as? Int, 1)
    XCTAssertEqual(state["hashOK"] as? Bool, true)
    XCTAssertEqual(state["chunksAfterPublication"] as? Int, 0)
    XCTAssertFalse(app.staticTexts["验收上传完成"].exists, "cancelled caller must not report successful completion")
  }

}
