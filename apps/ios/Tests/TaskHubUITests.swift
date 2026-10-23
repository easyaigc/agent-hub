import CryptoKit
import UIKit
import XCTest

/// Uses the dedicated DEV deployment and real human accounts. No backend fixtures or skipped passes.
final class TaskHubUITests: XCTestCase {
  private var app: XCUIApplication!
  private var projectName = ""
  private var taskTitle = ""
  private var nonce = ""
  private var ownerUsername = ""
  private var reviewerUsername = ""
  private var password = ""
  private var reviewerPassword = ""
  private var existingTaskID: String?
  private var existingAcceptanceID: String?

  override func setUpWithError() throws {
    continueAfterFailure = false
    addUIInterruptionMonitor(withDescription: "DEV password save prompt") { alert in
      for label in ["以后", "稍后", "Not Now", "Later"] where alert.buttons[label].exists {
        alert.buttons[label].tap(); return true
      }
      return false
    }
  }

  @MainActor func testTaskHubLANSharedWorkflowDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    let endpoint = try XCTUnwrap(environment["TASK_HUB_ENDPOINT"] ?? environment["TEST_RUNNER_TASK_HUB_ENDPOINT"], "Real DEV endpoint must be supplied by the runner")
    ownerUsername = try XCTUnwrap(environment["TASK_HUB_USERNAME"] ?? environment["TEST_RUNNER_TASK_HUB_USERNAME"], "Real DEV owner account is required")
    password = try XCTUnwrap(environment["TASK_HUB_PASSWORD"] ?? environment["TEST_RUNNER_TASK_HUB_PASSWORD"], "A private, short-lived DEV password is required")
    nonce = try XCTUnwrap(environment["TASK_HUB_NONCE"] ?? environment["TEST_RUNNER_TASK_HUB_NONCE"], "A unique real acceptance run is required")
    // A freshly initialized dedicated DEV database creates these human accounts with
    // its private bootstrap password. An existing deployment can supply separate values.
    reviewerUsername = environment["TASK_HUB_REVIEWER_USERNAME"] ?? "reviewer"
    reviewerPassword = environment["TASK_HUB_REVIEWER_PASSWORD"] ?? password
    projectName = "原生 DEV 验收 " + nonce
    taskTitle = "真实任务流程 " + nonce
    XCTAssertTrue(endpoint.hasPrefix("https://"))
    XCTAssertFalse(nonce.isEmpty)
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = password
    app.launchChineseTest()
    enterWorkspace()
    connectIfNeeded(endpoint)
    if !app.buttons["task-hub-login"].exists { logoutToLogin() }
    login(ownerUsername)
    createProject()
    configureEvidenceTaskPolicy()
    createTask(reviewerName: environment["TASK_HUB_REVIEWER_NAME"] ?? "验收成员")
    chooseTab("需求")
    respondToUnansweredFeedback()
    if !app.buttons["task-hub-action-start"].isEnabled { print(app.debugDescription); shot("unresolved-feedback-diagnostic") }
    XCTAssertTrue(app.buttons["task-hub-action-start"].isEnabled, "The real owner must resolve required feedback before starting")
    tap(app.buttons["task-hub-action-start"])
    waitForStage("开发中")
    progress("已在真实 App 核对需求、验收标准、负责人响应和开始处理状态。")
    recordSelfCheckAndDelivery(round: 1)
    waitForStage("验收中")
    shot("round-1-frozen-candidate")

    switchAccount(reviewerUsername, password: reviewerPassword)
    reopenTask()
    chooseTab("交付")
    let fail = prefixedButton("task-hub-action-fail-")
    reveal(fail)
    print("Actual review hit target: \(fail.frame), enabled=\(fail.isEnabled)")
    shot("before-real-human-failure-review")
    tap(fail)
    XCTAssertTrue(app.buttons["task-hub-submit"].waitForExistence(timeout: 10), "The review button must open the actual review form")
    XCTAssertTrue(app.navigationBars["人工验收不通过"].waitForExistence(timeout: 10), "The failure action must open the actual failure form before any decision is submitted")
    XCTAssertFalse(app.navigationBars["人工验收通过"].exists)
    shot("actual-human-failure-form-direction")
    fill(app.textViews["task-hub-body"], "本次真实验收要求核对失败返修：请进入第 2 轮，保留第 1 轮候选和不通过记录，再重新自验和交付。")
    submitForm()
    enterCurrentRoundIfNeeded()
    waitForStage("开发中")
    let secondRound = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "第 2 轮")).firstMatch
    // The stage row can be visible while the preceding round summary is outside
    // the lazy list's rendered viewport after returning from the review sheet.
    reveal(secondRound)
    XCTAssertTrue(secondRound.exists, "The confirmed second round must be visible")
    chooseTab("过程")
    let oldRound = app.descendants(matching: .any).matching(identifier: "task-hub-round-1").firstMatch
    reveal(oldRound)
    XCTAssertTrue(oldRound.exists, "The failed round must remain visible")
    shot("failed-acceptance-created-real-round-2")

    switchAccount(ownerUsername, password: password)
    reopenTask()
    chooseTab("需求")
    respondToUnansweredFeedback()
    progress("真实 App 已核对第 2 轮及旧轮保留，按验收意见重新交付本轮证据。")
    recordSelfCheckAndDelivery(round: 2)
    waitForStage("验收中")
    switchAccount(reviewerUsername, password: reviewerPassword)
    reopenTask()
    chooseTab("交付")
    finishSecondRoundAndRelaunch()
  }


  /// Continue the same task after an independent read confirmed open round 2/revision.
  /// No project, task or first-round decision is created or replayed by this selector.
  @MainActor func testTaskHubResumeOriginalDevelopmentRoundDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ key: String) -> String? { environment[key] ?? environment["TEST_RUNNER_" + key] }
    XCTAssertEqual(value("TASK_HUB_RESUME_WORKFLOW"), "1")
    XCTAssertEqual(value("TASK_HUB_RESUME_ROUND"), "2")
    nonce = try XCTUnwrap(value("TASK_HUB_NONCE"))
    XCTAssertNotNil(nonce.range(of: "^[a-f0-9]{8}$", options: .regularExpression))
    existingTaskID = try XCTUnwrap(value("TASK_HUB_RESUME_TASK_ID"))
    XCTAssertNotNil(UUID(uuidString: try XCTUnwrap(existingTaskID)))
    let revision = try XCTUnwrap(Int(try XCTUnwrap(value("TASK_HUB_RESUME_REVISION"))))
    XCTAssertGreaterThan(revision, 0)
    ownerUsername = try XCTUnwrap(value("TASK_HUB_USERNAME"))
    password = try XCTUnwrap(value("TASK_HUB_PASSWORD"))
    reviewerUsername = value("TASK_HUB_REVIEWER_USERNAME") ?? "reviewer"
    reviewerPassword = value("TASK_HUB_REVIEWER_PASSWORD") ?? password
    projectName = "原生 DEV 验收 " + nonce
    taskTitle = "真实任务流程 " + nonce
    let endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(value("TASK_HUB_ENDPOINT"))))
    XCTAssertEqual(endpoint.scheme, "https")
    XCTAssertEqual(endpoint.port, 19543)
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = password
    app.launchChineseTest()
    enterWorkspace()
    connectIfNeeded(endpoint.absoluteString)
    if app.buttons["task-hub-login"].exists { login(ownerUsername) }
    else { switchAccount(ownerUsername, password: password) }
    reopenTask()
    enterCurrentRoundIfNeeded()
    chooseTab("需求")
    waitForStage("开发中")
    let originalRevision = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "版本 \(revision) ·")).element
    reveal(originalRevision)
    XCTAssertTrue(originalRevision.exists, "The independently read original revision must still own this continuation before writes")
    let round = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "第 2 轮")).firstMatch
    reveal(round); XCTAssertTrue(round.exists)
    shot("original-round-2-development-before-only-remaining-stages")
    respondToUnansweredFeedback()
    progress("真实 App 已核对原任务第 2 轮及原版本，继续自验和交付；不重放第 1 轮操作。")
    recordSelfCheckAndDelivery(round: 2)
    waitForStage("验收中")
    switchAccount(reviewerUsername, password: reviewerPassword)
    reopenTask()
    chooseTab("交付")
    finishSecondRoundAndRelaunch()
  }

  @MainActor private func enterCurrentRoundIfNeeded() {
    let current = app.buttons["task-hub-enter-current-round"]
    if current.exists {
      tap(current)
      XCTAssertTrue(current.waitForNonExistence(timeout: 15), "Explicit current-round navigation must leave the original historical read-only context")
    }
  }

  /// Continues only the still-pending second round identified by a prior server read.
  @MainActor func testTaskHubResumeOriginalSecondRoundDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ key: String) -> String? { environment[key] ?? environment["TEST_RUNNER_" + key] }
    XCTAssertEqual(value("TASK_HUB_RESUME_WORKFLOW"), "1")
    nonce = try XCTUnwrap(value("TASK_HUB_NONCE"))
    XCTAssertNotNil(nonce.range(of: "^[a-f0-9]{8}$", options: .regularExpression))
    existingTaskID = try XCTUnwrap(value("TASK_HUB_RESUME_TASK_ID"))
    XCTAssertNotNil(UUID(uuidString: try XCTUnwrap(existingTaskID)))
    existingAcceptanceID = try XCTUnwrap(value("TASK_HUB_RESUME_ACCEPTANCE_ID"))
    XCTAssertNotNil(UUID(uuidString: try XCTUnwrap(existingAcceptanceID)))
    let revision = try XCTUnwrap(Int(try XCTUnwrap(value("TASK_HUB_RESUME_REVISION"))))
    XCTAssertGreaterThan(revision, 0)
    XCTAssertEqual(value("TASK_HUB_RESUME_ROUND"), "2")
    ownerUsername = try XCTUnwrap(value("TASK_HUB_USERNAME"))
    password = try XCTUnwrap(value("TASK_HUB_PASSWORD"))
    reviewerUsername = value("TASK_HUB_REVIEWER_USERNAME") ?? "reviewer"
    reviewerPassword = value("TASK_HUB_REVIEWER_PASSWORD") ?? password
    projectName = "原生 DEV 验收 " + nonce
    taskTitle = "真实任务流程 " + nonce
    let endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(value("TASK_HUB_ENDPOINT"))))
    XCTAssertEqual(endpoint.scheme, "https")
    XCTAssertEqual(endpoint.port, 19547, "Only the registered same-certificate observer may drive this remaining-stage selector")
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = reviewerPassword
    app.launch()
    enterWorkspace()
    uxConnectPrimary(endpoint)
    if app.buttons["task-hub-login"].exists { login(reviewerUsername) }
    else { switchAccount(reviewerUsername, password: reviewerPassword) }
    reopenTask()
    waitForStage("验收中")
    let originalRevision = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "版本 \(revision) ·")).element
    reveal(originalRevision)
    XCTAssertTrue(originalRevision.exists, "The original server revision must still own this continuation before any decision")
    chooseTab("交付")
    shot("original-round-2-still-pending-before-only-remaining-stages")
    finishSecondRoundAndRelaunch()
  }

  @MainActor private func finishSecondRoundAndRelaunch() {
    let pass: XCUIElement
    if let existingAcceptanceID { pass = app.buttons.matching(identifier: "task-hub-action-pass-" + existingAcceptanceID).element }
    else { pass = prefixedButton("task-hub-action-pass-") }
    reveal(pass)
    print("Actual review pass hit target: \(pass.frame), enabled=\(pass.isEnabled)")
    shot("before-real-human-pass-review")
    tap(pass)
    XCTAssertTrue(app.navigationBars["人工验收通过"].waitForExistence(timeout: 10), "The pass action must open the actual pass form before any decision is submitted")
    XCTAssertFalse(app.navigationBars["人工验收不通过"].exists)
    shot("actual-human-pass-form-direction")
    fill(app.textViews["task-hub-body"], "已在真实原生 App 核对第 2 轮、独立冻结候选、自验与材料；第 1 轮失败记录保留。通过必要人工验收，等待最终交付核验。")
    submitForm()
    waitForStage("待最终交付")
    XCTAssertNotEqual(app.staticTexts["task-hub-workflow-stage"].label, "已完成", "Acceptance alone must not close the task")
    shot("human-acceptance-passed-still-closing")

    switchAccount(ownerUsername, password: password)
    reopenTask()
    verifyClosingManualDispatch()
    openAction("task-hub-action-finalize")
    fill(app.textViews["task-hub-no-commit-reason"], "本任务交付本次真实原生 UI 检查的 xcresult 证据附件，未修改业务源码，无需代码提交。")
    submitForm()
    waitForStage("已完成")
    chooseTab("交付")
    let closing = app.descendants(matching: .any).matching(identifier: "task-hub-closing-state").firstMatch
    reveal(closing)
    XCTAssertTrue(closing.exists, "The actual closing state must be rendered inside the visible delivery list")
    XCTAssertTrue(closing.label.contains("已完成") || (closing.value as? String)?.contains("已完成") == true || closing.staticTexts["已完成"].exists,
      "The actual server closing summary must say completed after confirmed finalization")
    shot("server-confirmed-finalization-completed")
    chooseTab("过程")
    shot("both-rounds-and-timeline-retained")
    app.terminate()
    app.launchChineseTest()
    enterWorkspace()
    reopenTask()
    waitForStage("已完成")
    shot("same-service-persisted-task-after-relaunch")
  }

  /// Reads an existing completed DEV task; never creates or submits business data.
  @MainActor func testTaskHubReadOnlyAppearanceAndDynamicTypeDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ key: String) -> String? { environment[key] ?? environment["TEST_RUNNER_" + key] }
    projectName = try XCTUnwrap(value("TASK_HUB_UX_PROJECT_NAME"), "An existing real DEV project must be supplied")
    taskTitle = try XCTUnwrap(value("TASK_HUB_UX_TASK_TITLE"), "An existing real completed DEV task must be supplied")
    nonce = try XCTUnwrap(value("TASK_HUB_NONCE"))
    ownerUsername = try XCTUnwrap(value("TASK_HUB_USERNAME"))
    password = try XCTUnwrap(value("TASK_HUB_PASSWORD"))
    app = XCUIApplication()
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = password
    let primaryEndpoint = try XCTUnwrap(URL(string: try XCTUnwrap(value("TASK_HUB_ENDPOINT"))))
    XCTAssertEqual(primaryEndpoint.port, 19543)
    let historicalAcceptanceId = try XCTUnwrap(value("TASK_HUB_UX_HISTORICAL_ACCEPTANCE_ID"))
    let historicalDecisionNote = try XCTUnwrap(value("TASK_HUB_UX_HISTORICAL_DECISION_NOTE"))
    let ordinaryArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchArguments = ordinaryArguments + ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.large.rawValue]
    app.launchChineseTest()
    uxOpenSettings()
    let originalAppearance = try XCTUnwrap(uxSelectedAppearance(), "The real DEV appearance setting must be readable before any change")
    tap(app.navigationBars["设置"].buttons["完成"])
    if app.buttons["close-sidebar"].isHittable { tap(app.buttons["close-sidebar"]) }
    addTeardownBlock { [self] in
      await MainActor.run {
        // Relaunch clears transient sheets and the app-only launch argument domain.
        self.app.terminate()
        self.app.launchArguments = ordinaryArguments
        self.app.launchChineseTest()
        self.uxSetAppearance(originalAppearance)
        self.shot("ux-original-appearance-restored-and-size-argument-removed")
      }
    }

    enterWorkspace()
    uxConnectPrimary(primaryEndpoint)
    tap(app.buttons["task-hub-dismiss"])

    for accessibilitySize in [false, true] {
      if accessibilitySize {
        app.terminate()
        app.launchArguments = ordinaryArguments + ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue]
        app.launchChineseTest()
      }
      for appearance in ["浅色", "深色"] {
        uxSetAppearance(appearance)
        let throughSidebar = appearance == "深色"
        uxOpenWorkspace(throughSidebar: throughSidebar)
        reopenTask()
        uxAssertDefaultProcess()
        waitForStage("已完成")
        if !accessibilitySize && appearance == "浅色" { uxReadHistoricalAcceptance(historicalAcceptanceId, decisionNote: historicalDecisionNote) }
        let segmented = app.segmentedControls["task-hub-detail-tabs"]
        if accessibilitySize {
          XCTAssertFalse(segmented.exists, "Actual accessibility text size must replace the segmented picker")
          let menu = app.buttons["task-hub-detail-tabs"].firstMatch
          XCTAssertTrue(menu.waitForExistence(timeout: 10), "The actual accessibility-size menu picker must be rendered")
          print("Actual accessibility detail picker: label=\(menu.label), frame=\(menu.frame)")
          shot("ux-\(appearance)-accessibility-size-actual-menu-picker")
          tap(menu)
          for title in ["需求", "过程", "证据", "交付", "执行"] {
            XCTAssertTrue(app.buttons[title].firstMatch.waitForExistence(timeout: 5), "The opened real menu must expose every detail tab")
          }
          shot("ux-\(appearance)-accessibility-size-opened-real-menu")
          tap(app.buttons["过程"].firstMatch)
          XCTAssertTrue(menu.label.contains("过程") || (menu.value as? String)?.contains("过程") == true || menu.staticTexts["过程"].exists,
            "A real menu selection must update the actual detail picker")
          XCTAssertFalse(segmented.exists)
        } else {
          XCTAssertTrue(segmented.waitForExistence(timeout: 10), "Ordinary app-scoped size must render the actual segmented picker")
          for title in ["需求", "过程", "证据", "交付", "执行"] { XCTAssertTrue(segmented.buttons[title].exists) }
          chooseTab("需求")
          XCTAssertTrue(segmented.buttons["需求"].isSelected)
          shot("ux-\(appearance)-ordinary-size-actual-segmented-picker")
        }
        uxDismissWorkspace(returnToSidebar: throughSidebar)
      }
    }
  }

  @MainActor func testTaskHubReadOnlyLogoutDialogDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    nonce = try XCTUnwrap(environment["TASK_HUB_NONCE"] ?? environment["TEST_RUNNER_TASK_HUB_NONCE"])
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchChineseTest()
    enterWorkspace()
    openServiceFromCurrentStack()
    revealServiceLogout()
    XCTAssertTrue(app.buttons["task-hub-logout"].exists, "An actual existing human session must expose logout")
    for step in 0..<3 {
      app.buttons["task-hub-logout"].tap()
      let alert = app.alerts["退出当前设备登录？"]
      let button = try XCTUnwrap(waitForLogoutConfirmation(timeout: 8))
      XCTAssertTrue(button.exists)
      shot("scoped-real-logout-dialog-\(step)-before-cancel")
      let cancel = alert.buttons["取消"]
      XCTAssertTrue(cancel.isHittable)
      XCTAssertTrue(alert.frame.contains(cancel.frame))
      cancel.tap()
      XCTAssertFalse(alert.exists)
      XCTAssertTrue(app.navigationBars["主服务与账户"].exists)
      XCTAssertTrue(app.buttons["task-hub-logout"].exists, "Cancelling must keep this device's original login")
    }
    app.terminate()
  }

  @MainActor private func logoutConfirmation(in alert: XCUIElement) -> XCUIElement? {
    // UIKit exposes a SwiftUI Alert action twice with the same identifier and
    // frame. Require one actual leaf action rather than the ancestor or an
    // arbitrary first match; its hittability is verified before any tap.
    let leaves = alert.buttons.matching(identifier: "task-hub-confirm-logout").allElementsBoundByIndex.filter {
      $0.label == "退出登录" && $0.buttons.matching(identifier: "task-hub-confirm-logout").count == 0
    }
    return leaves.count == 1 ? leaves[0] : nil
  }

  @MainActor private func waitForLogoutConfirmation(timeout: TimeInterval) -> XCUIElement? {
    let alert = app.alerts["退出当前设备登录？"]
    var selected: XCUIElement?
    let usable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard alert.exists, let button = self.logoutConfirmation(in: alert), button.isHittable else { return false }
      selected = button
      return true
    }, object: alert)
    let found = XCTWaiter.wait(for: [usable], timeout: timeout) == .completed
    shot("exact-logout-alert-before-leaf-hit-check")
    if !found { print(alert.debugDescription) }
    XCTAssertTrue(found, "The exact native logout Alert must expose one hittable leaf confirmation within its original deadline")
    guard found, let button = selected else { return nil }
    XCTAssertEqual(alert.label, "退出当前设备登录？")
    XCTAssertEqual(button.label, "退出登录")
    XCTAssertEqual(button.identifier, "task-hub-confirm-logout")
    XCTAssertEqual(button.buttons.matching(identifier: "task-hub-confirm-logout").count, 0)
    XCTAssertTrue(button.isEnabled && button.isHittable)
    XCTAssertTrue(alert.frame.contains(button.frame), "The real leaf confirmation must remain inside its own Alert")
    print("Actual leaf confirmation: matches=\(alert.buttons.matching(identifier: "task-hub-confirm-logout").count), frame=\(button.frame), label=\(button.label), hittable=\(button.isHittable)")
    return button
  }

  @MainActor private func revealServiceLogout() {
    XCTAssertEqual(actualTaskHubPage()?.kind, "service")
    let logout = app.buttons["task-hub-logout"]
    reveal(logout)
    guard let list = actualList(for: logout) else {
      XCTFail("The exact logout row must belong to its actual Service List")
      return
    }
    XCTAssertTrue(uncoveredListRect(list).contains(logout.frame))
  }

  @MainActor private func uxAssertDefaultProcess() {
    let segmented = app.segmentedControls["task-hub-detail-tabs"]
    if segmented.exists {
      XCTAssertTrue(segmented.buttons["过程"].isSelected, "A newly opened actual detail must default to Process")
    } else {
      let menu = app.buttons["task-hub-detail-tabs"].firstMatch
      XCTAssertTrue(menu.waitForExistence(timeout: 10))
      XCTAssertTrue(menu.label.contains("过程") || (menu.value as? String)?.contains("过程") == true || menu.staticTexts["过程"].exists,
        "The actual accessibility-size default must also be Process")
    }
    shot("ux-newly-opened-detail-default-process")
  }
  @MainActor private func uxReadHistoricalAcceptance(_ acceptanceId: String, decisionNote: String) {
    let current = app.buttons["task-hub-round-2"]
    reveal(current)
    XCTAssertTrue(current.label.contains("当前轮") && current.label.contains("必要验收已通过"))
    uxToggleRoundChevron(current) // Collapse the initially expanded real history.
    let history = app.buttons["task-hub-round-1"]
    reveal(history)
    XCTAssertTrue(history.label.contains("历史只读") && history.label.contains("已拒绝"))
    uxToggleRoundChevron(history)
    // DisclosureGroup propagates its identifier over descendant AX identifiers.
    // Read the exact independent R1 decision from the actual completed task.
    let note = app.staticTexts[decisionNote].firstMatch
    reveal(note)
    XCTAssertEqual(note.label, decisionNote)
    let failed = app.staticTexts["未通过"].firstMatch
    reveal(failed)
    XCTAssertTrue(failed.exists)
    XCTAssertFalse(app.buttons["人工验收通过"].exists)
    XCTAssertFalse(app.buttons["人工验收不通过"].exists)
    XCTAssertFalse(app.buttons["task-hub-action-pass-" + acceptanceId].exists)
    XCTAssertFalse(app.buttons["task-hub-action-fail-" + acceptanceId].exists)
    shot("ux-existing-completed-task-real-R1-failed-readonly")
  }
  @MainActor private func uxToggleRoundChevron(_ header: XCUIElement) {
    reveal(header)
    // The default DisclosureGroup toggles at its chevron; its AX label may also
    // include the expanded body's bounds, so a generic center tap is unreliable.
    let frame = header.frame
    let point = CGPoint(x: frame.maxX - 20, y: frame.minY + min(22, frame.height / 2))
    guard let list = actualList(for: header) else { XCTFail("The real round header must belong to its actual detail List"); return }
    XCTAssertTrue(uncoveredListRect(list).contains(point))
    print("Actual readonly round chevron target: identifier=\(header.identifier), headerFrame=\(frame), point=\(point)")
    shot("ux-before-exact-round-chevron-tap")
    app.coordinate(withNormalizedOffset: .zero)
      .withOffset(CGVector(dx: point.x - app.frame.minX, dy: point.y - app.frame.minY)).tap()
  }
  @MainActor private func uxConnectPrimary(_ selectedEndpoint: URL) {
    let field = app.descendants(matching: .any).matching(identifier: "task-hub-endpoint").firstMatch
    let selectingExistingService = !field.waitForExistence(timeout: 2)
    if selectingExistingService {
      openServiceFromCurrentStack()
      tap(named("发现与核对服务"))
    }
    fill(field, selectedEndpoint.absoluteString)
    // Reveal uses the actual List/keyboard geometry and verifies displacement.
    let keyboard = app.keyboards.firstMatch
    let probe = app.buttons["task-hub-probe"]
    XCTAssertEqual((field.value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), selectedEndpoint.absoluteString)
    reveal(probe)
    if keyboard.exists {
      XCTAssertLessThan(probe.frame.maxY, keyboard.frame.minY, "The complete probe button must be above the actual keyboard")
    }
    print("Actual discovery probe hit target: frame=\(probe.frame), keyboardVisible=\(keyboard.exists)")
    shot("actual-discovery-probe-without-keyboard-cover")
    probe.tap()
    let trust = app.switches["task-hub-trust-confirmation"]
    let trustAppeared = trust.waitForExistence(timeout: 30)
    if !trustAppeared { print(app.debugDescription); shot("actual-discovery-probe-failure") }
    XCTAssertTrue(trustAppeared, "The exact endpoint must expose actual certificate confirmation within the original deadline")
    reveal(trust)
    if trust.value as? String != "1" { trust.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() }
    XCTAssertEqual(trust.value as? String, "1")
    tap(app.buttons["task-hub-connect"])
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: trust)], timeout: 30), .completed)
    if selectingExistingService {
      let tutorial = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Speed up your typing by sliding your finger")).firstMatch
      if tutorial.waitForExistence(timeout: 3) {
        shot("actual-keyboard-onboarding-interruption")
        app.buttons["Continue"].tap()
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: tutorial)], timeout: 10), .completed)
      }
      let discovery = app.navigationBars["发现主服务"]
      XCTAssertTrue(discovery.waitForExistence(timeout: 10))
      discovery.buttons["BackButton"].tap()
      let service = app.navigationBars["主服务与账户"]
      XCTAssertTrue(service.waitForExistence(timeout: 10), "Discovery must return through the actual service navigation before finishing")
      tap(service.buttons["完成"])
    }
  }
  @MainActor private func openServiceFromCurrentStack() {
    if actualTaskHubPage()?.kind == "service" { return }
    guard returnToTaskHubRoot(timeout: 20) else { return }
    let service = app.navigationBars["项目与任务"].buttons["task-hub-service"]
    XCTAssertTrue(service.isHittable)
    service.tap()
    let opened = app.navigationBars["主服务与账户"].waitForExistence(timeout: 10)
    if !opened { navigationDiagnostic("exact-service-open-failed") }
    XCTAssertTrue(opened, "The exact root Service action must open its own native Service navigation")
  }

  @MainActor private func uxOpenSettings() {
    if !app.buttons["open-settings"].isHittable { tap(app.buttons["open-sidebar"]) }
    tap(app.buttons["open-settings"])
    XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 10))
  }
  @MainActor private func uxSelectedAppearance() -> String? {
    let picker = app.descendants(matching: .any).matching(identifier: "appearance-picker").firstMatch
    let appeared = picker.waitForExistence(timeout: 10)
    if !appeared { print(app.debugDescription); shot("ux-appearance-picker-navigation-diagnostic") }
    XCTAssertTrue(appeared)
    let actual = picker.label + " " + ((picker.value as? String) ?? "")
    return ["跟随系统", "浅色", "深色"].first { actual.contains($0) }
  }
  @MainActor private func uxSetAppearance(_ appearance: String) {
    uxOpenSettings()
    tap(app.descendants(matching: .any).matching(identifier: "appearance-picker").firstMatch)
    tap(app.buttons[appearance].firstMatch)
    let choices = app.navigationBars["外观"]
    if choices.exists && choices.buttons["BackButton"].isHittable {
      choices.buttons["BackButton"].tap()
    }
    XCTAssertEqual(uxSelectedAppearance(), appearance, "The real DEV App setting must change through its existing picker")
    tap(app.navigationBars["设置"].buttons["完成"])
    if app.buttons["close-sidebar"].isHittable { tap(app.buttons["close-sidebar"]) }
  }
  @MainActor private func uxOpenWorkspace(throughSidebar: Bool) {
    let entry: XCUIElement
    if throughSidebar {
      tap(app.buttons["open-sidebar"])
      entry = app.buttons["sidebar-task-hub"]
    } else { entry = app.buttons["task-hub-entry"] }
    reveal(entry)
    print("Actual readonly UX entry: identifier=\(entry.identifier), frame=\(entry.frame)")
    shot("ux-\(entry.identifier)-entry-before-tap")
    entry.tap()
    let page = waitForTaskHubPage(until: Date().addingTimeInterval(20))
    XCTAssertNotNil(page, "A real TaskHub root, project or detail page must appear after its own entry")
    XCTAssertTrue(page?.kind == "root" || page?.kind == "project" || page?.kind == "detail")
    if app.buttons["task-hub-login"].exists { login(ownerUsername) }
  }
  @MainActor private func uxDismissWorkspace(returnToSidebar: Bool) {
    guard returnToTaskHubRoot(timeout: 20) else { return }
    tap(app.navigationBars["项目与任务"].buttons["task-hub-dismiss"])
    let entry = app.buttons[returnToSidebar ? "sidebar-task-hub" : "task-hub-entry"]
    XCTAssertTrue(entry.waitForExistence(timeout: 10))
    XCTAssertTrue(entry.isHittable, "The actual originating entry must be usable after returning")
    shot("ux-\(entry.identifier)-returned-without-business-write")
    if returnToSidebar { tap(app.buttons["close-sidebar"]) }
  }

  @MainActor private func enterWorkspace() {
    let entry = app.buttons["task-hub-entry"]
    XCTAssertTrue(entry.waitForExistence(timeout: 30))
    tap(entry)
    XCTAssertTrue(returnToTaskHubRoot(timeout: 20))
  }

  @MainActor private func connectIfNeeded(_ endpoint: String) {
    let field = app.descendants(matching: .any).matching(identifier: "task-hub-endpoint").firstMatch
    guard field.waitForExistence(timeout: 3) else { return }
    fill(field, endpoint)
    tap(app.buttons["task-hub-probe"])
    let trust = app.switches["task-hub-trust-confirmation"]
    XCTAssertTrue(trust.waitForExistence(timeout: 30), "The real HTTPS service must be probed before selection")
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", endpoint)).firstMatch.exists)
    if trust.value as? String != "1" {
      reveal(trust)
      trust.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
    }
    XCTAssertEqual(trust.value as? String, "1", "Explicit certificate confirmation must be enabled")
    XCTAssertTrue(app.buttons["task-hub-connect"].isEnabled)
    tap(app.buttons["task-hub-connect"])
    XCTAssertTrue(app.buttons["task-hub-login"].waitForExistence(timeout: 30))
  }

  @MainActor private func login(_ username: String) {
    fill(app.textFields["task-hub-username"], username)
    // The DEBUG gate fills the real private password without XCTest typeText logging it.
    // This button still performs the real human login over the pinned HTTPS connection.
    tap(app.buttons["task-hub-login"])
    let confirmed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["task-hub-login"])
    XCTAssertEqual(XCTWaiter.wait(for: [confirmed], timeout: 40), .completed, "The real human login must be confirmed")
    XCTAssertTrue(app.buttons["task-hub-service"].waitForExistence(timeout: 30))
    XCTAssertFalse(app.buttons["task-hub-login"].exists, "Real human login must succeed")
  }

  @MainActor private func createProject() {
    tap(app.buttons["task-hub-project-create"])
    fill(app.textFields["task-hub-title"], projectName)
    let suffix = String(nonce.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(12)).uppercased()
    fill(app.textFields["task-hub-project-key"], "N" + suffix)
    fill(app.textViews["task-hub-description"], "本次独立真实原生验收，设备运行与业务写操作由实际 DEV 服务确认。")
    submitForm()
    openProject()
  }

  @MainActor private func configureEvidenceTaskPolicy() {
    tap(app.buttons["task-hub-project-settings"])
    tap(app.descendants(matching: .any).matching(identifier: "task-hub-policy-edit").firstMatch)
    let requiredCommit = app.switches["task-hub-policy-final-commit"]
    reveal(requiredCommit)
    if requiredCommit.value as? String == "1" {
      requiredCommit.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
    }
    XCTAssertEqual(requiredCommit.value as? String, "0")
    submitForm()
    tap(app.buttons["完成"].firstMatch)
    XCTAssertTrue(app.buttons["task-hub-create"].waitForExistence(timeout: 20))
  }

  @MainActor private func createTask(reviewerName: String) {
    tap(app.buttons["task-hub-create"])
    fill(app.textFields["task-hub-title"], taskTitle)
    fill(app.textViews["task-hub-description"], "核对真实任务创建、负责人响应、开始处理、进度、自验、冻结候选、验收失败新轮、验收通过后的关闭门槛与重启恢复。")
    fill(app.textViews["task-hub-acceptance-criteria"], "旧轮候选与失败结论保留；第 2 轮重新响应和自验；必要人工验收通过后仍待最终交付；最终提交确认后服务端返回完成。")
    choose("task-hub-reviewer", label: reviewerName)
    submitForm()
    XCTAssertTrue(app.staticTexts["task-hub-detail-title"].waitForExistence(timeout: 30))
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
    shot("real-project-and-task-created")
  }

  @MainActor private func respondToUnansweredFeedback() {
    let predicate = NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "task-hub-action-response-", "提交本人响应")
    for _ in 0..<20 {
      let response = app.descendants(matching: .any).matching(predicate).firstMatch
      for _ in 0..<12 where !response.exists { if !scrollContent(up: true) { break } }
      if !response.exists { break }
      tap(response)
      fill(app.textViews["task-hub-body"], "接受并处理：在本次真实原生运行中逐项核对需求，并保留对应轮次、实际候选和验收证据。")
      submitForm()
    }
    XCTAssertFalse(app.descendants(matching: .any).matching(predicate).firstMatch.exists, "All current unanswered feedback must be explicitly handled by its owner")
  }

  @MainActor private func progress(_ text: String) {
    openAction("task-hub-action-progress")
    fill(app.textViews["task-hub-body"], text)
    submitForm()
  }

  @MainActor private func recordSelfCheckAndDelivery(round: Int) {
    let screenshot = app.screenshot()
    let bytes = screenshot.pngRepresentation
    let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    let name = "\(nonce)-round-\(round)-actual-native-self-check"
    let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "public.png")
    attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    openAction("task-hub-action-self-check")
    fill(app.textFields["task-hub-candidate-reference"], "native-dev:\(nonce):round-\(round)")
    fill(app.textFields["task-hub-source-revision"], "XCTest-run:\(nonce):round-\(round)")
    fill(app.textFields["task-hub-artifact-digest"], digest)
    tap(app.buttons["task-hub-add-material"])
    fill(prefixedField("task-hub-material-name-"), name)
    fill(prefixedField("task-hub-material-reference-"), "xcresult:TaskHubUITests/\(nonce)/\(name)")
    fill(prefixedField("task-hub-material-digest-"), digest)
    fill(prefixedField("task-hub-check-name-"), "本轮真实原生需求与负责人过程核对")
    let result = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "task-hub-check-result-")).firstMatch
    tap(result); tap(named("通过"))
    let evidence = app.textViews.matching(NSPredicate(format: "identifier BEGINSWITH %@", "task-hub-check-evidence-")).firstMatch
    fill(evidence, "已在本次真实 App 核对第 \(round) 轮任务需求、负责人明确响应和进度；对应截图已保留为 xcresult 附件 \(name)，SHA-256 \(digest)。此项不声称 Agent、模型或推送验证。")
    submitForm()
    openAction("task-hub-action-delivery")
    fill(app.textViews["task-hub-body"], "提交第 \(round) 轮本次真实 UI 运行的冻结截图证据，候选、产物摘要和材料与本轮自验一致。")
    submitForm()
  }

  @MainActor private func switchAccount(_ username: String, password selectedPassword: String) {
    logoutToLogin()
    if selectedPassword != app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] {
      app.terminate(); app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = selectedPassword; app.launchChineseTest(); enterWorkspace()
    }
    login(username)
  }

  @MainActor private func logoutToLogin() {
    openServiceFromCurrentStack()
    revealServiceLogout()
    app.buttons["task-hub-logout"].tap()
    guard let confirmation = waitForLogoutConfirmation(timeout: 8) else { return }
    shot("before-real-leaf-logout-confirmation")
    confirmation.tap()
    tap(app.navigationBars["主服务与账户"].buttons["完成"])
    XCTAssertTrue(app.buttons["task-hub-login"].waitForExistence(timeout: 20))
  }

  @MainActor private func openProject() {
    XCTAssertFalse(projectName.isEmpty, "Exact project identity must be supplied before navigating")
    func selectedProjectVisible() -> Bool {
      // The exact title identifies SwiftUI's NavigationBar; its AX label can differ.
      let matches = app.navigationBars.matching(identifier: projectName)
      guard matches.count == 1 else { return false }
      let bar = matches.element(boundBy: 0)
      let create = bar.buttons["task-hub-create"]
      let settings = bar.buttons["task-hub-project-settings"]
      guard bar.exists, create.exists, settings.exists else { return false }
      let viewport = bar.frame.intersection(app.frame)
      let ready = bar.identifier == projectName && create.isHittable && settings.isHittable
        && viewport.contains(create.frame) && viewport.contains(settings.frame)
      if ready { print("Actual exact project NavigationBar: identifier=\(bar.identifier), label=\(bar.label), frame=\(bar.frame), createFrame=\(create.frame), settingsFrame=\(settings.frame)") }
      return ready
    }
    let deadline = Date().addingTimeInterval(30)
    let project = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "task-hub-project-", projectName)).firstMatch
    var backs = 0
    while Date() < deadline {
      if selectedProjectVisible() { return }
      guard let page = waitForTaskHubPage(until: deadline) else { break }
      if selectedProjectVisible() { return }
      if page.kind == "root" {
        let found = revealActualTarget(project, initialWait: 2, until: deadline, routeChanged: {
          self.actualTaskHubPage()?.kind != "root"
        })
        if selectedProjectVisible() { return }
        if actualTaskHubPage()?.kind != "root" { continue }
        guard found else { break }
        XCTAssertTrue(project.identifier.hasPrefix("task-hub-project-"))
        XCTAssertTrue(project.label.contains(projectName))
        print("Actual exact project row: identifier=\(project.identifier), frame=\(project.frame)")
        project.tap()
        let opened = app.navigationBars[projectName].waitForExistence(timeout: 20)
        if !opened { navigationDiagnostic("exact-project-open-failed") }
        XCTAssertTrue(opened)
        XCTAssertTrue(selectedProjectVisible(), "The exact requested project must expose its own task list")
        return
      }
      guard (page.kind == "project" || page.kind == "detail") && backs < 3 else { break }
      let back = page.bar.buttons["BackButton"]
      guard back.exists && back.isHittable && page.bar.frame.contains(back.frame) else { break }
      print("Actual project navigation back: title=\(page.bar.label), frame=\(back.frame)")
      back.tap(); backs += 1
    }
    navigationDiagnostic("exact-project-route-not-ready")
    XCTFail("The exact target project or its real root row must be usable within the original 30s route deadline")
  }

  @MainActor private func reopenTask() {
    // Account changes discard the old navigation scope asynchronously. Return
    // through the actual visible stack before opening the new account's project.
    openProject()
    let row: XCUIElement
    if let existingTaskID { row = app.buttons.matching(identifier: "task-hub-task-" + existingTaskID).element }
    else { row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "task-hub-task-", taskTitle)).firstMatch }
    if !row.waitForExistence(timeout: 30) { print(app.debugDescription); shot("reopen-real-task-diagnostic") }
    XCTAssertTrue(row.exists)
    // A plain row's accessibility frame includes empty layout space. Tap the
    // actual title inside this exact task row instead of its blank frame center.
    let title = row.staticTexts[taskTitle].firstMatch
    reveal(title)
    XCTAssertTrue(row.frame.contains(title.frame), "The tap target must belong to the selected task row")
    print("Actual task navigation hit target: row=\(row.identifier), rowFrame=\(row.frame), titleFrame=\(title.frame)")
    shot("before-reopen-exact-task-title")
    title.tap()
    let detailTitle = app.staticTexts["task-hub-detail-title"]
    let opened = detailTitle.waitForExistence(timeout: 20)
    if !opened { print(app.debugDescription); shot("reopen-detail-title-timeout") }
    XCTAssertTrue(opened, "The selected task must open its actual detail")
    XCTAssertEqual(detailTitle.label, taskTitle, "The detail must belong to the selected real task")
  }

  @MainActor private func openAction(_ identifier: String) {
    tap(app.buttons["task-hub-actions"])
    tap(app.buttons[identifier])
    XCTAssertTrue(app.buttons["task-hub-submit"].waitForExistence(timeout: 10))
  }

  @MainActor private func verifyClosingManualDispatch() {
    waitForStage("待最终交付")
    openAction("task-hub-action-dispatch")
    let form = app.navigationBars["派发最终交付"]
    XCTAssertTrue(form.waitForExistence(timeout: 10), "Closing must expose the actual final-delivery dispatch form")
    let role = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "执行角色")).firstMatch
    if !role.waitForExistence(timeout: 10) { print(app.debugDescription); shot("closing-dispatch-role-diagnostic") }
    XCTAssertTrue(role.exists)
    XCTAssertTrue(role.label.contains("最终交付") || (role.value as? String) == "最终交付" || role.staticTexts["最终交付"].exists,
      "Closing must default to the actual executor/final-delivery role without selecting another role")
    XCTAssertFalse(app.staticTexts["当前业务阶段不能派发此角色。"].exists,
      "The current closing stage must allow the executor role")
    let noMapping = app.staticTexts["没有可用映射"].firstMatch
    reveal(noMapping); XCTAssertTrue(noMapping.exists)
    XCTAssertFalse(app.buttons["task-hub-submit"].isEnabled,
      "This human-only project has no verified Host mapping; stage availability must not fabricate a dispatch target")
    shot("closing-final-delivery-role-allowed-without-fabricated-host-binding")
    tap(form.buttons["关闭"])
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: form)], timeout: 10), .completed)
    waitForStage("待最终交付")
  }

  @MainActor private func chooseTab(_ label: String) { tap(app.segmentedControls["task-hub-detail-tabs"].buttons[label]) }
  @MainActor private func choose(_ identifier: String, label: String) {
    tap(app.descendants(matching: .any).matching(identifier: identifier).firstMatch)
    tap(named(label))
  }
  @MainActor private func waitForStage(_ text: String) {
    let element = app.staticTexts["task-hub-workflow-stage"]
    reveal(element)
    let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND label CONTAINS %@", text), object: element)
    XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 40), .completed, "The actual server workflow stage must be confirmed")
  }
  @MainActor private func submitForm() {
    hideKeyboard()
    let submit = app.buttons["task-hub-submit"]
    XCTAssertTrue(submit.isEnabled, "Current required inputs and real connection must permit submission")
    tap(submit)
    let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: submit)
    let result = XCTWaiter.wait(for: [closed], timeout: 45)
    if result != .completed { print(app.debugDescription) }
    XCTAssertEqual(result, .completed, "The valid form must receive a real confirmed server response")
  }
  @MainActor private func fill(_ element: XCUIElement, _ text: String) {
    reveal(element); element.tap()
    if let existing = element.value as? String, !existing.isEmpty {
      element.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
    }
    element.typeText(text); hideKeyboard()
  }
  @MainActor private func hideKeyboard() {
    let done = app.buttons["task-hub-keyboard-dismiss"]
    if done.exists && done.isHittable { done.tap() }
    else if app.keyboards.buttons["Done"].exists { app.keyboards.buttons["Done"].tap() }
  }
  @MainActor private func actualTaskHubPage() -> (kind: String, bar: XCUIElement)? {
    let service = app.navigationBars["主服务与账户"]
    if service.exists && service.buttons["完成"].isHittable { return ("service", service) }
    let root = app.navigationBars["项目与任务"]
    if root.exists && root.buttons["task-hub-service"].isHittable && root.buttons["task-hub-dismiss"].isHittable { return ("root", root) }
    let projects = app.navigationBars.allElementsBoundByIndex.filter {
      $0.buttons["task-hub-create"].isHittable && $0.buttons["task-hub-project-settings"].isHittable
    }
    if projects.count == 1 { return ("project", projects[0]) }
    let picker = app.segmentedControls["task-hub-detail-tabs"]
    let menu = app.buttons["task-hub-detail-tabs"]
    let pickerVisible = (picker.exists && picker.frame.intersects(app.frame)) || (menu.exists && menu.frame.intersects(app.frame))
    if pickerVisible {
      let details = app.navigationBars.allElementsBoundByIndex.filter {
        $0.buttons["BackButton"].isHittable && !$0.buttons["task-hub-create"].isHittable && $0.label != "主服务与账户"
      }
      if details.count == 1 { return ("detail", details[0]) }
    }
    return nil
  }

  @MainActor private func navigationDiagnostic(_ reason: String) {
    print("Actual TaskHub navigation: reason=\(reason), requestedProject=\(projectName), requestedTask=\(taskTitle), bars=\(app.navigationBars.allElementsBoundByIndex.map { "\($0.label):\($0.frame)" })")
    shot("task-hub-navigation-\(reason)")
  }

  @MainActor private func waitForTaskHubPage(until deadline: Date) -> (kind: String, bar: XCUIElement)? {
    let remaining = deadline.timeIntervalSinceNow
    guard remaining > 0 else { return nil }
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in self.actualTaskHubPage() != nil }, object: app)
    guard XCTWaiter.wait(for: [ready], timeout: remaining) == .completed else { return nil }
    return actualTaskHubPage()
  }

  @MainActor private func returnToTaskHubRoot(timeout: TimeInterval) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    for step in 0..<4 {
      guard let page = waitForTaskHubPage(until: deadline) else { break }
      if page.kind == "root" { return true }
      guard step < 3, page.kind == "project" || page.kind == "detail" else { break }
      let back = page.bar.buttons["BackButton"]
      guard back.exists && back.isHittable && page.bar.frame.contains(back.frame) else { break }
      print("Actual scoped TaskHub back: page=\(page.kind), title=\(page.bar.label), frame=\(back.frame)")
      back.tap()
    }
    navigationDiagnostic("root-not-reached")
    XCTFail("Only actual TaskHub project/detail back navigation may reach its root within the original deadline")
    return false
  }

  @MainActor private func listContaining(_ element: XCUIElement) -> XCUIElement? {
    guard element.exists else { return nil }
    let candidates = app.collectionViews.allElementsBoundByIndex.filter { list in
      guard list.isHittable, list.frame.intersects(app.frame) else { return false }
      let query = list.descendants(matching: element.elementType)
      if !element.identifier.isEmpty { return query.matching(identifier: element.identifier).count > 0 }
      return !element.label.isEmpty && query.matching(NSPredicate(format: "label == %@", element.label)).count > 0
    }
    return candidates.count == 1 ? candidates[0] : nil
  }

  @MainActor private func actualList(for element: XCUIElement? = nil) -> XCUIElement? {
    if let element, let list = listContaining(element) { return list }
    let candidates = app.collectionViews.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.intersects(app.frame) }
    if actualTaskHubPage()?.kind == "service" {
      let serviceLists = candidates.filter {
        $0.staticTexts["服务身份"].exists || $0.staticTexts["连接与账户"].exists ||
        $0.buttons["task-hub-reconnect"].exists || $0.buttons["task-hub-logout"].exists
      }
      return serviceLists.count == 1 ? serviceLists[0] : nil
    }
    if actualTaskHubPage()?.kind == "detail" {
      // A visible frozen-candidate header identifies the active delivery List
      // even when all decision buttons are still lazy, below its viewport.
      let visibleDelivery = candidates.filter { visibleDeliverySection(in: $0) }
      if !visibleDelivery.isEmpty { return visibleDelivery.count == 1 ? visibleDelivery[0] : nil }
      let detailLists = candidates.filter {
        $0.staticTexts["task-hub-workflow-stage"].exists ||
        $0.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ OR identifier BEGINSWITH %@ OR identifier BEGINSWITH %@ OR identifier BEGINSWITH %@", "task-hub-round-", "task-hub-action-response-", "task-hub-action-pass-", "task-hub-action-fail-")).count > 0
      }
      if detailLists.count == 1 { return detailLists[0] }
    }
    return candidates.count == 1 ? candidates[0] : nil
  }

  @MainActor private func visibleDeliverySection(in list: XCUIElement) -> Bool {
    guard actualTaskHubPage()?.kind == "detail" else { return false }
    let tabs = app.segmentedControls["task-hub-detail-tabs"]
    guard tabs.exists && tabs.buttons["交付"].isSelected else { return false }
    let viewport = uncoveredListRect(list)
    guard viewport.width > 80 && viewport.height > 120 else { return false }
    do {
      let snapshot = try list.snapshot()
      var anchors = 0
      func visit(_ node: any XCUIElementSnapshot) {
        if node.elementType == .staticText && node.label == "本轮冻结候选"
          && !node.frame.isEmpty && viewport.contains(CGPoint(x: node.frame.midX, y: node.frame.midY)) {
          anchors += 1
        }
        for child in node.children { visit(child) }
      }
      visit(snapshot)
      if anchors == 1 { print("Actual visible delivery List anchor: list=\(list.frame), viewport=\(viewport), exactHeaderMatches=\(anchors)") }
      return anchors == 1
    } catch {
      XCTFail("The actual delivery List must provide a fresh exact-section snapshot")
      return false
    }
  }

  @MainActor private func uncoveredListRect(_ list: XCUIElement) -> CGRect {
    var rect = list.frame.intersection(app.frame)
    guard !rect.isNull else { return .zero }
    if let page = actualTaskHubPage() { rect.origin.y = max(rect.minY, page.bar.frame.maxY + 8) }
    else {
      let bars = app.navigationBars.allElementsBoundByIndex.filter { $0.isHittable }
      if bars.count == 1 { rect.origin.y = max(rect.minY, bars[0].frame.maxY + 8) }
    }
    var bottom = min(list.frame.maxY, app.frame.maxY) - 8
    let picker = app.segmentedControls["task-hub-detail-tabs"]
    let menu = app.buttons["task-hub-detail-tabs"]
    if actualTaskHubPage()?.kind == "detail" {
      for top in [picker, menu] where top.exists && top.frame.intersects(app.frame) { rect.origin.y = max(rect.minY, top.frame.maxY + 8) }
      for id in ["task-hub-actions", "task-hub-action-start", "task-hub-enter-current-round"] {
        let footer = app.buttons[id]
        if footer.exists && footer.frame.intersects(app.frame) { bottom = min(bottom, footer.frame.minY - 8) }
      }
      let history = app.staticTexts["正在查看通知原轮次 · 历史只读"]
      if history.exists && history.frame.intersects(app.frame) { bottom = min(bottom, history.frame.minY - 8) }
    }
    for blocker in app.keyboards.allElementsBoundByIndex + app.tabBars.allElementsBoundByIndex where blocker.exists && blocker.isHittable && blocker.frame.intersects(rect) {
      bottom = min(bottom, blocker.frame.minY - 8)
    }
    let keyboardToolbar = app.buttons["task-hub-keyboard-dismiss"]
    if keyboardToolbar.exists && keyboardToolbar.isHittable && keyboardToolbar.frame.intersects(rect) { bottom = min(bottom, keyboardToolbar.frame.minY - 8) }
    rect.size.height = max(0, bottom - rect.minY)
    return rect
  }

  @MainActor private func visibleCellPositions(_ list: XCUIElement, in viewport: CGRect) -> [String: [CGFloat]] {
    var positions: [String: [CGFloat]] = [:]
    for cell in list.cells.allElementsBoundByIndex where cell.frame.intersects(viewport) {
      var key = cell.identifier
      if key.isEmpty {
        let text = cell.staticTexts.firstMatch
        let button = cell.buttons.firstMatch
        if text.exists { key = text.identifier + "/" + text.label }
        else if button.exists { key = button.identifier + "/" + button.label }
      }
      if !key.isEmpty { positions[key, default: []].append(cell.frame.minY) }
    }
    return positions
  }

  @MainActor private func scrollIndicatorValues(_ list: XCUIElement) -> [String] {
    do {
      let snapshot = try list.snapshot()
      var values: [String] = []
      func visit(_ node: any XCUIElementSnapshot) {
        if node.label.hasPrefix("垂直滚动条"), let value = node.value as? String {
          values.append(value)
        }
        for child in node.children { visit(child) }
      }
      visit(snapshot)
      return values
    } catch {
      XCTFail("The actual scoped List must provide a fresh scroll-position snapshot")
      return []
    }
  }

  @MainActor @discardableResult private func scrollContent(up: Bool, within selected: XCUIElement? = nil) -> Bool {
    guard let list = selected ?? actualList() else {
      navigationDiagnostic("no-unique-active-list")
      XCTFail("A unique actual native List must own the gesture; background lists cannot be selected arbitrarily")
      return false
    }
    let viewport = uncoveredListRect(list)
    guard viewport.width > 80 && viewport.height > 120 else {
      shot("list-has-no-uncovered-drag-space")
      XCTFail("The actual List must expose drag space above its footer and keyboard")
      return false
    }
    let before = visibleCellPositions(list, in: viewport)
    let oldValues = scrollIndicatorValues(list)
    let origin = app.coordinate(withNormalizedOffset: .zero)
    let x = viewport.maxX - min(35, viewport.width * 0.12) - app.frame.minX
    let top = viewport.minY + viewport.height * 0.18 - app.frame.minY
    let bottom = viewport.maxY - viewport.height * 0.18 - app.frame.minY
    let from = origin.withOffset(CGVector(dx: x, dy: up ? bottom : top))
    let to = origin.withOffset(CGVector(dx: x, dy: up ? top : bottom))
    from.press(forDuration: 0.05, thenDragTo: to)
    let after = visibleCellPositions(list, in: uncoveredListRect(list))
    let moved = before.contains { key, positions in
      guard let actual = after[key] else { return false }
      return zip(positions, actual).contains { abs($0.0 - $0.1) > 1 }
    }
    let newValues = scrollIndicatorValues(list)
    // Indicator appearance/disappearance alone is not evidence of content movement.
    let indicatorMoved = !oldValues.isEmpty && oldValues.count == newValues.count && oldValues != newValues
    print("Actual List displacement: direction=\(up ? "up" : "down"), viewport=\(viewport), commonCellMoved=\(moved), indicatorMoved=\(indicatorMoved)")
    return moved || indicatorMoved
  }

  @MainActor private func targetUncovered(_ element: XCUIElement) -> Bool {
    guard element.exists else { return false }
    let frame = element.frame
    let roundChevron = element.elementType == .button && element.identifier.hasPrefix("task-hub-round-") && element.label.hasPrefix("第 ")
    guard element.isHittable else { return false }
    var viewport = app.frame
    if let list = listContaining(element) { viewport = uncoveredListRect(list) }
    else if let keyboard = app.keyboards.allElementsBoundByIndex.first(where: { $0.isHittable && $0.frame.intersects(viewport) }) {
      viewport.size.height = max(0, keyboard.frame.minY - viewport.minY - 8)
    }
    if roundChevron { return viewport.contains(CGPoint(x: frame.maxX - 20, y: frame.minY + min(22, frame.height / 2))) }
    if element.elementType == .button && listContaining(element) != nil { return viewport.contains(frame) }
    return viewport.contains(CGPoint(x: frame.midX, y: frame.midY))
  }

  @MainActor private func revealActualTarget(_ element: XCUIElement, initialWait: TimeInterval, until deadline: Date? = nil, routeChanged: (() -> Bool)? = nil) -> Bool {
    if routeChanged?() == true { return false }
    if targetUncovered(element) { return true }
    let wait = min(initialWait, max(0, deadline?.timeIntervalSinceNow ?? initialWait))
    if wait > 0 { _ = element.waitForExistence(timeout: wait) }
    for up in [true, false] {
      var stalled = 0
      for _ in 0..<12 {
        if routeChanged?() == true { return false }
        if targetUncovered(element) { return true }
        if let deadline, Date() >= deadline { return false }
        if scrollContent(up: up, within: actualList(for: element)) { stalled = 0 } else { stalled += 1 }
        if stalled >= 2 { break }
      }
    }
    return targetUncovered(element)
  }

  @MainActor private func tap(_ element: XCUIElement) { reveal(element); element.tap() }
  @MainActor private func reveal(_ element: XCUIElement) {
    let shown = revealActualTarget(element, initialWait: 2);
    if (!shown) { navigationDiagnostic("exact-target-not-uncovered") }
    XCTAssertTrue(element.exists)
    XCTAssertTrue(element.isHittable)
    XCTAssertTrue(shown, "The exact native target must be inside its uncovered viewport after actual List displacement")
  }

  @MainActor private func named(_ label: String) -> XCUIElement {
    let button = app.buttons[label].firstMatch
    if button.exists { return button }
    let text = app.staticTexts[label].firstMatch
    return text.exists ? text : button
  }
  @MainActor private func prefixedButton(_ prefix: String) -> XCUIElement { app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch }
  @MainActor private func prefixedField(_ prefix: String) -> XCUIElement { app.textFields.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch }
  @MainActor private func shot(_ label: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "\(nonce)-\(label)"; attachment.lifetime = .keepAlways; add(attachment)
  }
}
