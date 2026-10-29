import XCTest

final class UsageUITests: XCTestCase {
  @MainActor func testRealHostUsageFooterPickerAndSettings() throws {
    continueAfterFailure = false
    guard ProcessInfo.processInfo.environment["USAGE_ACCEPTANCE_BUNDLE"] == "com.easyai.agenthub.usage.acceptance",
      let raw = ProcessInfo.processInfo.environment["USAGE_ACCEPTANCE_FIXTURE"],
      let fixture = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
      throw XCTSkip("Requires the independent F73 App and a real, isolated Host fixture")
    }
    let app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.usage.acceptance")
    app.launchArguments = ["-ui-testing", "-usage-acceptance", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
    app.launchEnvironment["USAGE_ACCEPTANCE_FIXTURE"] = raw
    addUIInterruptionMonitor(withDescription: "F73 network permission") { alert in
      let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "Allow", "无线局域网与蜂窝网络", "无线局域网与蜂窝数据"])).firstMatch
      if allow.exists { allow.tap(); return true }; return false
    }
    app.launch()
    let settings = app.buttons["open-settings"], usage = app.buttons["current-client-usage"], collapse = app.buttons["close-sidebar"]
    XCTAssertTrue(settings.waitForExistence(timeout: 30))
    XCTAssertTrue(usage.waitForExistence(timeout: 30)); XCTAssertTrue(collapse.exists)
    let loaded = NSPredicate { _, _ in usage.label.contains("7d") }
    expectation(for: loaded, evaluatedWith: usage)
    waitForExpectations(timeout: 30)
    XCTAssertLessThan(settings.frame.maxX, usage.frame.midX)
    XCTAssertLessThan(usage.frame.midX, collapse.frame.midX)
    XCTAssertFalse(app.staticTexts["个人空间"].exists)
    shot("f73-real-host-footer")
    usage.tap()
    XCTAssertTrue(app.navigationBars["当前客户端额度"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.staticTexts["7d"].exists)
    shot("f73-real-host-window-detail")
    if fixture["expectedBalance"] as? Bool == true {
      for _ in 0..<3 where !app.staticTexts["Credits"].exists { app.swipeUp() }
      XCTAssertTrue(app.staticTexts["Credits"].exists)
    }
    shot("f73-real-host-detail")
    app.buttons["完成"].tap()
    app.buttons["sidebar-client-switch"].tap()
    XCTAssertTrue(app.staticTexts["Usage DEV"].waitForExistence(timeout: 10))
    shot("f73-real-host-client-picker")
    let done = app.buttons["完成"]; if done.exists { done.tap() } else { app.swipeDown() }
    XCTAssertTrue(settings.waitForExistence(timeout: 10));settings.tap()
    XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 10))
    shot("f73-settings-independent")
    app.buttons["完成"].tap()
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(collapse.waitForExistence(timeout: 10)); collapse.tap()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10))
  }
  @MainActor func testRealEasyWorkUsageBothTransports() async throws {
    continueAfterFailure = false
    let env = ProcessInfo.processInfo.environment
    let bundle = "com.easyai.agenthub.usageew.acceptance"
    guard env["USAGE_ACCEPTANCE_BUNDLE"] == bundle,
      let raw = env["USAGE_EASYWORK_CONTEXT"],
      let config = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
      let port = config["observerPort"] as? Int, let auth = config["authorization"] as? String,
      let relayPort = config["relayPort"] as? Int else {
      throw XCTSkip("Requires the independent usage App and owned real EasyWork DEV observer")
    }
    func observer(_ path: String) async throws -> [String: Any] {
      var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/\(path)")!)
      req.setValue(auth, forHTTPHeaderField: "x-easywork-acceptance")
      let (data, response) = try await URLSession.shared.data(for: req)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func control(_ operation: String) async throws {
      var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/control")!)
      req.httpMethod = "POST"; req.setValue(auth, forHTTPHeaderField: "x-easywork-acceptance")
      req.setValue("application/json", forHTTPHeaderField: "content-type")
      req.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (data, response) = try await URLSession.shared.data(for: req)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      let reply = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
      let id = try XCTUnwrap(reply["id"] as? String)
      for _ in 0..<30 {
        let state = try await observer("evidence")
        if let receipt = (state["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) {
          XCTAssertEqual(receipt["accepted"] as? Bool, true); return
        }
        try await Task.sleep(for: .milliseconds(500))
      }
      XCTFail("Owned desktop control did not complete")
    }
    let initial = try await observer("evidence")
    let sample = try XCTUnwrap((initial["usageSamples"] as? [[String: Any]])?.last)
    XCTAssertEqual(sample["authorityOrigin"] as? String, "https://51easyai.com")
    let expected = try XCTUnwrap(config["displayBalance"] as? String)
    let exact = try XCTUnwrap(config["exactBalance"] as? String)
    let app = XCUIApplication(bundleIdentifier: bundle)
    app.launchArguments = ["-ui-testing", "-easywork-local-dev", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
    let lan = try XCTUnwrap(initial["lan"] as? [String: Any])
    let offer = try XCTUnwrap(initial["relayOffer"] as? [String: Any])
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": try XCTUnwrap(lan["pairingUri"] as? String),
      "EASYWORK_DEV_RELAY_URL": "http://127.0.0.1:\(relayPort)",
      "EASYWORK_DEV_RELAY_CODE": try XCTUnwrap(offer["pairingCode"] as? String)]
    app.launch()
    func chooseMethod(_ method: String) {
      let picker = app.descendants(matching: .any).matching(identifier: "easywork-connection-method").firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 10)); picker.tap()
      app.buttons[method].firstMatch.tap()
    }
    func addEasyWork() {
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
      app.buttons["client-switch"].tap()
      for _ in 0..<4 where !app.buttons["add-client"].isHittable { app.swipeUp() }
      XCTAssertTrue(app.buttons["add-client"].waitForExistence(timeout: 10)); app.buttons["add-client"].tap()
      XCTAssertTrue(app.navigationBars["添加客户端"].waitForExistence(timeout: 10))
      app.buttons["EasyWork"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.textFields["easywork-name"].waitForExistence(timeout: 10))
    }
    func waitConnected() {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND label CONTAINS 'EasyWork'"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 90), .completed)
    }
    func footer(_ route: String) async throws {
      if app.buttons["open-sidebar"].isHittable { app.buttons["open-sidebar"].tap() }
      let usage = app.buttons["current-client-usage"], settings = app.buttons["open-settings"], collapse = app.buttons["close-sidebar"]
      XCTAssertTrue(usage.waitForExistence(timeout: 30))
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", expected), object: usage)], timeout: 45), .completed)
      XCTAssertTrue(usage.label.contains("算力")); XCTAssertFalse(usage.label.contains("暂不可用"))
      XCTAssertTrue(settings.exists); XCTAssertLessThan(settings.frame.maxX, usage.frame.midX)
      XCTAssertLessThan(usage.frame.midX, collapse.frame.midX)
      XCTAssertFalse(app.staticTexts["个人空间"].exists)
      shot("f73-easywork-\(route)-footer")
      usage.tap(); XCTAssertTrue(app.navigationBars["当前客户端额度"].waitForExistence(timeout: 10))
      var matched = false
      for _ in 0..<20 {
        let observed = try await observer("evidence")
        let replies = (observed["requests"] as? [[String: Any]] ?? []).filter { $0["transportId"] as? String == (route == "lan" ? "lan-wss" : "relay-http") && ($0["request"] as? [String: Any])?["method"] as? String == "usage/read" }
        let values = replies.flatMap { entry -> [String] in
          let response = entry["response"] as? [String: Any], result = response?["result"] as? [String: Any]
          let snapshot = result?["snapshot"] as? [String: Any]
          return (snapshot?["buckets"] as? [[String: Any]] ?? []).flatMap { $0["metrics"] as? [[String: Any]] ?? [] }.compactMap { ($0["reading"] as? [String: Any])?["amount"] as? String }
        }
        matched = ([exact] + values).contains { app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", $0 + " 算力")).firstMatch.exists }
        if matched { break }; try await Task.sleep(for: .milliseconds(500))
      }
      XCTAssertTrue(matched, "Details must match a real desktop usage reply while the live wallet may change")
      shot("f73-easywork-\(route)-detail"); app.buttons["完成"].tap()
      app.buttons["sidebar-client-switch"].tap()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected + " 算力")).firstMatch.waitForExistence(timeout: 20))
      shot("f73-easywork-\(route)-picker"); app.navigationBars.buttons["完成"].tap()
      settings.tap(); XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 10)); app.buttons["完成"].tap()
      if app.buttons["open-sidebar"].isHittable { app.buttons["open-sidebar"].tap() }
      collapse.tap(); XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10))
    }
    addEasyWork(); chooseMethod("扫码连接")
    let lanButton = app.buttons["easywork-pair-lan"]
    XCTAssertTrue(lanButton.isHittable); lanButton.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); waitConnected(); try await footer("lan")
    addEasyWork(); chooseMethod("通过中继连接")
    for _ in 0..<4 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
    app.buttons["easywork-dev-login"].tap()
    try await control("relay.refresh-offer")
    let relayState = try await observer("evidence")
    let freshOffer = try XCTUnwrap(relayState["relayOffer"] as? [String: Any])
    let freshCode = try XCTUnwrap(freshOffer["pairingCode"] as? String)
    let codeField = app.secureTextFields["easywork-relay-code"]
    XCTAssertTrue(codeField.waitForExistence(timeout: 10)); codeField.tap()
    codeField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 6) + freshCode)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }

    for _ in 0..<4 where !app.buttons["easywork-pair-relay"].isHittable { app.swipeUp() }
    app.buttons["easywork-pair-relay"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); waitConnected()
    app.buttons["client-switch"].tap()
    let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
    XCTAssertTrue(edit.waitForExistence(timeout: 10)); edit.tap()
    app.descendants(matching: .any)["connection-preference"].firstMatch.tap(); app.buttons["仅中继"].firstMatch.tap()
    app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
    try await footer("relay")
    let final = try await observer("evidence")
    let requests = final["requests"] as? [[String: Any]] ?? []
    for route in ["lan-wss", "relay-http"] {
      let reads = requests.filter { $0["transportId"] as? String == route && ($0["request"] as? [String: Any])?["method"] as? String == "usage/read" }
      XCTAssertFalse(reads.isEmpty, "Original desktop must independently observe usage/read on \(route)")
      XCTAssertTrue(reads.contains { ($0["response"] as? [String: Any])?["ok"] as? Bool == true })
    }
    XCTAssertFalse(requests.contains { ($0["request"] as? [String: Any])?["method"] as? String == "turn/start" }, "Quota reads must not submit a model task")
    print("F73_EASYWORK_REAL_LAN_RELAY_PASSED")
  }
  @MainActor func testRealEasyWorkUsagePhysicalLAN() throws {
    continueAfterFailure = false
    let bundle = "com.easyai.agenthub.usageew.acceptance"
    let env = ProcessInfo.processInfo.environment
    guard env["USAGE_ACCEPTANCE_BUNDLE"] == bundle, let raw = env["USAGE_PHYSICAL_CONTEXT"],
      let fixture = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: String],
      let link = fixture["pairingUri"], let display = fixture["displayBalance"], let exact = fixture["exactBalance"] else {
      throw XCTSkip("Requires the paired physical iPhone, independent App and owned real LAN offer")
    }
    let app = XCUIApplication(bundleIdentifier: bundle)
    app.launchArguments = ["-ui-testing", "-easywork-local-dev", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
    app.launchEnvironment["EASYWORK_DEV_LAN_OFFER"] = link
    addUIInterruptionMonitor(withDescription: "Usage LAN permission") { alert in
      let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "Allow", "无线局域网与蜂窝网络", "无线局域网与蜂窝数据"])).firstMatch
      if allow.exists { allow.tap(); return true }; return false
    }
    app.launch()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30)); app.buttons["client-switch"].tap()
    app.buttons["add-client"].tap(); app.buttons["EasyWork"].firstMatch.tap()
    app.buttons["easywork-connection-method"].tap(); app.buttons["扫码连接"].firstMatch.tap()
    app.buttons["easywork-pair-lan"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND label CONTAINS 'EasyWork'"), object: app.buttons["client-switch"])], timeout: 90), .completed)
    app.buttons["open-sidebar"].tap()
    let usage = app.buttons["current-client-usage"], settings = app.buttons["open-settings"], collapse = app.buttons["close-sidebar"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", display + " 算力"), object: usage)], timeout: 45), .completed)
    XCTAssertLessThan(settings.frame.maxX, usage.frame.midX); XCTAssertLessThan(usage.frame.midX, collapse.frame.midX)
    shot("f73-physical-easywork-footer"); usage.tap()
    XCTAssertTrue(app.navigationBars["当前客户端额度"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", exact + " 算力")).firstMatch.waitForExistence(timeout: 10))
    shot("f73-physical-easywork-detail"); app.buttons["完成"].tap()
    app.buttons["sidebar-client-switch"].tap()
    XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", display + " 算力")).firstMatch.waitForExistence(timeout: 10))
    shot("f73-physical-easywork-picker"); app.navigationBars.buttons["完成"].tap()
    settings.tap(); XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 10)); app.buttons["完成"].tap()
    if app.buttons["open-sidebar"].isHittable { app.buttons["open-sidebar"].tap() }
    collapse.tap(); XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10))
    print("F73_PHYSICAL_EASYWORK_LAN_PASSED")
  }
  @MainActor private func shot(_ name: String) {
    let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
  }
}
