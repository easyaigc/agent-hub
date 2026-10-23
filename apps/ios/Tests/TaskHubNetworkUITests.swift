import CryptoKit
import Foundation
import Security
import XCTest

/// Real DEV HTTP responses are lost/held by a forwarding proxy, never fabricated.
/// The runner independently verifies the original receipts and actual event counts.
final class TaskHubNetworkUITests: XCTestCase {
  private var app: XCUIApplication!
  private var observer: URLSession!
  private var endpoint: URL!
  private var runID = ""
  private var projectName = ""
  private var taskTitle = ""

  private struct Observation: Decodable {
    struct Fixture: Decodable {
      var projectId: String; var taskId: String; var projectName: String; var taskTitle: String
      var status: String; var workflowStage: String; var currentRound: Int; var progressCount: Int
      var taskKey: String?
      var baselineBodies: [String]?
      var baselineOperationIds: [String]?
    }
    struct Operation: Decodable {
      var mode: String
      var operationId: String
      var taskId: String
      var backendStatus: Int?
      var backendCommittedAt: String?
      var downstreamClosedAt: String?
      var requestCount: Int
      var receiptQueries: Int
      var receiptConfirmedAt: String?
      var recoveredDetailReads: Int
      var firstRecoveredProgressCount: Int?
      var recoveredSSEConnections: Int
    }
    struct ExternalProgress: Decodable {
      var operationId: String
      var taskId: String
      var reporterDeviceId: String
      var requestCount: Int
      var backendStatus: Int?
      var backendCommittedAt: String?
      var committedRevision: Int?
      var actualProgressCount: Int?
      var nativeSSEFrames: Int
      var firstNativeSSEAt: String?
      var nativeDetailReads: Int
      var firstNativeDetailAt: String?
      var firstNativeProgressCount: Int?
    }
    var runId: String
    var operations: [Operation]
    var externalProgress: ExternalProgress?
    var fixture: Fixture?
  }
  private struct ReporterLogin: Decodable {
    struct Actor: Decodable { var id: String; var kind: String }
    var actor: Actor
    var token: String
  }
  private struct ReporterTask: Decodable {
    struct Event: Decodable { var id: String; var kind: String; var body: String; var actorId: String }
    var id: String
    var title: String
    var projectId: String
    var ownerId: String
    var status: String
    var workflowStage: String
    var currentRound: Int
    var revision: Int
    var events: [Event]
  }
  private struct ExternalIntent {
    var operationId: String
    var deviceId: String
    var body: String
    var revision: Int
  }

  override func setUpWithError() throws {
    continueAfterFailure = false
    addUIInterruptionMonitor(withDescription: "DEV keyboard onboarding") { alert in
      let tutorial = alert.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Speed up your typing by sliding your finger")).firstMatch
      if tutorial.exists && alert.buttons["Continue"].exists { alert.buttons["Continue"].tap(); return true }
      return false
    }
  }
  override func tearDownWithError() throws { observer?.invalidateAndCancel() }

  @MainActor func testTaskHubRealUnknownAndEndWaitingDEV() async throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ name: String) -> String? { environment[name] ?? environment["TEST_RUNNER_" + name] }
    XCTAssertEqual(value("TASK_HUB_NETWORK_SCENARIO"), "1", "Only the isolated network runner may select this scenario")
    endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(value("TASK_HUB_ENDPOINT"))))
    XCTAssertEqual(endpoint.scheme, "https")
    XCTAssertEqual(endpoint.port, 19546)
    XCTAssertTrue(endpoint.host?.hasSuffix(".local") == true)
    runID = try XCTUnwrap(value("TASK_HUB_NETWORK_RUN_ID"))
    XCTAssertNotNil(UUID(uuidString: runID))
    let pin = try XCTUnwrap(value("TASK_HUB_NETWORK_CERT_SHA256"))
    XCTAssertEqual(pin.count, 64)
    let username = try XCTUnwrap(value("TASK_HUB_USERNAME"))
    let password = try XCTUnwrap(value("TASK_HUB_PASSWORD"))
    let nonce = String(runID.replacingOccurrences(of: "-", with: "").prefix(12))
    projectName = "原生网络故障 DEV " + nonce
    taskTitle = "原回执与结束等待 " + nonce
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 5
    configuration.httpCookieStorage = nil
    configuration.urlCredentialStorage = nil
    observer = URLSession(configuration: configuration,
      delegate: NetworkObservationTrust(host: endpoint.host!, port: 19546, pin: pin), delegateQueue: nil)
    let initial = try await observation()
    XCTAssertEqual(initial.runId, runID)
    XCTAssertTrue(initial.operations.isEmpty, "This run must own a fresh fault observation scope")
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-task-hub-acceptance", "-task-hub-network-acceptance"]
    // Only the existing DEBUG gate pre-fills the secure field. Never type/log a password.
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = password
    app.launchChineseTest()
    enterWorkspace()
    connectToEndpoint(endpoint)
    if !app.buttons["task-hub-login"].exists { logoutToLogin() }
    login(username)
    if value("TASK_HUB_NETWORK_API_FIXTURE") == "1" {
      let fixture = try XCTUnwrap(initial.fixture, "The runner must first create and read back its own real API fixture")
      XCTAssertEqual(fixture.projectName, projectName); XCTAssertEqual(fixture.taskTitle, taskTitle)
      XCTAssertNotNil(UUID(uuidString: fixture.projectId)); XCTAssertNotNil(UUID(uuidString: fixture.taskId))
      XCTAssertEqual(fixture.status, "doing"); XCTAssertEqual(fixture.workflowStage, "development")
      XCTAssertEqual(fixture.currentRound, 1); XCTAssertEqual(fixture.progressCount, 0)
      openProject()
      let row = app.buttons["task-hub-task-" + fixture.taskId]
      XCTAssertTrue(row.waitForExistence(timeout: 20))
      let title = row.staticTexts[taskTitle].firstMatch
      reveal(title); XCTAssertTrue(row.frame.contains(title.frame)); title.tap()
      XCTAssertTrue(app.staticTexts["task-hub-detail-title"].waitForExistence(timeout: 20))
      XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
      let stage = app.staticTexts["task-hub-workflow-stage"]
      reveal(stage); XCTAssertTrue(stage.label.contains("开发中"))
    } else {
      createProjectAndTask(reviewerName: value("TASK_HUB_REVIEWER_NAME") ?? "验收成员")
      chooseTab("需求")
      respondAndStart()
    }
    shot("real-human-network-task-started-uncompleted")

    let dropBody = "native-network/drop:\(runID) · 实际进度一，仅丢失本次下行响应。"
    let firstDraft = "仅本机新草稿一 \(runID)，不得自动重放或被回执覆盖。"
    openProgress()
    fill(app.textViews["task-hub-body"], dropBody)
    submitWithoutWaiting()
    let dropped = try await waitForCommit("drop")
    XCTAssertEqual(dropped.requestCount, 1)
    XCTAssertTrue(app.buttons["task-hub-submit"].exists, "A lost reply must not dismiss the original form")
    // The observed backend success is not used to patch the app. Its journal must be unknown.
    openReceipts()
    assertOriginalReceipt(dropped.operationId, state: "结果未知")
    showImmutableBody(dropBody)
    backFromReceipts()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, dropBody)
    fill(app.textViews["task-hub-body"], firstDraft)
    XCTAssertFalse(app.buttons["task-hub-submit"].isEnabled, "An unresolved original operation must block re-submission")
    tap(app.buttons["task-hub-save-draft"])
    shot("unknown-original-intent-and-new-draft-retained")

    openReceipts()
    assertOriginalReceipt(dropped.operationId, state: "结果未知")
    queryOriginalReceipt(dropped.operationId)
    backFromReceipts()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, firstDraft, "Querying the original receipt must preserve newer editable content")
    let dropRecovery = try await waitForRecovery("drop", progressCount: 1)
    XCTAssertEqual(dropRecovery.operationId, dropped.operationId)
    XCTAssertTrue(app.buttons["task-hub-rebase"].waitForExistence(timeout: 10), "Successful original receipt recovery must refresh the actual task revision")
    shot("original-receipt-restores-online-detail-and-sse-without-relaunch")
    closeProgress()
    chooseTab("过程")
    reveal(app.staticTexts[dropBody].firstMatch)
    XCTAssertTrue(app.staticTexts[dropBody].firstMatch.exists)
    // Real encrypted cache/draft restoration occurs only after proving receipt recovery
    // on the original offline page, so app relaunch cannot conceal a broken recovery path.
    app.terminate(); app.launchChineseTest(); enterWorkspace(); reopenTask(); openProgress()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, firstDraft)
    shot("confirmed-original-operation-and-new-draft-survive-relaunch")
    closeProgress()
    let afterDrop = try await observation()
    let dropProof = try XCTUnwrap(afterDrop.operations.first { $0.mode == "drop" })
    XCTAssertEqual(dropProof.operationId, dropped.operationId)
    XCTAssertEqual(dropProof.requestCount, 1, "The app must not automatically re-send an unknown write")
    XCTAssertGreaterThanOrEqual(dropProof.receiptQueries, 1)

    let holdBody = "native-network/hold:\(runID) · 实际进度二，结束手机等待仍须查询原回执。"
    let secondDraft = "仅本机新草稿二 \(runID)，不是远端取消或另一笔进度。"
    openProgress()
    adoptLatestRevisionIfNeeded()
    fill(app.textViews["task-hub-body"], holdBody)
    submitWithoutWaiting()
    let held = try await waitForCommit("hold")
    XCTAssertNotEqual(held.operationId, dropped.operationId)
    XCTAssertEqual(held.taskId, dropped.taskId)
    XCTAssertEqual(held.requestCount, 1)
    let end = app.buttons["task-hub-end-waiting"]
    XCTAssertTrue(end.waitForExistence(timeout: 5), "A real in-flight write must expose local end-waiting")
    tap(end)
    openReceipts()
    assertOriginalReceipt(held.operationId, state: "结果未知")
    XCTAssertTrue(named("已结束手机等待；远端结果仍需查询原回执").exists)
    backFromReceipts()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, holdBody)
    fill(app.textViews["task-hub-body"], secondDraft)
    XCTAssertFalse(app.buttons["task-hub-submit"].isEnabled)
    tap(app.buttons["task-hub-save-draft"])
    shot("end-waiting-retains-same-id-and-editable-draft")
    openReceipts(); queryOriginalReceipt(held.operationId); backFromReceipts()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, secondDraft)
    _ = try await waitForRecovery("hold", progressCount: 2)
    closeProgress()
    let finalStage = app.staticTexts["task-hub-workflow-stage"]
    reveal(finalStage); XCTAssertTrue(finalStage.label.contains("开发中"), "This fault task must remain uncompleted")
    chooseTab("过程")
    let firstEvent = app.staticTexts[dropBody].firstMatch
    let secondEvent = app.staticTexts[holdBody].firstMatch
    reveal(firstEvent); XCTAssertTrue(firstEvent.exists)
    reveal(secondEvent); XCTAssertTrue(secondEvent.exists)
    shot("two-real-progress-events-original-task-still-uncompleted")
    let final = try await waitForClosedHold()
    XCTAssertEqual(final.operations.count, 2)
    for proof in final.operations {
      XCTAssertEqual(proof.requestCount, 1)
      XCTAssertGreaterThanOrEqual(proof.receiptQueries, 1)
      XCTAssertNotNil(proof.backendCommittedAt)
      XCTAssertNotNil(proof.receiptConfirmedAt)
      XCTAssertGreaterThanOrEqual(proof.recoveredDetailReads, 1)
      XCTAssertGreaterThanOrEqual(proof.recoveredSSEConnections, 1)
    }

    // All fault/recovery assertions above finish before a third, independent real
    // human write. Its returned detail is verified here, never injected into the app.
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
    XCTAssertTrue(app.segmentedControls["task-hub-detail-tabs"].buttons["过程"].isSelected)
    XCTAssertFalse(app.buttons["task-hub-submit"].exists)
    let externalBody = "native-network/external-sse:\(runID)"
    let externalText = app.staticTexts[externalBody].firstMatch
    XCTAssertFalse(externalText.exists, "The independent progress must not exist before its actual write")
    shot("before-external-write-same-task-process-page")
    let intent = try await reportExternalProgress(username: username, password: password,
      taskId: held.taskId, dropOperationId: dropped.operationId, holdOperationId: held.operationId,
      dropBody: dropBody, holdBody: holdBody, body: externalBody)
    // Only passive AX observation occurs between the external POST and appearance.
    // No refresh, tab change, relaunch, or state restoration can manufacture this proof.
    XCTAssertTrue(externalText.waitForExistence(timeout: 20), "An actual external progress event must automatically appear through SSE on the unchanged process page")
    XCTAssertEqual(externalText.label, intent.body)
    XCTAssertTrue(app.segmentedControls["task-hub-detail-tabs"].buttons["过程"].isSelected)
    shot("external-sse-ui-appeared-before-any-view-action")
    let externalProof = try await waitForExternalRefresh(intent.operationId)
    XCTAssertEqual(externalProof.taskId, held.taskId)
    XCTAssertEqual(externalProof.reporterDeviceId, intent.deviceId)
    XCTAssertEqual(externalProof.requestCount, 1)
    XCTAssertEqual(externalProof.backendStatus, 201)
    XCTAssertEqual(externalProof.committedRevision, intent.revision)
    XCTAssertEqual(externalProof.actualProgressCount, 3)
    XCTAssertEqual(externalProof.firstNativeProgressCount, 3)
    XCTAssertGreaterThanOrEqual(externalProof.nativeSSEFrames, 1)
    XCTAssertGreaterThanOrEqual(externalProof.nativeDetailReads, 1)
    XCTAssertNotNil(externalProof.firstNativeSSEAt)
    XCTAssertNotNil(externalProof.firstNativeDetailAt)
    // After automatic appearance is proved, scroll only upward to expose the
    // appended lazy timeline text for a screenshot. This cannot pull to refresh.
    for _ in 0..<6 {
      if targetUncovered(externalText) { break }
      if !scrollContent(up: true, within: actualList(for: externalText)) { break }
    }
    XCTAssertTrue(externalText.isHittable)
    let externalList = try XCTUnwrap(actualList(for: externalText))
    XCTAssertTrue(uncoveredListRect(externalList).contains(externalText.frame))
    shot("third-real-progress-visible-after-automatic-sse-update")
    let afterExternal = try await observation()
    XCTAssertEqual(afterExternal.operations.count, 2, "The independent third event must not alter the two fault-operation scopes")
    for proof in afterExternal.operations { XCTAssertEqual(proof.requestCount, 1) }
    let sseAttachment = XCTAttachment(string: "runId=\(runID)\nexternalOperationId=\(intent.operationId)\nreporterDeviceId=\(intent.deviceId)\ntaskId=\(held.taskId)\nrevision=\(intent.revision)\nactualProgressCount=3\nNative SSE business frame, automatic detail refresh and passive UI appearance all verified; no refresh/relaunch/tab switch occurred after the external write.")
    sseAttachment.name = "actual-external-sse-automatic-ui-proof"; sseAttachment.lifetime = .keepAlways; add(sseAttachment)
    let attachment = XCTAttachment(string: "runId=\(runID)\ndropOperationId=\(dropped.operationId)\nholdOperationId=\(held.operationId)\ntaskId=\(held.taskId)\nTask intentionally remains uncompleted; no candidate/acceptance/finalization was attempted.")
    attachment.name = "real-network-original-operation-ids"; attachment.lifetime = .keepAlways; add(attachment)
    // Leave the dedicated DEV app on the original gateway before the harness closes
    // its temporary proxy. This switches only the connection path of the same identity.
    var primary = try XCTUnwrap(URLComponents(url: endpoint, resolvingAgainstBaseURL: false))
    primary.port = 19543
    connectToEndpoint(try XCTUnwrap(primary.url))
    shot("same-primary-origin-restored-after-network-scenario")
  }

  @MainActor func testTaskHubBoundedHoldDEV() async throws { try await runBoundedNetwork("hold") }
  @MainActor func testTaskHubBoundedEndWaitingDEV() async throws { try await runBoundedNetwork("end-wait") }
  @MainActor func testTaskHubBoundedExternalSSEDEV() async throws { try await runBoundedNetwork("external") }

  @MainActor private func runBoundedNetwork(_ mode: String) async throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ key: String) -> String? { environment[key] ?? environment["TEST_RUNNER_" + key] }
    XCTAssertEqual(value("TASK_HUB_NETWORK_SCENARIO"), "1")
    XCTAssertEqual(value("TASK_HUB_NETWORK_CASE"), mode)
    endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(value("TASK_HUB_ENDPOINT"))))
    XCTAssertEqual(endpoint.scheme, "https"); XCTAssertEqual(endpoint.port, 19546)
    runID = try XCTUnwrap(value("TASK_HUB_NETWORK_RUN_ID")); XCTAssertNotNil(UUID(uuidString: runID))
    let pin = try XCTUnwrap(value("TASK_HUB_NETWORK_CERT_SHA256"))
    XCTAssertEqual(pin, "7d2d41b01839c8b8177b8c0d7f67e30a6f8ffb68fc97a8e5849e7da78f120239")
    let username = try XCTUnwrap(value("TASK_HUB_USERNAME")), password = try XCTUnwrap(value("TASK_HUB_PASSWORD"))
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 5; configuration.httpCookieStorage = nil; configuration.urlCredentialStorage = nil
    observer = URLSession(configuration: configuration,
      delegate: NetworkObservationTrust(host: endpoint.host!, port: 19546, pin: pin), delegateQueue: nil)
    let initial = try await observation(); XCTAssertTrue(initial.operations.isEmpty)
    let fixture = try XCTUnwrap(initial.fixture)
    projectName = fixture.projectName; taskTitle = fixture.taskTitle
    XCTAssertNotNil(UUID(uuidString: fixture.projectId)); XCTAssertNotNil(UUID(uuidString: fixture.taskId))
    XCTAssertEqual(fixture.status, "doing"); XCTAssertEqual(fixture.workflowStage, "development"); XCTAssertEqual(fixture.currentRound, 1)
    let baselineBodies = try XCTUnwrap(fixture.baselineBodies), baselineIDs = try XCTUnwrap(fixture.baselineOperationIds)
    XCTAssertEqual(baselineBodies.count, mode == "external" ? 2 : 1)
    XCTAssertEqual(fixture.progressCount, baselineBodies.count); XCTAssertEqual(baselineIDs.count, baselineBodies.count)
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication(); app.launchArguments = ["-ui-testing", "-task-hub-acceptance", "-task-hub-network-acceptance"]
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = password
    app.launchChineseTest(); enterWorkspace(); connectToEndpoint(endpoint)
    if !app.buttons["task-hub-login"].exists { logoutToLogin() }
    login(username); reopenTask()
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
    XCTAssertTrue(app.navigationBars[try XCTUnwrap(fixture.taskKey)].exists)
    chooseTab("过程")
    for body in baselineBodies { reveal(app.staticTexts[body].firstMatch); XCTAssertTrue(app.staticTexts[body].firstMatch.exists) }
    shot("bounded-" + mode + "-real-API-baselines-visible")

    if mode == "external" {
      let body = "native-network/external-sse:" + runID
      XCTAssertFalse(app.staticTexts[body].firstMatch.exists)
      // Keep this process page. The independent device performs the third real
      // write; no refresh, relaunch, tab switch or app-state injection follows it.
      let external = try await reportExternalProgress(username: username, password: password,
        taskId: fixture.taskId, dropOperationId: baselineIDs[0], holdOperationId: baselineIDs[1],
        dropBody: baselineBodies[0], holdBody: baselineBodies[1], body: body)
      let actual = app.staticTexts[body].firstMatch
      XCTAssertTrue(actual.waitForExistence(timeout: 20), "The actual external third progress must appear passively via SSE")
      let proof = try await waitForExternalRefresh(external.operationId)
      XCTAssertEqual(proof.taskId, fixture.taskId); XCTAssertEqual(proof.requestCount, 1)
      XCTAssertEqual(proof.actualProgressCount, 3); XCTAssertGreaterThan(proof.nativeSSEFrames, 0)
      XCTAssertGreaterThan(proof.nativeDetailReads, 0)
      shot("bounded-external-third-progress-passive-SSE-no-refresh")
      return
    }

    let body = "native-network/hold:\(runID) · 实际进度二，结束手机等待仍须查询原回执。"
    let newDraft = "bounded-new-draft:\(runID) · 保留新草稿，不重放原操作。"
    openProgress(); fill(app.textViews["task-hub-body"], body)
    submitWithoutWaiting()
    if app.navigationBars["任务 AI 数据共享"].waitForExistence(timeout: 2) {
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "5fa19018-bf21-4d0c-b300-9f26ec0fc3d5")).firstMatch.exists)
      for identity in ["agent-hub-task-management-dev-20261014", "agent-hub-task-management-dev-primary-20261014", "25d82f80-4d26-4e37-a71a-30aa3f5bd8bf"] {
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", identity)).firstMatch.exists)
      }
      shot("bounded-own-fixture-normal-AI-consent-before-any-business-write")
      tap(app.buttons["task-hub-ai-sharing-allow"])
      XCTAssertFalse(app.navigationBars["任务 AI 数据共享"].exists)
      XCTAssertEqual(app.textViews["task-hub-body"].value as? String, body)
      // Granting permission submits nothing. This is the first business POST.
      submitWithoutWaiting()
    }
    let held = try await waitForCommit("hold")
    XCTAssertEqual(held.taskId, fixture.taskId); XCTAssertEqual(held.requestCount, 1)
    XCTAssertNotEqual(held.operationId, baselineIDs[0])
    if mode == "end-wait" {
      let end = app.buttons["task-hub-end-waiting"]
      XCTAssertTrue(end.waitForExistence(timeout: 5)); tap(end)
      _ = try await waitForClosedHold()
    } else {
      // The real request retains its production 20s timeout. The proxy checks
      // actual start/close timing independently; this wait only observes it.
      let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["task-hub-end-waiting"])
      XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 25), .completed)
    }
    openReceipts(); assertOriginalReceipt(held.operationId, state: "结果未知")
    if mode == "end-wait" { XCTAssertTrue(named("已结束手机等待；远端结果仍需查询原回执").exists) }
    showImmutableBody(body); backFromReceipts()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, body)
    fill(app.textViews["task-hub-body"], newDraft)
    XCTAssertFalse(app.buttons["task-hub-submit"].isEnabled)
    tap(app.buttons["task-hub-save-draft"])
    openReceipts(); queryOriginalReceipt(held.operationId); backFromReceipts()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, newDraft)
    let recovered = try await waitForRecovery("hold", progressCount: 2)
    XCTAssertEqual(recovered.operationId, held.operationId); XCTAssertEqual(recovered.requestCount, 1)
    XCTAssertTrue(app.buttons["task-hub-rebase"].waitForExistence(timeout: 10))
    closeProgress(); chooseTab("过程")
    reveal(app.staticTexts[body].firstMatch); XCTAssertTrue(app.staticTexts[body].firstMatch.exists)
    let last = try await observation(); XCTAssertEqual(last.operations.count, 1)
    XCTAssertEqual(last.operations[0].requestCount, 1); XCTAssertNotNil(last.operations[0].receiptConfirmedAt)
    shot("bounded-" + mode + "-original-receipt-recovery-two-real-events")
  }

  private struct CSVHumanTask: Decodable {
    struct Round: Decodable {
      struct Delivery: Decodable {
        struct Candidate: Decodable { var artifactDigest: String }
        var candidateRef: String; var candidate: Candidate
      }
      struct Acceptance: Decodable {
        var id: String; var mode: String; var reviewerId: String; var status: String
        var candidateRef: String; var decisionNote: String?; var decidedAt: String?
      }
      var id: String; var number: Int; var delivery: Delivery?; var acceptances: [Acceptance]
    }
    var id: String; var key: String; var title: String; var projectId: String; var currentRound: Int
    var revision: Int; var status: String; var workflowStage: String; var reviewerId: String; var rounds: [Round]
  }

  @MainActor func testTaskHubActualCSVHumanRejectR1DEV() async throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ name: String) -> String? { environment[name] ?? environment["TEST_RUNNER_" + name] }
    XCTAssertEqual(value("TASK_HUB_CSV_HUMAN_SCENARIO"), "1")
    endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(value("TASK_HUB_ENDPOINT"))))
    XCTAssertEqual(endpoint.scheme, "https"); XCTAssertEqual(endpoint.port, 19543)
    let pin = "7d2d41b01839c8b8177b8c0d7f67e30a6f8ffb68fc97a8e5849e7da78f120239"
    let taskId = "ccfd1343-28b2-46de-997b-f14ff8834098"
    let projectId = "3e807e5c-037a-4740-afe1-01b739b2bf70"
    let roundId = "efdda4a1-6604-4b2c-b5b9-3b514b29186b"
    let acceptanceId = "27a5131e-3ef2-4ce5-8277-dab68958a06e"
    let actorId = "2826967d-57b6-42e6-bdcd-8d8e3905b5db"
    let digest = "cd5eb7d225268b1c15fa96bab5cffb3f172de4793e1ed6fe679bca624d1bb32d"
    let note = try XCTUnwrap(value("TASK_HUB_CSV_REJECTION_NOTE"))
    let noteBytes = Data(note.utf8)
    XCTAssertEqual(noteBytes.count, 1509)
    XCTAssertEqual(SHA256.hash(data: noteBytes).map { String(format: "%02x", $0) }.joined(),
      "86779851efaf9f61296ea333e94532d471bc57b0f668145bd512a0b9ae840410")
    let username = try XCTUnwrap(value("TASK_HUB_USERNAME"))
    let password = try XCTUnwrap(value("TASK_HUB_PASSWORD"))
    runID = try XCTUnwrap(value("TASK_HUB_NONCE"))
    projectName = try XCTUnwrap(value("TASK_HUB_CSV_PROJECT_NAME"))
    taskTitle = try XCTUnwrap(value("TASK_HUB_CSV_TASK_TITLE"))
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 180
    config.httpCookieStorage = nil; config.urlCredentialStorage = nil
    observer = URLSession(configuration: config,
      delegate: NetworkObservationTrust(host: endpoint.host!, port: 19543, pin: pin), delegateQueue: nil)
    let readDevice = UUID().uuidString.lowercased()
    var readToken: String?
    func readRequest(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> Data {
      let routes = ["auth/mobile/login", "auth/mobile/logout", "tasks/" + taskId]
      guard routes.contains(path), method == "GET" || path.hasPrefix("auth/mobile/") else { throw NetworkObservationError.invalidResponse }
      var request = URLRequest(url: endpoint.appendingPathComponent("api/v1").appendingPathComponent(path))
      request.httpMethod = method; request.timeoutInterval = 20
      request.setValue("application/json", forHTTPHeaderField: "Accept")
      request.setValue(readDevice, forHTTPHeaderField: "X-TaskHub-Device-Id")
      if let readToken { request.setValue("Bearer " + readToken, forHTTPHeaderField: "Authorization") }
      if let body { request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try JSONSerialization.data(withJSONObject: body) }
      let (data, response) = try await observer.data(for: request)
      guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw NetworkObservationError.invalidResponse }
      return data
    }
    let readLogin = try JSONDecoder().decode(ReporterLogin.self, from: await readRequest("auth/mobile/login", method: "POST",
      body: ["username": username, "password": password, "deviceId": readDevice, "deviceName": "native32-csv-human-prewrite:" + runID]))
    XCTAssertEqual(readLogin.actor.id, actorId); XCTAssertEqual(readLogin.actor.kind, "human")
    readToken = readLogin.token
    let cleanupSession = try XCTUnwrap(observer)
    let cleanupToken = readLogin.token
    let logoutURL = endpoint.appendingPathComponent("api/v1/auth/mobile/logout")
    addTeardownBlock {
      var request = URLRequest(url: logoutURL)
      request.httpMethod = "POST"; request.httpBody = Data("{}".utf8); request.timeoutInterval = 20
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.setValue("Bearer " + cleanupToken, forHTTPHeaderField: "Authorization")
      request.setValue(readDevice, forHTTPHeaderField: "X-TaskHub-Device-Id")
      let (_, response) = try await cleanupSession.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    func current() async throws -> CSVHumanTask {
      try JSONDecoder().decode(CSVHumanTask.self, from: await readRequest("tasks/" + taskId))
    }
    func requireR1Pending(_ task: CSVHumanTask) throws {
      XCTAssertEqual(task.id, taskId); XCTAssertEqual(task.projectId, projectId); XCTAssertEqual(task.currentRound, 1)
      XCTAssertEqual(task.status, "review"); XCTAssertEqual(task.workflowStage, "acceptance")
      let round = try XCTUnwrap(task.rounds.first { $0.id == roundId })
      XCTAssertEqual(round.number, 1); XCTAssertEqual(round.delivery?.candidate.artifactDigest, digest)
      let human = try XCTUnwrap(round.acceptances.first { $0.id == acceptanceId })
      XCTAssertEqual(human.reviewerId, actorId); XCTAssertEqual(human.mode, "human")
      XCTAssertEqual(human.status, "pending"); XCTAssertNil(human.decidedAt)
      let agent = try XCTUnwrap(round.acceptances.first { $0.mode == "agent_host" && $0.reviewerId == task.reviewerId })
      XCTAssertEqual(agent.status, "passed"); XCTAssertNotNil(agent.decidedAt)
      XCTAssertEqual(agent.candidateRef, human.candidateRef); XCTAssertEqual(agent.candidateRef, round.delivery?.candidateRef)
    }
    let initial = try await current(); try requireR1Pending(initial)
    XCTAssertEqual(initial.title, taskTitle)
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication(); app.launchArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchEnvironment["TASK_HUB_DEV_PASSWORD"] = password
    app.launchChineseTest()
    if value("TASK_HUB_CSV_REUSE_VERIFIED_LOGIN") == "1" {
      // Reuse only this scenario's preceding real reviewer login. The private
      // runner verifies its exact prior actor/task readback before selecting it.
      let entry = app.buttons["task-hub-entry"]
      XCTAssertTrue(entry.waitForExistence(timeout: 20))
      tap(entry)
      // The real restored stack may already be this exact CSV detail. Let the
      // existing exact-task helper retain it instead of first backing to root.
      XCTAssertFalse(app.buttons["task-hub-login"].exists)
      XCTAssertFalse(app.buttons["task-hub-project-create"].exists)
    } else {
      enterWorkspace()
      connectToEndpoint(endpoint)
      if !app.buttons["task-hub-login"].exists { logoutToLogin() }
      login(username, requireProjectCreation: false)
    }
    reopenTask()
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
    XCTAssertTrue(app.navigationBars[initial.key].exists)
    let picker = app.segmentedControls["task-hub-detail-tabs"]
    guard picker.waitForExistence(timeout: 5), picker.buttons["交付"].exists else {
      navigationDiagnostic("CSV-exact-delivery-picker-missing")
      XCTFail("The exact TaskHub Picker must expose 交付; do not search the List for a missing tab")
      return
    }
    let deliveryTab = picker.buttons["交付"]
    print("Actual CSV Picker before readiness: pickerFrame=\(picker.frame), buttonFrame=\(deliveryTab.frame), hittable=\(deliveryTab.isHittable), appFrame=\(app.frame)")
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      picker.exists && deliveryTab.exists && deliveryTab.isHittable
        && self.app.frame.contains(deliveryTab.frame) && picker.frame.intersects(deliveryTab.frame)
    }, object: app)
    guard XCTWaiter.wait(for: [ready], timeout: 5) == .completed else {
      navigationDiagnostic("CSV-exact-delivery-picker-not-ready")
      XCTFail("The exact Picker button must be fully visible and hittable after navigation")
      return
    }
    deliveryTab.tap()
    guard XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: deliveryTab)], timeout: 5) == .completed else {
      navigationDiagnostic("CSV-delivery-tab-not-selected")
      XCTFail("The exact 交付 Picker segment must actually become selected")
      return
    }
    print("Actual CSV TaskHub Picker: identifier=\(picker.identifier), selected=\(deliveryTab.label), isSelected=\(deliveryTab.isSelected), frame=\(picker.frame)")
    shot("CSV-exact-delivery-picker-selected")
    let fail = app.buttons["task-hub-action-fail-" + acceptanceId]
    guard revealCSVHumanDecision(fail, picker: picker, taskKey: initial.key) else { return }
    XCTAssertTrue(fail.isEnabled)
    let beforeOpen = try await current(); try requireR1Pending(beforeOpen)
    shot("actual-CSV-R1-agent-passed-human-pending-before-fail")
    fail.tap()
    XCTAssertTrue(app.navigationBars["人工验收不通过"].waitForExistence(timeout: 10))
    fill(app.textViews["task-hub-body"], note)
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String, note)
    let immediatelyBeforeSubmit = try await current(); try requireR1Pending(immediatelyBeforeSubmit)
    XCTAssertEqual(immediatelyBeforeSubmit.revision, beforeOpen.revision, "Any task change while typing stops this one decision; never submit an old revision")
    shot("actual-CSV-R1-exact-1509B-human-rejection-before-one-submit")
    submitConfirmedForm()
    let after = try await current()
    let retained = try XCTUnwrap(after.rounds.first { $0.id == roundId })
    XCTAssertEqual(retained.delivery?.candidate.artifactDigest, digest, "R1 candidate must remain unchanged")
    let decided = try XCTUnwrap(retained.acceptances.first { $0.id == acceptanceId })
    XCTAssertEqual(decided.status, "failed"); XCTAssertEqual(decided.reviewerId, actorId)
    XCTAssertEqual(decided.decisionNote, note); XCTAssertNotNil(decided.decidedAt)
    XCTAssertEqual(after.currentRound, 2)
    XCTAssertEqual(retained.acceptances.first { $0.mode == "agent_host" }?.status, "passed")
    shot("actual-simulator-CSV-human-failed-original-R1-kept-R2-created")
  }

  @MainActor private func revealCSVHumanDecision(_ button: XCUIElement, picker: XCUIElement, taskKey: String) -> Bool {
    let bar = app.navigationBars[taskKey]
    let lists = app.collectionViews.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.intersects(app.frame) }
    guard bar.exists, picker.buttons["交付"].isSelected, lists.count == 1 else {
      navigationDiagnostic("CSV-Human-no-unique-current-delivery-list")
      XCTFail("Only the exact CSV Task's selected delivery List may reveal its Human decision")
      return false
    }
    let list = lists[0]
    let action = app.buttons["task-hub-actions"]
    func viewport() -> CGRect {
      let actual = list.frame.intersection(app.frame)
      let top = max(actual.minY, max(bar.frame.maxY, picker.frame.maxY) + 8)
      let bottom = action.exists && action.isHittable ? min(actual.maxY - 8, action.frame.minY - 8) : actual.maxY - 8
      return CGRect(x: actual.minX, y: top, width: actual.width, height: max(0, bottom - top))
    }
    // This actual candidate has two 67-material summaries before the Human row.
    // Use at most eight fast gestures on this one foreground List; never reverse
    // through unrelated sections or select a background List.
    for attempt in 0..<8 {
      let visible = viewport()
      guard bar.exists, picker.buttons["交付"].isSelected, visible.width > 80, visible.height > 120 else { break }
      if button.exists && button.isEnabled && button.isHittable && visible.contains(button.frame) { return true }
      let before = visibleCellPositions(list, in: visible)
      let indicators = scrollIndicatorValues(list)
      let origin = app.coordinate(withNormalizedOffset: .zero)
      let x = visible.maxX - 35 - app.frame.minX
      let from = origin.withOffset(CGVector(dx: x, dy: visible.maxY - 20 - app.frame.minY))
      let to = origin.withOffset(CGVector(dx: x, dy: visible.minY + 20 - app.frame.minY))
      from.press(forDuration: 0.05, thenDragTo: to, withVelocity: .fast, thenHoldForDuration: 0)
      let after = visibleCellPositions(list, in: viewport())
      let commonMoved = before.contains { key, positions in
        guard let actual = after[key] else { return false }
        return zip(positions, actual).contains { abs($0.0 - $0.1) > 1 }
      }
      let newIndicators = scrollIndicatorValues(list)
      let indicatorMoved = !indicators.isEmpty && indicators.count == newIndicators.count && indicators != newIndicators
      print("Actual scoped CSV delivery displacement: attempt=\(attempt + 1), viewport=\(visible), commonMoved=\(commonMoved), indicatorMoved=\(indicatorMoved)")
      guard commonMoved || indicatorMoved else { break }
    }
    if button.exists && button.isEnabled && button.isHittable && viewport().contains(button.frame) { return true }
    navigationDiagnostic("CSV-exact-Human-row-not-uncovered-within-eight-gestures")
    XCTFail("The exact Human acceptance Button must be fully visible; no decision was submitted")
    return false
  }

  @MainActor func testTaskHubReadOnlyReceiptLinkCheckpointDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    func value(_ name: String) -> String? { environment[name] ?? environment["TEST_RUNNER_" + name] }
    XCTAssertEqual(value("TASK_HUB_RECEIPT_CHECKPOINT"), "1", "Only the explicit read-only checkpoint runner may select this test")
    runID = try XCTUnwrap(value("TASK_HUB_RECEIPT_CHECKPOINT_RUN_ID"))
    let originalRun = try XCTUnwrap(UUID(uuidString: runID))
    XCTAssertEqual(originalRun.uuidString.lowercased(), runID, "The checkpoint requires an explicit canonical original run ID")
    let operationID = try XCTUnwrap(value("TASK_HUB_RECEIPT_CHECKPOINT_OPERATION_ID"))
    let originalOperation = try XCTUnwrap(UUID(uuidString: operationID))
    XCTAssertEqual(originalOperation.uuidString.lowercased(), operationID, "The checkpoint requires the explicit already-submitted operation ID")
    let nonce = String(runID.replacingOccurrences(of: "-", with: "").prefix(12))
    projectName = "原生网络故障 DEV " + nonce
    taskTitle = "原回执与结束等待 " + nonce
    XCUIDevice.shared.orientation = .portrait
    app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-task-hub-acceptance"]
    app.launchChineseTest()
    enterWorkspace()
    XCTAssertFalse(app.buttons["task-hub-login"].exists, "The checkpoint must use its existing dedicated-simulator human session; it may not perform a new login")
    reopenTask()
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
    openProgress()
    XCTAssertEqual(app.textViews["task-hub-body"].value as? String,
      "native-network/drop:\(runID) · 实际进度一，仅丢失本次下行响应。", "The checkpoint must preserve the already-submitted run-scoped body")
    shot("readonly-checkpoint-existing-progress-form")
    openReceipts()
    let receipt = app.buttons["task-hub-receipt-" + operationID]
    reveal(receipt)
    XCTAssertTrue(receipt.exists)
    // Revealing the query button can leave its earlier lazy ID row above the
    // viewport. Reveal and verify that exact row separately, without querying.
    let exactIDs = app.staticTexts.matching(NSPredicate(format: "label == %@", operationID))
    let exactID = exactIDs.element
    reveal(exactID)
    XCTAssertEqual(exactIDs.count, 1, "Only the exact already-observed ID may satisfy the checkpoint")
    XCTAssertEqual(exactID.label, operationID, "The complete original ID must be exposed unchanged")
    shot("readonly-checkpoint-original-receipt-link-and-id")
    let proof = XCTAttachment(string: "Read-only locator checkpoint only. Existing run \(runID), operation \(operationID). No submit, progress, end-waiting, original-receipt query, draft edit, logout, login or fixture creation is performed. This is not a drop/hold/SSE recovery pass.")
    proof.name = "readonly-receipt-locator-checkpoint-scope"
    proof.lifetime = .keepAlways
    add(proof)
  }

  private func observation() async throws -> Observation {
    let url = endpoint.appendingPathComponent("__taskhub_network").appendingPathComponent(runID).appendingPathComponent("status")
    let (data, response) = try await observer.data(from: url)
    guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw NetworkObservationError.invalidResponse }
    let result = try JSONDecoder().decode(Observation.self, from: data)
    guard result.runId == runID else { throw NetworkObservationError.invalidResponse }
    return result
  }
  private func waitForCommit(_ mode: String) async throws -> Observation.Operation {
    let deadline = Date().addingTimeInterval(15)
    repeat {
      let result = try await observation()
      if let operation = result.operations.first(where: { $0.mode == mode && $0.backendCommittedAt != nil }) {
        XCTAssertTrue((200..<300).contains(operation.backendStatus ?? 0))
        XCTAssertNotNil(UUID(uuidString: operation.operationId))
        return operation
      }
      try await Task.sleep(nanoseconds: 200_000_000)
    } while Date() < deadline
    throw NetworkObservationError.actualCommitNotObserved
  }
  private func waitForClosedHold() async throws -> Observation {
    let deadline = Date().addingTimeInterval(8)
    repeat {
      let result = try await observation()
      if result.operations.first(where: { $0.mode == "hold" })?.downstreamClosedAt != nil { return result }
      try await Task.sleep(nanoseconds: 200_000_000)
    } while Date() < deadline
    throw NetworkObservationError.localWaitDidNotClose
  }
  private func waitForRecovery(_ mode: String, progressCount: Int) async throws -> Observation.Operation {
    let deadline = Date().addingTimeInterval(20)
    repeat {
      let result = try await observation()
      if let operation = result.operations.first(where: { $0.mode == mode }),
        operation.receiptConfirmedAt != nil, operation.recoveredDetailReads > 0,
        operation.firstRecoveredProgressCount == progressCount, operation.recoveredSSEConnections > 0 { return operation }
      try await Task.sleep(nanoseconds: 200_000_000)
    } while Date() < deadline
    throw NetworkObservationError.onlineRecoveryNotObserved
  }

  @MainActor private func reportExternalProgress(username: String, password: String,
    taskId: String, dropOperationId: String, holdOperationId: String,
    dropBody: String, holdBody: String, body: String) async throws -> ExternalIntent {
    XCTAssertNotNil(UUID(uuidString: taskId))
    let deviceId = UUID().uuidString.lowercased()
    let operationId = UUID().uuidString.lowercased()
    XCTAssertNotEqual(operationId, dropOperationId)
    XCTAssertNotEqual(operationId, holdOperationId)
    let environment = ProcessInfo.processInfo.environment
    let pin = try XCTUnwrap(environment["TASK_HUB_NETWORK_CERT_SHA256"] ?? environment["TEST_RUNNER_TASK_HUB_NETWORK_CERT_SHA256"])
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 20
    configuration.timeoutIntervalForResource = 180
    configuration.httpCookieStorage = nil; configuration.urlCredentialStorage = nil
    let reporter = URLSession(configuration: configuration,
      delegate: NetworkObservationTrust(host: endpoint.host!, port: 19546, pin: pin), delegateQueue: nil)
    defer { reporter.invalidateAndCancel() }
    func request(_ path: String, method: String = "GET", body: [String: Any]? = nil,
      token: String? = nil, operationId: String? = nil, status: Int) async throws -> Data {
      let allowed = ["auth/mobile/login", "tasks/" + taskId, "tasks/" + taskId + "/progress"]
      guard allowed.contains(path) else { throw NetworkObservationError.invalidResponse }
      var request = URLRequest(url: endpoint.appendingPathComponent("api/v1").appendingPathComponent(path))
      request.httpMethod = method; request.timeoutInterval = 20
      request.setValue("application/json", forHTTPHeaderField: "Accept")
      request.setValue(deviceId, forHTTPHeaderField: "X-TaskHub-Device-Id")
      if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
      if let operationId { request.setValue(operationId, forHTTPHeaderField: "X-TaskHub-Operation-Id") }
      if let body {
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      }
      let (data, response) = try await reporter.data(for: request)
      guard (response as? HTTPURLResponse)?.statusCode == status, data.count <= 2 * 1024 * 1024
      else { throw NetworkObservationError.invalidReporterResponse }
      return data
    }
    let loginData = try await request("auth/mobile/login", method: "POST",
      body: ["username": username, "password": password, "deviceId": deviceId,
        "deviceName": "native-network/external-sse:" + runID], status: 200)
    let login = try JSONDecoder().decode(ReporterLogin.self, from: loginData)
    guard login.actor.kind == "human", UUID(uuidString: login.actor.id) != nil,
      login.token.range(of: "^thm_[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil
    else { throw NetworkObservationError.invalidReporterResponse }
    let before = try JSONDecoder().decode(ReporterTask.self,
      from: await request("tasks/" + taskId, token: login.token, status: 200))
    XCTAssertEqual(before.id, taskId)
    XCTAssertEqual(before.title, taskTitle)
    XCTAssertEqual(before.ownerId, login.actor.id, "The independent device must use the actual task owner's human account")
    XCTAssertEqual(before.status, "doing"); XCTAssertEqual(before.workflowStage, "development")
    XCTAssertEqual(before.currentRound, 1); XCTAssertGreaterThanOrEqual(before.revision, 1)
    let previous = before.events.filter { $0.kind == "progress.reported" }
    XCTAssertEqual(previous.count, 2)
    XCTAssertEqual(previous.filter { $0.body == dropBody }.count, 1)
    XCTAssertEqual(previous.filter { $0.body == holdBody }.count, 1)
    let after = try JSONDecoder().decode(ReporterTask.self,
      from: await request("tasks/" + taskId + "/progress", method: "POST",
        body: ["revision": before.revision, "body": body], token: login.token,
        operationId: operationId, status: 201))
    XCTAssertEqual(after.id, taskId); XCTAssertEqual(after.projectId, before.projectId)
    XCTAssertEqual(after.ownerId, login.actor.id)
    XCTAssertEqual(after.status, "doing"); XCTAssertEqual(after.workflowStage, "development")
    XCTAssertEqual(after.currentRound, 1); XCTAssertEqual(after.revision, before.revision + 1)
    let events = after.events.filter { $0.kind == "progress.reported" }
    XCTAssertEqual(events.count, 3)
    XCTAssertEqual(events.filter { $0.body == dropBody }.count, 1)
    XCTAssertEqual(events.filter { $0.body == holdBody }.count, 1)
    let newEvents = events.filter { $0.body == body }
    XCTAssertEqual(newEvents.count, 1)
    XCTAssertEqual(newEvents.first?.actorId, login.actor.id)
    XCTAssertNotNil(UUID(uuidString: try XCTUnwrap(newEvents.first?.id)))
    // The harness retains only this exact reporter's auth in memory, independently
    // verifies its receipt, then revokes that session during its own cleanup.
    return ExternalIntent(operationId: operationId, deviceId: deviceId, body: body, revision: after.revision)
  }
  private func waitForExternalRefresh(_ operationId: String) async throws -> Observation.ExternalProgress {
    let deadline = Date().addingTimeInterval(20)
    repeat {
      let result = try await observation()
      if let proof = result.externalProgress, proof.operationId == operationId,
        proof.backendCommittedAt != nil, proof.actualProgressCount == 3,
        proof.nativeSSEFrames > 0, proof.firstNativeSSEAt != nil,
        proof.nativeDetailReads > 0, proof.firstNativeDetailAt != nil,
        proof.firstNativeProgressCount == 3 { return proof }
      try await Task.sleep(nanoseconds: 200_000_000)
    } while Date() < deadline
    throw NetworkObservationError.externalAutomaticRefreshNotObserved
  }

  @MainActor private func enterWorkspace() {
    let entry = app.buttons["task-hub-entry"]
    XCTAssertTrue(entry.waitForExistence(timeout: 20))
    tap(entry)
    XCTAssertTrue(returnToTaskHubRoot(timeout: 20))
  }

  @MainActor private func connectToEndpoint(_ selectedEndpoint: URL) {
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
    setToggle(trust, on: true)
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

  @MainActor private func logoutToLogin() {
    openServiceFromCurrentStack()
    revealServiceLogout()
    app.buttons["task-hub-logout"].tap()
    // Preserve reveal's original 20s deadline while avoiding its ancestor
    // match and list-scroll fallback for a native Alert action.
    guard let confirmation = waitForLogoutConfirmation(timeout: 20) else { return }
    shot("before-real-leaf-logout-confirmation")
    confirmation.tap()
    tap(app.navigationBars["主服务与账户"].buttons["完成"])
    XCTAssertTrue(app.buttons["task-hub-login"].waitForExistence(timeout: 20))
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
  @MainActor private func logoutConfirmation(in alert: XCUIElement) -> XCUIElement? {
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
    XCTAssertTrue(alert.frame.contains(button.frame))
    print("Actual leaf confirmation: matches=\(alert.buttons.matching(identifier: "task-hub-confirm-logout").count), frame=\(button.frame), label=\(button.label), hittable=\(button.isHittable)")
    return button
  }
  @MainActor private func login(_ username: String, requireProjectCreation: Bool = true) {
    fill(app.textFields["task-hub-username"], username)
    tap(app.buttons["task-hub-login"])
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["task-hub-login"])], timeout: 40), .completed)
    if requireProjectCreation {
      XCTAssertTrue(app.buttons["task-hub-project-create"].waitForExistence(timeout: 30))
    } else {
      XCTAssertTrue(app.navigationBars["项目与任务"].waitForExistence(timeout: 30), "The real reviewer must reach its authorized Project workspace")
      XCTAssertFalse(app.buttons["task-hub-project-create"].exists, "The reviewer is not an administrator and must not gain admin-only project creation")
    }
  }
  @MainActor private func createProjectAndTask(reviewerName: String) {
    tap(app.buttons["task-hub-project-create"])
    fill(app.textFields["task-hub-title"], projectName)
    fill(app.textFields["task-hub-project-key"], "W" + String(runID.replacingOccurrences(of: "-", with: "").prefix(12)).uppercased())
    fill(app.textViews["task-hub-description"], "独立真实网络异常验收。只有原主服务执行业务；结束等待不等于取消，任务有意保留未完成。")
    submitConfirmedForm(); openProject()
    tap(app.buttons["task-hub-create"])
    fill(app.textFields["task-hub-title"], taskTitle)
    fill(app.textViews["task-hub-description"], "真实人类负责人及独立人工验收人。两笔进度分别验证丢失下行响应与本机结束等待。")
    fill(app.textViews["task-hub-acceptance-criteria"], "同一原操作查询确认；无自动重放；后端每笔只一条进度；不可变提交与新草稿分别保留。此故障任务未交付或最终关闭。")
    tap(app.descendants(matching: .any).matching(identifier: "task-hub-reviewer").firstMatch)
    tap(named(reviewerName))
    submitConfirmedForm()
    XCTAssertTrue(app.staticTexts["task-hub-detail-title"].waitForExistence(timeout: 30))
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
  }
  @MainActor private func respondAndStart() {
    let predicate = NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "task-hub-action-response-", "提交本人响应")
    for _ in 0..<20 {
      let response = app.descendants(matching: .any).matching(predicate).firstMatch
      for _ in 0..<12 where !response.exists { if !scrollContent(up: true) { break } }
      if !response.exists { break }
      tap(response); fill(app.textViews["task-hub-body"], "接受并处理本次独立网络验收要求；只记录两笔真实进度，不声明任务完成。")
      submitConfirmedForm()
    }
    XCTAssertFalse(app.descendants(matching: .any).matching(predicate).firstMatch.exists)
    // Looking for remaining feedback scrolls the lazy List past its stage row.
    // Reveal the real row before starting; keep the original business deadline.
    let stage = app.staticTexts["task-hub-workflow-stage"]
    reveal(stage)
    XCTAssertTrue(stage.exists)
    shot("before-start-with-real-workflow-stage-visible")
    XCTAssertTrue(app.buttons["task-hub-action-start"].isEnabled)
    tap(app.buttons["task-hub-action-start"])
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "开发中"), object: stage)], timeout: 40), .completed)
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
    if app.staticTexts["task-hub-detail-title"].exists && app.staticTexts["task-hub-detail-title"].label == taskTitle { return }
    openProject()
    let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "task-hub-task-", taskTitle)).firstMatch
    XCTAssertTrue(row.waitForExistence(timeout: 20))
    let title = row.staticTexts[taskTitle].firstMatch
    reveal(title); XCTAssertTrue(row.frame.contains(title.frame))
    shot("before-reopen-exact-network-task-title")
    title.tap()
    XCTAssertTrue(app.staticTexts["task-hub-detail-title"].waitForExistence(timeout: 20))
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
  }
  @MainActor private func openProgress() {
    tap(app.buttons["task-hub-actions"]); tap(app.buttons["task-hub-action-progress"])
    XCTAssertTrue(app.buttons["task-hub-submit"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.textViews["task-hub-body"].waitForExistence(timeout: 10))
  }
  @MainActor private func adoptLatestRevisionIfNeeded() {
    let rebase = app.buttons["task-hub-rebase"]
    if rebase.exists { tap(rebase) }
  }
  @MainActor private func openReceipts() {
    guard let form = receiptFormContext() else { return }
    func visibleLink() -> XCUIElement? {
      guard receiptFormStillActive(form.bar), let snapshot = try? form.list.snapshot() else { return nil }
      let viewport = receiptFormViewport(form.list, bar: form.bar)
      var anchors: [any XCUIElementSnapshot] = []
      func visit(_ node: any XCUIElementSnapshot) {
        if (node.elementType == .button || node.elementType == .link), node.label == "查询原操作回执",
          !node.frame.isEmpty, viewport.contains(node.frame) { anchors.append(node) }
        for child in node.children { visit(child) }
      }
      visit(snapshot)
      guard anchors.count == 1 else { return nil }
      // The fresh, scoped snapshot establishes the actual action leaf before any
      // target frame/type read. A global typed Button query can match stale AX.
      let nodes = form.list.descendants(matching: .any)
        .matching(NSPredicate(format: "label == %@", "查询原操作回执")).allElementsBoundByIndex
        .filter { $0.elementType == anchors[0].elementType && $0.isHittable && viewport.contains($0.frame) }
      guard nodes.count == 1, abs(nodes[0].frame.minY - anchors[0].frame.minY) < 1,
        abs(nodes[0].frame.minX - anchors[0].frame.minX) < 1 else { return nil }
      print("Actual scoped receipt leaf: type=\(nodes[0].elementType), identifier=\(nodes[0].identifier), label=\(nodes[0].label), frame=\(nodes[0].frame), viewport=\(viewport)")
      return nodes[0]
    }
    var link = visibleLink()
    if link == nil {
      // Preserve reveal's original initial wait and gesture bounds. An absent
      // lazy row never supplies a speculative Button frame to the helper.
      let materialized = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in visibleLink() != nil }, object: app)
      _ = XCTWaiter.wait(for: [materialized], timeout: 20)
      link = visibleLink()
    }
    for up in [true, false] where link == nil {
      var stalled = 0
      for _ in 0..<12 where link == nil {
        guard receiptFormStillActive(form.bar) else { XCTFail("The exact progress sheet changed before receipt navigation"); return }
        if scrollReceiptForm(form.list, bar: form.bar, up: up) { stalled = 0 } else { stalled += 1 }
        link = visibleLink()
        if stalled >= 2 { break }
      }
    }
    guard let link else { shot("receipt-link-not-materialized-in-exact-progress-form"); XCTFail("The exact receipt navigation leaf must be revealed inside the current progress Form"); return }
    shot("before-tap-exact-uncovered-receipt-leaf")
    link.tap()
    XCTAssertTrue(app.navigationBars["原操作回执"].waitForExistence(timeout: 10))
  }

  @MainActor private func receiptFormStillActive(_ bar: XCUIElement) -> Bool {
    // The actual toolbar contains same-label Other wrappers around a Button leaf.
    // Repeated typed hittability queries can resolve an invalid AX activation point.
    // Establish the exact current sheet from a fresh leaf snapshot, not that query.
    guard bar.exists, bar.identifier == "记录进度",
      app.navigationBars.matching(identifier: "记录进度").count == 1 else { return false }
    return receiptCloseFrame(bar) != nil
  }

  @MainActor private func receiptCloseFrame(_ bar: XCUIElement) -> CGRect? {
    guard let snapshot = try? bar.snapshot(), snapshot.identifier == "记录进度" else { return nil }
    func usable(_ rect: CGRect) -> Bool {
      !rect.isNull && !rect.isEmpty && [rect.minX, rect.minY, rect.maxX, rect.maxY].allSatisfy { $0.isFinite }
    }
    guard usable(snapshot.frame), app.frame.contains(snapshot.frame), !app.alerts.firstMatch.exists else { return nil }
    var leaves: [any XCUIElementSnapshot] = []
    func hasNestedCloseButton(_ node: any XCUIElementSnapshot) -> Bool {
      node.children.contains { child in
        (child.elementType == .button && child.label == "关闭") || hasNestedCloseButton(child)
      }
    }
    func visit(_ node: any XCUIElementSnapshot) {
      if node.elementType == .button, node.label == "关闭", !hasNestedCloseButton(node) {
        leaves.append(node)
      }
      for child in node.children { visit(child) }
    }
    visit(snapshot)
    guard leaves.count == 1, leaves[0].isEnabled, usable(leaves[0].frame),
      snapshot.frame.contains(leaves[0].frame), app.frame.contains(leaves[0].frame) else { return nil }
    return leaves[0].frame
  }

  @MainActor private func receiptFormViewport(_ list: XCUIElement, bar: XCUIElement) -> CGRect {
    var rect = list.frame.intersection(app.frame)
    guard !rect.isNull, !rect.isEmpty else { return .zero }
    let top = max(rect.minY, bar.frame.maxY + 8)
    var bottom = rect.maxY - 8
    for blocker in app.keyboards.allElementsBoundByIndex + app.tabBars.allElementsBoundByIndex
      where blocker.exists && blocker.isHittable && blocker.frame.intersects(rect) {
      bottom = min(bottom, blocker.frame.minY - 8)
    }
    let keyboardToolbar = app.buttons["task-hub-keyboard-dismiss"]
    if keyboardToolbar.exists && keyboardToolbar.isHittable && keyboardToolbar.frame.intersects(rect) {
      bottom = min(bottom, keyboardToolbar.frame.minY - 8)
    }
    guard top.isFinite, bottom.isFinite, bottom > top else { return .zero }
    rect.origin.y = top; rect.size.height = bottom - top
    return rect
  }

  @MainActor private func receiptFormContext() -> (bar: XCUIElement, list: XCUIElement)? {
    let bars = app.navigationBars.matching(identifier: "记录进度")
    guard bars.count == 1 else { XCTFail("A single exact progress sheet must own receipt navigation"); return nil }
    let bar = bars.element(boundBy: 0)
    let key = "W" + runID.replacingOccurrences(of: "-", with: "").prefix(12).uppercased() + "-1"
    guard receiptFormStillActive(bar), app.navigationBars.matching(identifier: key).count == 1 else {
      XCTFail("Receipt navigation must remain on the exact run-scoped task and its topmost progress sheet"); return nil
    }
    var owners: [XCUIElement] = []
    for list in app.collectionViews.allElementsBoundByIndex where list.isHittable && list.frame.intersects(app.frame) {
      let viewport = receiptFormViewport(list, bar: bar)
      guard let snapshot = try? list.snapshot() else { XCTFail("The progress Form must provide a fresh ownership snapshot"); return nil }
      var ownedAnchor = false
      func visit(_ node: any XCUIElementSnapshot) {
        if !node.frame.isEmpty && viewport.contains(CGPoint(x: node.frame.midX, y: node.frame.midY)) {
          if node.elementType == .textView && node.identifier == "task-hub-body",
            let body = node.value as? String, body.contains(runID) { ownedAnchor = true }
          if node.elementType == .button && ["task-hub-save-draft", "task-hub-end-waiting"].contains(node.identifier) { ownedAnchor = true }
        }
        for child in node.children { visit(child) }
      }
      visit(snapshot)
      if ownedAnchor { owners.append(list) }
    }
    guard owners.count == 1 else { XCTFail("One actual progress Form List must own the exact receipt gesture; background Lists cannot substitute"); return nil }
    print("Actual receipt Form owner: taskKey=\(key), bar=\(bar.frame), list=\(owners[0].frame)")
    return (bar, owners[0])
  }

  @MainActor private func scrollReceiptForm(_ list: XCUIElement, bar: XCUIElement, up: Bool) -> Bool {
    let viewport = receiptFormViewport(list, bar: bar)
    guard viewport.width > 80, viewport.height > 120 else { XCTFail("The exact progress Form must expose uncovered drag space"); return false }
    let before = visibleCellPositions(list, in: viewport)
    let oldValues = scrollIndicatorValues(list)
    let origin = app.coordinate(withNormalizedOffset: .zero)
    let x = viewport.maxX - min(35, viewport.width * 0.12) - app.frame.minX
    let top = viewport.minY + viewport.height * 0.18 - app.frame.minY
    let bottom = viewport.maxY - viewport.height * 0.18 - app.frame.minY
    origin.withOffset(CGVector(dx: x, dy: up ? bottom : top)).press(forDuration: 0.05,
      thenDragTo: origin.withOffset(CGVector(dx: x, dy: up ? top : bottom)))
    let after = visibleCellPositions(list, in: receiptFormViewport(list, bar: bar))
    let moved = before.contains { key, positions in
      guard let actual = after[key] else { return false }
      return zip(positions, actual).contains { abs($0.0 - $0.1) > 1 }
    }
    let newValues = scrollIndicatorValues(list)
    let indicatorMoved = !oldValues.isEmpty && oldValues.count == newValues.count && oldValues != newValues
    print("Actual receipt Form displacement: direction=\(up ? "up" : "down"), viewport=\(viewport), commonCellMoved=\(moved), indicatorMoved=\(indicatorMoved)")
    return moved || indicatorMoved
  }
  @MainActor private func assertOriginalReceipt(_ id: String, state: String) {
    let button = app.buttons["task-hub-receipt-" + id]
    reveal(button); XCTAssertTrue(button.exists)
    revealReceiptMetadata(id, state: state)
  }
  @MainActor private func revealReceiptMetadata(_ id: String, state: String, retainedList: XCUIElement? = nil) {
    XCTAssertTrue(app.navigationBars["原操作回执"].exists)
    let uuid = app.staticTexts[id].firstMatch
    // Revealing the query row can move the preceding ID/state above the bar.
    // Keep the exact wire ID assertion, and expose its real metadata first.
    let query = app.buttons["task-hub-receipt-" + id]
    let lists = (retainedList.map { [$0] } ?? app.collectionViews.allElementsBoundByIndex).filter { list in
      guard list.isHittable, let snapshot = try? list.snapshot() else { return false }
      if retainedList != nil { return true } // Already bound to this exact ID before querying.
      func owns(_ node: any XCUIElementSnapshot) -> Bool {
        (node.elementType == .button && node.identifier == "task-hub-receipt-" + id)
          || (node.elementType == .staticText && node.label == id)
          || node.children.contains { owns($0) }
      }
      return owns(snapshot)
    }
    guard lists.count == 1 else { XCTFail("The exact original receipt metadata must belong to one actual current Receipt List"); return }
    let list = lists[0]
    for _ in 0..<12 {
      if uuid.exists && uncoveredListRect(list).contains(uuid.frame) { break }
      if !scrollContent(up: false, within: list) { break }
    }
    XCTAssertTrue(uuid.exists, "The UI must show the exact ID observed on the real wire")
    XCTAssertEqual(uuid.label, id)
    XCTAssertTrue(uncoveredListRect(list).contains(uuid.frame), "The full original ID must be visible below the receipt bar")
    let phase = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard let snapshot = try? list.snapshot() else { return false }
      let viewport = self.uncoveredListRect(list)
      var values: [any XCUIElementSnapshot] = []
      func visit(_ node: any XCUIElementSnapshot) {
        if node.elementType == .staticText, node.label == state, !node.frame.isEmpty, viewport.contains(node.frame),
          node.frame.maxY <= uuid.frame.minY, uuid.frame.minY - node.frame.maxY < 120 { values.append(node) }
        for child in node.children { visit(child) }
      }
      visit(snapshot)
      return values.count == 1
    }, object: list)
    XCTAssertEqual(XCTWaiter.wait(for: [phase], timeout: 45), .completed,
      "The exact original receipt must expose its own expected state immediately above its visible ID")
    print("Actual original receipt metadata: id=\(id), frame=\(uuid.frame), state=\(state), queryPresent=\(query.exists)")
    shot("exact-original-receipt-id-and-state-visible")
  }
  @MainActor private func showImmutableBody(_ body: String) {
    tap(app.buttons["不可变提交内容"].firstMatch)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", body)).firstMatch.waitForExistence(timeout: 10))
  }
  @MainActor private func queryOriginalReceipt(_ id: String) {
    let button = app.buttons["task-hub-receipt-" + id]
    reveal(button)
    let originalList = listContaining(button)
    XCTAssertNotNil(originalList)
    button.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: button)], timeout: 30), .completed)
    revealReceiptMetadata(id, state: "已确认", retainedList: originalList)
    shot("confirmed-original-receipt-" + id)
  }
  @MainActor private func backFromReceipts() { tap(app.navigationBars["原操作回执"].buttons.firstMatch) }
  @MainActor private func closeProgress() {
    let bars = app.navigationBars.matching(identifier: "记录进度")
    XCTAssertEqual(bars.count, 1, "Only the exact current progress sheet may be closed")
    let bar = bars.element(boundBy: 0)
    let key = "W" + runID.replacingOccurrences(of: "-", with: "").prefix(12).uppercased() + "-1"
    guard receiptFormStillActive(bar), app.navigationBars.matching(identifier: key).count == 1,
      let frame = receiptCloseFrame(bar) else {
      shot("exact-progress-close-leaf-unavailable")
      XCTFail("The current run-scoped progress sheet must expose one enabled, fully visible Close Button leaf")
      return
    }
    print("Actual exact progress Close Button leaf: frame=\(frame), center=(\(frame.midX), \(frame.midY)), bar=\(bar.frame)")
    shot("before-coordinate-tap-exact-progress-close-leaf")
    app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
      dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY)).tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["task-hub-submit"])], timeout: 10), .completed)
    XCTAssertFalse(bar.exists)
    XCTAssertEqual(app.staticTexts["task-hub-detail-title"].label, taskTitle)
  }
  @MainActor private func chooseTab(_ title: String) { tap(app.segmentedControls["task-hub-detail-tabs"].buttons[title]) }
  @MainActor private func submitWithoutWaiting() {
    hideKeyboard(); XCTAssertTrue(app.buttons["task-hub-submit"].isEnabled); tap(app.buttons["task-hub-submit"])
  }
  @MainActor private func submitConfirmedForm() {
    submitWithoutWaiting()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["task-hub-submit"])], timeout: 40), .completed)
  }
  @MainActor private func fill(_ element: XCUIElement, _ text: String) {
    reveal(element); element.tap()
    if let current = element.value as? String, !current.isEmpty { element.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count)) }
    element.typeText(text); hideKeyboard()
  }
  @MainActor private func hideKeyboard() {
    let dismiss = app.buttons["task-hub-keyboard-dismiss"]
    if dismiss.exists && dismiss.isHittable { dismiss.tap() }
    else if app.keyboards.buttons["Done"].exists { app.keyboards.buttons["Done"].tap() }
  }
  @MainActor private func setToggle(_ element: XCUIElement, on: Bool) {
    reveal(element)
    if (element.value as? String == "1") != on { element.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap() }
    XCTAssertEqual(element.value as? String, on ? "1" : "0", "Toggle state must actually change")
  }
  @MainActor private func actualTaskHubPage() -> (kind: String, bar: XCUIElement)? {
    guard let snapshot = try? app.snapshot() else { return nil }
    var bars: [any XCUIElementSnapshot] = []
    var pickerVisible = false
    func visit(_ node: any XCUIElementSnapshot) {
      if node.elementType == .navigationBar, !node.frame.isEmpty, node.frame.intersects(snapshot.frame) { bars.append(node) }
      if node.identifier == "task-hub-detail-tabs", !node.frame.isEmpty, node.frame.intersects(snapshot.frame) { pickerVisible = true }
      for child in node.children { visit(child) }
    }
    visit(snapshot)
    func child(_ node: any XCUIElementSnapshot, id: String) -> Bool {
      node.identifier == id || node.children.contains { child($0, id: id) }
    }
    // Read one real AX snapshot before issuing positive action queries. Missing
    // Button .isHittable probes previously consumed the unchanged 20s back scope.
    for node in bars where node.identifier == "主服务与账户" {
      let bar = app.navigationBars.matching(identifier: node.identifier).firstMatch
      if bar.buttons["完成"].isHittable { return ("service", bar) }
    }
    for node in bars where node.identifier == "项目与任务" && child(node, id: "task-hub-service") && child(node, id: "task-hub-dismiss") {
      let bar = app.navigationBars.matching(identifier: node.identifier).firstMatch
      if bar.buttons["task-hub-service"].isHittable && bar.buttons["task-hub-dismiss"].isHittable { return ("root", bar) }
    }
    let projects = bars.filter { child($0, id: "task-hub-create") && child($0, id: "task-hub-project-settings") }
    if projects.count == 1 {
      let bar = app.navigationBars.matching(identifier: projects[0].identifier).firstMatch
      if bar.buttons["task-hub-create"].isHittable && bar.buttons["task-hub-project-settings"].isHittable { return ("project", bar) }
    }
    if pickerVisible {
      let details = bars.filter { child($0, id: "BackButton") && !child($0, id: "task-hub-create") && $0.identifier != "主服务与账户" }
      if details.count == 1 {
        let bar = app.navigationBars.matching(identifier: details[0].identifier).firstMatch
        if bar.buttons["BackButton"].isHittable { return ("detail", bar) }
      }
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
    let page = actualTaskHubPage()
    if page?.kind == "service" {
      let serviceLists = candidates.filter {
        $0.staticTexts["服务身份"].exists || $0.staticTexts["连接与账户"].exists ||
        $0.buttons["task-hub-reconnect"].exists || $0.buttons["task-hub-logout"].exists
      }
      return serviceLists.count == 1 ? serviceLists[0] : nil
    }
    if let page, page.kind == "detail" {
      // After the final response, its action disappears and the stage row can be
      // lazy/offscreen. The actual visible feedback section still owns this List.
      let visibleRequirements = candidates.filter { visibleRequirementSection(in: $0, detailBar: page.bar) }
      if !visibleRequirements.isEmpty {
        return visibleRequirements.count == 1 ? visibleRequirements[0] : nil
      }
      let detailLists = candidates.filter {
        $0.staticTexts["task-hub-workflow-stage"].exists ||
        $0.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ OR identifier BEGINSWITH %@ OR identifier BEGINSWITH %@ OR identifier BEGINSWITH %@", "task-hub-round-", "task-hub-action-response-", "task-hub-action-pass-", "task-hub-action-fail-")).count > 0
      }
      if detailLists.count == 1 { return detailLists[0] }
    }
    return candidates.count == 1 ? candidates[0] : nil
  }

  @MainActor private func visibleRequirementSection(in list: XCUIElement, detailBar: XCUIElement) -> Bool {
    var viewport = list.frame.intersection(app.frame)
    guard !viewport.isNull, !viewport.isEmpty else { return false }
    var top = max(viewport.minY, detailBar.frame.maxY + 8)
    var bottom = viewport.maxY - 8
    for picker in [app.segmentedControls["task-hub-detail-tabs"], app.buttons["task-hub-detail-tabs"]] {
      if picker.exists && picker.isHittable && picker.frame.intersects(viewport) {
        top = max(top, picker.frame.maxY + 8)
      }
    }
    for id in ["task-hub-actions", "task-hub-action-start", "task-hub-enter-current-round"] {
      let footer = app.buttons[id]
      if footer.exists && footer.isHittable && footer.frame.intersects(viewport) {
        bottom = min(bottom, footer.frame.minY - 8)
      }
    }
    for blocker in app.keyboards.allElementsBoundByIndex + app.tabBars.allElementsBoundByIndex
      where blocker.exists && blocker.isHittable && blocker.frame.intersects(viewport) {
      bottom = min(bottom, blocker.frame.minY - 8)
    }
    guard top.isFinite, bottom.isFinite, bottom > top else { return false }
    viewport.origin.y = top
    viewport.size.height = bottom - top
    do {
      let snapshot = try list.snapshot()
      var anchors = 0
      func visit(_ node: XCUIElementSnapshot) {
        if node.elementType == .staticText && node.label == "本轮反馈与负责人响应"
          && !node.frame.isEmpty && viewport.contains(CGPoint(x: node.frame.midX, y: node.frame.midY)) {
          anchors += 1
        }
        for child in node.children { visit(child) }
      }
      visit(snapshot)
      if anchors == 1 {
        print("Actual visible requirements List anchor: frame=\(list.frame), viewport=\(viewport), headerMatches=\(anchors)")
      }
      return anchors == 1
    } catch {
      XCTFail("The actual requirements List must provide a fresh section-anchor snapshot")
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
    let shown = revealActualTarget(element, initialWait: 20);
    if (!shown) { navigationDiagnostic("exact-target-not-uncovered") }
    XCTAssertTrue(element.exists)
    XCTAssertTrue(element.isHittable)
    XCTAssertTrue(shown, "The exact native target must be inside its uncovered viewport after actual List displacement")
  }

  @MainActor private func named(_ title: String) -> XCUIElement {
    let button = app.buttons[title].firstMatch
    if button.exists { return button }
    let text = app.staticTexts[title].firstMatch
    // Lazy rows have no AX node yet. Keep the intended button query for reveal's scroll;
    // only return a StaticText when that node already exists (not a missing stand-in).
    return text.exists ? text : button
  }
  @MainActor private func shot(_ name: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
  }
}

private enum NetworkObservationError: Error { case invalidResponse, actualCommitNotObserved, localWaitDidNotClose, onlineRecoveryNotObserved, invalidReporterResponse, externalAutomaticRefreshNotObserved }

/// The observation channel trusts only the same registered certificate and hostname.
private final class NetworkObservationTrust: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
  let host: String
  let port: Int
  let pin: String
  init(host: String, port: Int, pin: String) { self.host = host; self.port = port; self.pin = pin.lowercased() }
  func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
      challenge.protectionSpace.host == host, challenge.protectionSpace.port == port,
      let trust = challenge.protectionSpace.serverTrust,
      let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let certificate = chain.first
    else { completionHandler(.cancelAuthenticationChallenge, nil); return }
    let actual = SHA256.hash(data: SecCertificateCopyData(certificate) as Data).map { String(format: "%02x", $0) }.joined()
    guard actual == pin else { completionHandler(.cancelAuthenticationChallenge, nil); return }
    SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString))
    SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
    SecTrustSetAnchorCertificatesOnly(trust, true)
    guard SecTrustEvaluateWithError(trust, nil) else { completionHandler(.cancelAuthenticationChallenge, nil); return }
    completionHandler(.useCredential, URLCredential(trust: trust))
  }
  func urlSession(_ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}
