import XCTest
import CryptoKit
import UIKit
import Vision

#if targetEnvironment(simulator)
/// Drives the actual history card in a fresh owned simulator. The app's DEBUG
/// demo fixture supplies the long text; no device credentials or desktop writes.
final class EasyWorkSubagentReadingUITests: XCTestCase {
  @MainActor func testLongChildDetailExpandsAndCollapsesWithoutLosingContent() {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = ["-ui-testing", "-subagent-reading-fixture"]
    app.launch()
    defer { app.terminate() }
    let history = app.scrollViews["message-list"]
    let disclosure = app.buttons["subagent-expand-c2-fixture-child"]
    XCTAssertTrue(history.waitForExistence(timeout: 30))
    XCTAssertTrue(disclosure.waitForExistence(timeout: 20))
    let full = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "C2_DETAIL_HEAD")).firstMatch
    XCTAssertFalse(full.exists)
    XCTAssertTrue(["展开", "Expand"].contains(disclosure.label))
    func evidence(_ name: String) {
      let image = XCTAttachment(screenshot: app.screenshot())
      image.name = name; image.lifetime = .keepAlways; add(image)
    }
    evidence("subagent-collapsed")
    disclosure.tap()
    XCTAssertTrue(full.waitForExistence(timeout: 15))
    let original = full.label
    XCTAssertTrue(original.utf8.count > 65_536)
    XCTAssertTrue(original.contains("C2_DETAIL_MIDDLE"))
    XCTAssertTrue(original.hasSuffix("C2_DETAIL_TAIL"))
    XCTAssertTrue(["收起", "Collapse"].contains(disclosure.label))
    evidence("subagent-expanded")
    for _ in 0..<80 where !disclosure.isHittable { history.swipeUp(velocity: .fast) }
    XCTAssertTrue(disclosure.isHittable, "The original detail remains scrollable to the disclosure at its end")
    evidence("subagent-detail-end")
    disclosure.tap()
    XCTAssertFalse(full.exists)
    XCTAssertTrue(["展开", "Expand"].contains(disclosure.label))
  }
}
#endif

/// Minimal real UIAutomation bootstrap. Does not launch, tap or mutate the App.
final class EasyWorkIndependentRunnerBootstrapUITests: XCTestCase {
  @MainActor func testReadOnlyExactAcceptanceAppState() throws {
    guard let value = ProcessInfo.processInfo.environment["EASYWORK_UI_BOOTSTRAP_CASE"],
      UUID(uuidString: value) != nil else { throw XCTSkip("Frozen independent bootstrap case required") }
    let app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.easyworkacceptance")
    let state = app.state
    XCTAssertNotEqual(state, .unknown)
    let evidence = XCTAttachment(string: "case=\(value);bundle=com.easyai.agenthub.easyworkacceptance;state=\(state.rawValue);actions=0")
    evidence.name = "independent-app-ui-automation-state"
    evidence.lifetime = .keepAlways
    add(evidence)
  }
}

/// Grants only the independent App's real OS permission through its normal
/// notification setting. No synthetic event, pairing or model submission.
final class EasyWorkIndependentNotificationAuthorizationUITests: XCTestCase {
  @MainActor func testGrantIndependentSandboxNotificationPermission() throws {
    continueAfterFailure = false
    let env = ProcessInfo.processInfo.environment
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
      let run = env["EASYWORK_SANDBOX_AUTHORIZATION_CASE"],
      UUID(uuidString: run)?.uuidString.lowercased() == run,
      env["EASYWORK_SANDBOX_AUTHORIZATION_BUNDLE"] == "com.easyai.agenthub.easyworkacceptance" else {
      throw XCTSkip("Frozen independent physical notification permission case required")
    }
    let app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.easyworkacceptance")
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchEnvironment = ["EASYWORK_NOTIFICATION_OBSERVATION_RUN": run]
    app.launch()
    XCTAssertEqual(app.state, .runningForeground)
    let sidebar = app.buttons["open-sidebar"]
    let settings = app.buttons["open-settings"]
    if !settings.isHittable {
      XCTAssertTrue(sidebar.waitForExistence(timeout: 30)); sidebar.tap()
    }
    XCTAssertTrue(settings.waitForExistence(timeout: 10)); settings.tap()
    let notifications = app.descendants(matching: .any)["notification-settings"].firstMatch
    XCTAssertTrue(notifications.waitForExistence(timeout: 10)); notifications.tap()
    let toggle = app.switches["notifications-enabled"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 10))
    XCTAssertEqual(toggle.value as? String, "0", "The frozen prior native result had no authorization; do not toggle an already enabled subscription off")
    toggle.tap()
    let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let alert = system.alerts.firstMatch
    XCTAssertTrue(alert.waitForExistence(timeout: 15), "Observe the actual system permission prompt")
    let before = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    before.name = "independent-sandbox-system-permission-" + run; before.lifetime = .keepAlways; add(before)
    let allow = alert.buttons.matching(NSPredicate(format: "label == 'Allow' OR label == '允许'")).firstMatch
    XCTAssertTrue(allow.exists); allow.tap()
    let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: toggle)
    XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 15), .completed)
    let permission = app.staticTexts["notification-permission"]
    XCTAssertTrue(permission.exists)
    let authorized = XCTNSPredicateExpectation(predicate: NSPredicate(
      format: "label CONTAINS %@ OR label CONTAINS %@", "系统通知已允许", "System notifications allowed"), object: permission)
    XCTAssertEqual(XCTWaiter.wait(for: [authorized], timeout: 10), .completed)
    let after = XCTAttachment(screenshot: app.screenshot())
    after.name = "independent-sandbox-permission-granted-" + run; after.lifetime = .keepAlways; add(after)
    let done = app.buttons["settings-done"]
    if done.isHittable { done.tap() }
  }
}

/// A separate class avoids the general suite's launch/setup behavior. Read only
/// the already running, independently identified EasyWork acceptance App.
final class EasyWorkPhysicalSystemReadinessUITests: XCTestCase {
  @MainActor func testExistingOwnAppAccessibilityWithoutLaunchingOrInput() throws {
    continueAfterFailure=false
    let env=ProcessInfo.processInfo.environment
    guard Bundle.main.bundleIdentifier=="com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
      let run=env["EASYWORK_SYSTEM_READINESS_CASE"],UUID(uuidString:run) != nil,
      env["EASYWORK_SYSTEM_READINESS_BUNDLE"]=="com.easyai.agenthub.easyworkacceptance" else {throw XCTSkip("Explicit own physical system-readiness case required")}
    let own=XCUIApplication(bundleIdentifier:"com.easyai.agenthub.easyworkacceptance")
    guard own.state == .runningForeground else {throw XCTSkip("Exact own App is not foreground; no App launched by this preflight")}
    // Querying a real accessibility snapshot exercises the system automation
    // authorization. A process-state read alone would not prove that access.
    let snapshot=own.debugDescription
    XCTAssertFalse(snapshot.isEmpty)
    let attachment=XCTAttachment(string:snapshot);attachment.name="own-app-system-accessibility-readiness-"+run;attachment.lifetime = .keepAlways;add(attachment)
  }
}

func observeEasyWorkExaminer(_ testName: String) {
  guard testName.contains("testEasyWorkRealDEVDeviceGrant"),
    let run = ProcessInfo.processInfo.environment["EASYWORK_EXAMINER_FILE_BRIDGE_RUN"],
    Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
    run.range(of: #"^[a-zA-Z0-9_-]{1,80}$"#, options: .regularExpression) != nil else { return }
  EasyWorkExaminerObservation.shared.start(run: run)
}

private final class EasyWorkExaminerObservation: NSObject, XCTestObservation {
  static let shared = EasyWorkExaminerObservation()
  private let lock = NSLock()
  private var directory: URL?
  private var events: [[String: Any]] = []
  func start(run: String) {
    directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-acceptance").appendingPathComponent(run)
    XCTestObservationCenter.shared.addTestObserver(self)
    record(["kind": "observer-start", "run": run])
  }
  private func record(_ event: [String: Any]) {
    lock.lock(); defer { lock.unlock() }
    guard let directory else { return }
    events.append(event.merging(["at": Date().timeIntervalSince1970]) { original, _ in original })
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      try JSONSerialization.data(withJSONObject: events, options: [.sortedKeys]).write(
        to: directory.appendingPathComponent("native-xctest-events.json"),
        options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    } catch { print("EASYWORK_EXAMINER_OBSERVATION_WRITE_FAILED", error) }
  }
  func testCase(_ testCase: XCTestCase, didRecord issue: XCTIssue) {
    record(["kind": "issue", "test": testCase.name, "type": issue.type.rawValue,
      "description": issue.compactDescription, "detail": issue.detailedDescription ?? ""])
  }
  func testCase(_ testCase: XCTestCase, didFailWithDescription description: String, inFile filePath: String?, atLine lineNumber: Int) {
    record(["kind": "failure", "test": testCase.name, "description": description,
      "file": filePath ?? "", "line": lineNumber])
  }
  func testCaseDidFinish(_ testCase: XCTestCase) {
    record(["kind": "case-finish", "test": testCase.name,
      "passed": testCase.testRun?.hasSucceeded == true, "failures": testCase.testRun?.failureCount ?? -1])
  }
}

func easyWorkExaminerText(_ text: String, name: String, testCase: XCTestCase) {
  guard let run = ProcessInfo.processInfo.environment["EASYWORK_EXAMINER_FILE_BRIDGE_RUN"] else {
    let attachment = XCTAttachment(string: text)
    attachment.name = name; attachment.lifetime = .keepAlways; testCase.add(attachment)
    return
  }
  guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
    run == ProcessInfo.processInfo.environment["EASYWORK_NATIVE_ARTIFACT_RUN"],
    run.range(of: #"^[a-zA-Z0-9_-]{1,80}$"#, options: .regularExpression) != nil,
    name.range(of: #"^[a-zA-Z0-9_-]{1,160}$"#, options: .regularExpression) != nil else {
    XCTFail("Invalid independent examiner artifact scope"); return
  }
  do {
    let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-acceptance").appendingPathComponent(run)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try Data(text.utf8).write(to: directory.appendingPathComponent(name + ".txt"),
      options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  } catch { XCTFail("Could not preserve independent examiner text: \(error)") }
}

// The physical Runner can execute XCUIAutomation while iOS denies its own
// networking. Only the private examiner uses paired-device file transport;
// the product App still uses its real authenticated LAN/Relay connection.
func easyWorkExaminerData(for request: URLRequest) async throws -> (Data, Int) {
  if let run = ProcessInfo.processInfo.environment["EASYWORK_EXAMINER_FILE_BRIDGE_RUN"] {
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
      ProcessInfo.processInfo.environment["EASYWORK_GRANT_EXAMINER_CONFIG_JSON"] != nil,
      run.range(of: #"^[a-zA-Z0-9_-]{1,80}$"#, options: .regularExpression) != nil else {
      throw NSError(domain: "EasyWorkExaminer", code: 1)
    }
    return try await EasyWorkExaminerFileBridge.shared.perform(request, run: run)
  }
  let (data, response) = try await URLSession.shared.data(for: request)
  guard let status = (response as? HTTPURLResponse)?.statusCode else {
    throw NSError(domain: "EasyWorkExaminer", code: 2)
  }
  return (data, status)
}

private actor EasyWorkExaminerFileBridge {
  static let shared = EasyWorkExaminerFileBridge()
  private var busy = false
  private var initializedRuns: Set<String> = []
  func perform(_ request: URLRequest, run: String) async throws -> (Data, Int) {
    let deadline = Date().addingTimeInterval(120)
    while busy {
      guard Date() < deadline else { throw NSError(domain: "EasyWorkExaminer", code: 3) }
      try await Task.sleep(for: .milliseconds(200))
    }
    busy = true; defer { busy = false }
    guard let url = request.url, ["/evidence", "/control", "/checkpoint"].contains(url.path),
      ["GET", "POST"].contains(request.httpMethod ?? "GET") else {
      throw NSError(domain: "EasyWorkExaminer", code: 4)
    }
    guard let nonce = ProcessInfo.processInfo.environment["EASYWORK_EXAMINER_SESSION_NONCE"],
      UUID(uuidString: nonce)?.uuidString == nonce else {
      throw NSError(domain: "EasyWorkExaminer", code: 7)
    }
    let payload = try JSONSerialization.data(withJSONObject: ["url": url.absoluteString,
      "method": request.httpMethod ?? "GET", "headers": request.allHTTPHeaderFields ?? [:],
      "bodyBase64": (request.httpBody ?? Data()).base64EncodedString(), "sessionNonce": nonce], options: [.sortedKeys])
    let id = UUID().uuidString
    let digest = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
    let envelope = try JSONSerialization.data(withJSONObject: ["version": 1, "requestId": id,
      "sha256": digest, "payloadBase64": payload.base64EncodedString()], options: [.sortedKeys])
    let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-examiner").appendingPathComponent(run)
    if !initializedRuns.contains(run) {
      guard !FileManager.default.fileExists(atPath: directory.path) else {
        throw NSError(domain: "EasyWorkExaminer", code: 8,
          userInfo: [NSLocalizedDescriptionKey: "Original examiner run already exists; keep its request and use a fresh run ID"])
      }
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      initializedRuns.insert(run)
    }
    let input = directory.appendingPathComponent("request.json")
    let output = directory.appendingPathComponent("response-" + id + ".json")
    let ready = directory.appendingPathComponent("ready-" + id + ".json")
    try envelope.write(to: input, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    while Date() < deadline {
      try Task.checkCancellation()
      // CoreDevice copying is not an atomic rename. The sender publishes this
      // marker only after the complete response copy has returned successfully.
      if let markerData = try? Data(contentsOf: ready), markerData.count <= 4096,
        let marker = (try? JSONSerialization.jsonObject(with: markerData)) as? [String: Any] {
        guard marker["version"] as? Int == 1, marker["requestId"] as? String == id,
          marker["requestSha256"] as? String == digest,
          let length = marker["responseBytes"] as? Int, length > 0, length <= 32 * 1024 * 1024,
          let expectedHash = marker["responseSha256"] as? String else {
          throw NSError(domain: "EasyWorkExaminer", code: 7)
        }
        let encoded = try Data(contentsOf: output)
        guard encoded.count == length,
          SHA256.hash(data: encoded).map({ String(format: "%02x", $0) }).joined() == expectedHash,
          let response = try JSONSerialization.jsonObject(with: encoded) as? [String: Any],
          response["requestId"] as? String == id, response["sha256"] as? String == digest,
          let status = response["status"] as? Int,
          let body = response["bodyBase64"] as? String, let data = Data(base64Encoded: body) else {
          throw NSError(domain: "EasyWorkExaminer", code: 5)
        }
        if (try? Data(contentsOf: input)) == envelope { try FileManager.default.removeItem(at: input) }
        // Retain both response and marker through the run so CoreDevice can
        // finish its metadata read; each immutable request has its own files.
        return (data, status)
      }
      try await Task.sleep(for: .milliseconds(200))
    }
    // An unknown examiner control result stays sealed under this ID; no replay.
    throw NSError(domain: "EasyWorkExaminer", code: 6,
      userInfo: [NSLocalizedDescriptionKey: "Original examiner request remains unresolved: " + id])
  }
}

extension CodexManageUITests {
  @MainActor func runEasyWorkForkOptionsInspection(app:XCUIApplication,composer:XCUIElement,config:[String:Any],
    evidence:(String) async throws->[String:Any],shot:(String)->Void,openOptions:(String)->Void) async throws {
    guard config["newModelInputsAuthorized"] as? Int==0,config["newForkWritesAuthorized"] as? Int==0,
      config["newProjectWritesAuthorized"] as? Int==0,config["expectedWorktreeEnabled"] as? Bool==false else {
      throw NSError(domain:"EasyWorkForkOptionsInspection",code:1)
    }
    let source=try XCTUnwrap(config["sourceThreadId"] as? String),reply=try XCTUnwrap(config["sourceReplyUIId"] as? String)
    let title=try XCTUnwrap(config["sourceSearchTitle"] as? String),draft=try XCTUnwrap(config["originalSourceDraft"] as? String)
    let before=try await evidence(source)
    let oldIds=Set((before["requests"] as? [[String:Any]] ?? []).compactMap { ($0["request"] as? [String:Any])?["requestId"] as? String })
    openEasyWorkSidebar(app:app)
    let search=app.textFields["sidebar-search"]
    if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
    XCTAssertTrue(search.waitForExistence(timeout:10));try replaceEasyWorkManagementText(app:app,field:search,with:title)
    var row=easyWorkVisibleSidebarRow(app:app,thread:source)
    for _ in 0..<8 where row==nil { app.scrollViews["sidebar-list"].swipeUp();row=easyWorkVisibleSidebarRow(app:app,thread:source) }
    try XCTUnwrap(row).tap();waitForEasyWorkDrawerClosed(app:app)
    func waitForOriginalDraft() {
      let exact=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in composer.value as? String==draft },object:composer)
      XCTAssertEqual(XCTWaiter.wait(for:[exact],timeout:30),.completed)
    }
    waitForOriginalDraft()
    XCTAssertEqual((app.staticTexts["agent-dev-target"].value as? String)?.components(separatedBy:"|").dropFirst().first,source)
    openOptions(reply)
    let standard=app.buttons["fork-same-directory"],worktree=app.buttons["fork-worktree"]
    let ready=XCTNSPredicateExpectation(predicate:NSPredicate(format:"exists == true AND enabled == true"),object:standard)
    XCTAssertEqual(XCTWaiter.wait(for:[ready],timeout:30),.completed)
    XCTAssertTrue(worktree.exists);XCTAssertFalse(worktree.isEnabled)
    shot("native-ios-no-git-worktree-fork-disabled")
    // A normal process restart closes this inspection-only menu without tapping
    // any fork option. Restoration must retain the exact source and draft.
    app.terminate();app.launchArguments=["-ui-testing","-restore-testing","-easywork-local-dev"];app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout:30))
    waitForOriginalDraft()
    XCTAssertEqual((app.staticTexts["agent-dev-target"].value as? String)?.components(separatedBy:"|").dropFirst().first,source)
    XCTAssertFalse(app.buttons["fork-same-directory"].exists)
    let final=try await evidence(source)
    let requests=(final["requests"] as? [[String:Any]] ?? []).compactMap { $0["request"] as? [String:Any] }.filter { !oldIds.contains($0["requestId"] as? String ?? "") }
    XCTAssertFalse(requests.filter { $0["method"] as? String=="thread/fork-options" && ($0["params"] as? [String:Any])?["threadId"] as? String==source }.isEmpty)
    for method in ["thread/fork","turn/start","thread/create","project/create","thread/manage"] { XCTAssertTrue(requests.filter { $0["method"] as? String==method }.isEmpty) }
    shot("native-ios-no-git-menu-process-restore-original-draft")
    print("EASYWORK_FORK_NO_GIT_INSPECTION:worktree-disabled;ordinary-fork-enabled;menu-process-restart-source-draft-exact;zero-writes")
  }
  @MainActor func runEasyWorkComplexHistoryFork(app:XCUIApplication,composer:XCUIElement,config:[String:Any],
    evidence:(String) async throws->[String:Any],shot:(String)->Void,openFork:(String)->Void) async throws {
    let existing=config["existingComplexForkOperation"] as? [String:Any]
    let expectedWrites=existing==nil ? 1 : 0
    guard config["newModelInputsAuthorized"] as? Int==0,config["newForkWritesAuthorized"] as? Int==expectedWrites,
      config["newProjectWritesAuthorized"] as? Int==0 else { throw NSError(domain:"EasyWorkComplexFork",code:1) }
    let source=try XCTUnwrap(config["sourceThreadId"] as? String)
    let selectedRun=try XCTUnwrap(config["selectedRunId"] as? String)
    let searchTitle=try XCTUnwrap(config["sourceSearchTitle"] as? String)
    let reply=try XCTUnwrap(config["sourceReplyUIId"] as? String)
    guard app.responds(to:NSSelectorFromString("setCurrentInteractionOptions:")),app.responds(to:NSSelectorFromString("currentInteractionOptions")),
      let oldOptions=app.value(forKey:"currentInteractionOptions") else { throw NSError(domain:"EasyWorkComplexFork",code:2) }
    app.setValue(NSNumber(value:3),forKey:"currentInteractionOptions")
    defer { app.setValue(oldOptions,forKey:"currentInteractionOptions") }
    func currentThread()->String? { (app.staticTexts["agent-dev-target"].value as? String)?.components(separatedBy:"|").dropFirst().first }
    func selectSource() throws {
      openEasyWorkSidebar(app:app)
      let exposed=XCTNSPredicateExpectation(predicate:NSPredicate(format:"exists == true AND hittable == true"),object:app.buttons["close-sidebar"])
      guard XCTWaiter.wait(for:[exposed],timeout:15) == .completed else { throw NSError(domain:"EasyWorkComplexFork",code:3) }
      let search=app.textFields["sidebar-search"]
      if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
      XCTAssertTrue(search.waitForExistence(timeout:10));try replaceEasyWorkManagementText(app:app,field:search,with:searchTitle)
      var row=easyWorkVisibleSidebarRow(app:app,thread:source)
      for _ in 0..<8 where row==nil { app.scrollViews["sidebar-list"].swipeUp();row=easyWorkVisibleSidebarRow(app:app,thread:source) }
      let exact=try XCTUnwrap(row);exact.tap();waitForEasyWorkDrawerClosed(app:app)
      let selected=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in currentThread()==source },object:app)
      XCTAssertEqual(XCTWaiter.wait(for:[selected],timeout:30),.completed)
    }
    try selectSource()
    let originalDraft=try XCTUnwrap(composer.value as? String)
    let before=try await evidence(source)
    let oldIds=Set((before["requests"] as? [[String:Any]] ?? []).compactMap { ($0["request"] as? [String:Any])?["requestId"] as? String })
    func fresh(_ value:[String:Any],method:String)->[[String:Any]] {
      (value["requests"] as? [[String:Any]] ?? []).filter { row in
        guard let request=row["request"] as? [String:Any],let id=request["requestId"] as? String else { return false }
        return request["method"] as? String==method && !oldIds.contains(id)
      }
    }
    let snapshot=try XCTUnwrap(((before["independentDesktop"] as? [String:Any])?["snapshots"] as? [[String:Any]])?.first)
    XCTAssertEqual(snapshot["id"] as? String,source);XCTAssertEqual(snapshot["messageHeadersComplete"] as? Bool,true)
    let headers=try XCTUnwrap(snapshot["messageHeaders"] as? [[String:Any]])
    let through=try XCTUnwrap(headers.lastIndex { $0["sourceRunId"] as? String==selectedRun && $0["role"] as? String=="assistant" })
    let sourceHash=try XCTUnwrap(snapshot["messagesHash"] as? String)
    XCTAssertEqual(sourceHash,config["sourceMessagesHash"] as? String)
    var operation:[String:Any]?
    if let existing {
      guard existing["threadId"] as? String==source,existing["turnId"] as? String==selectedRun,
        existing["phase"] as? String=="confirmed",existing["environment"] as? String=="same-directory",
        let id=existing["clientOperationId"] as? String else { throw NSError(domain:"EasyWorkComplexFork",code:4) }
      let query=app.buttons["query-fork-"+id]
      XCTAssertTrue(query.waitForExistence(timeout:20))
      for _ in 0..<8 where !app.frame.contains(query.frame) { app.scrollViews["message-list"].swipeUp() }
      let ready=XCTNSPredicateExpectation(predicate:NSPredicate(format:"exists == true AND enabled == true AND hittable == true"),object:query)
      XCTAssertEqual(XCTWaiter.wait(for:[ready],timeout:15),.completed)
      query.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap();operation=existing
    } else { openFork(reply) } // Exactly one explicit metadata fork, never replay.
    for _ in 0..<(existing==nil ? 60 : 0) {
      let observed=try await evidence(source);let writes=fresh(observed,method:"thread/fork")
      XCTAssertLessThanOrEqual(writes.count,1)
      if let write=writes.first,let request=write["request"] as? [String:Any],let params=request["params"] as? [String:Any],
        let response=write["response"] as? [String:Any],let value=response["result"] as? [String:Any],value["phase"] as? String=="confirmed" {
        XCTAssertEqual(params["threadId"] as? String,source);XCTAssertEqual(params["turnId"] as? String,selectedRun)
        XCTAssertEqual(params["environment"] as? String,"same-directory");operation=params.merging(value) { _,new in new }
        print("EASYWORK_COMPLEX_FORK_ORIGINAL_OPERATION:"+String(decoding:try JSONSerialization.data(withJSONObject:try XCTUnwrap(operation),options:[.sortedKeys]),as:UTF8.self));break
      }
      try await Task.sleep(for:.seconds(1))
    }
    let confirmed=try XCTUnwrap(operation);let child=try XCTUnwrap(confirmed["forkedThreadId"] as? String)
    let opened=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in currentThread()==child },object:app)
    XCTAssertEqual(XCTWaiter.wait(for:[opened],timeout:60),.completed)
    let childDraft=try XCTUnwrap(composer.value as? String);XCTAssertTrue(childDraft.isEmpty||childDraft=="询问 EasyWork")
    let observed=try await evidence(child)
    let inherited=try XCTUnwrap(((observed["independentDesktop"] as? [String:Any])?["snapshots"] as? [[String:Any]])?.first)
    XCTAssertEqual(inherited["id"] as? String,child);XCTAssertEqual(inherited["messageHeadersComplete"] as? Bool,true)
    let childHeaders=try XCTUnwrap(inherited["messageHeaders"] as? [[String:Any]])
    XCTAssertEqual(childHeaders.count,through+1)
    XCTAssertEqual(childHeaders.compactMap { $0["contentHash"] as? String },headers.prefix(through+1).compactMap { $0["contentHash"] as? String })
    XCTAssertTrue(childHeaders.allSatisfy { $0["sourceRunId"]==nil })
    shot("native-ios-complex-history-inherited-without-model")
    var resourcePreviewVerified=false
    let resourceOnSource=config["verifyResourceOnOriginalSource"] as? Bool==true
    if resourceOnSource {
      XCTAssertNotNil(existing);XCTAssertEqual(expectedWrites,0)
      try selectSource();XCTAssertEqual(composer.value as? String,originalDraft)
    }
    if let filename=config["verifyInheritedResourceName"] as? String {
      guard existing != nil,expectedWrites==0,filename==(filename as NSString).lastPathComponent,
        let marker=config["verifyInheritedResourceText"] as? String,!marker.isEmpty else { throw NSError(domain:"EasyWorkComplexFork",code:5) }
      let download=app.buttons["download-"+filename].firstMatch
      XCTAssertTrue(download.waitForExistence(timeout:30),"The exact selected existing thread must expose its own resource")
      for _ in 0..<8 where !download.isHittable { app.scrollViews["message-list"].swipeUp() }
      XCTAssertTrue(download.isEnabled);XCTAssertTrue(download.isHittable);download.tap()
      let preview=app.buttons["preview-"+filename].firstMatch
      XCTAssertTrue(preview.waitForExistence(timeout:45));preview.tap()
      let done=app.buttons.matching(NSPredicate(format:"identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@",["关闭","Close","Done","完成"])).firstMatch
      XCTAssertTrue(done.waitForExistence(timeout:15))
      XCTAssertTrue(app.textViews.matching(NSPredicate(format:"label CONTAINS %@ OR value CONTAINS %@",marker,marker)).firstMatch.waitForExistence(timeout:15))
      shot("native-ios-original-complex-child-resource-quicklook")
      done.tap();XCTAssertTrue(preview.waitForExistence(timeout:10));resourcePreviewVerified=true
    }
    if !resourceOnSource { try selectSource() }
    XCTAssertEqual(composer.value as? String,originalDraft)
    let final=try await evidence(source)
    let finalSource=try XCTUnwrap(((final["independentDesktop"] as? [String:Any])?["snapshots"] as? [[String:Any]])?.first)
    XCTAssertEqual(finalSource["messagesHash"] as? String,sourceHash)
    XCTAssertEqual(fresh(final,method:"thread/fork").count,expectedWrites)
    if existing != nil {
      let queries=fresh(final,method:"thread/fork-status");XCTAssertFalse(queries.isEmpty)
      for row in queries {
        let params=(row["request"] as? [String:Any])?["params"] as? [String:Any]
        XCTAssertEqual(params?["threadId"] as? String,source);XCTAssertEqual(params?["operationId"] as? String,confirmed["clientOperationId"] as? String)
      }
    }
    for method in ["turn/start","thread/create","project/create","thread/manage"] { XCTAssertTrue(fresh(final,method:method).isEmpty) }
    let proof:[String:Any]=["sourceThread":source,"selectedRun":selectedRun,"forkedThread":child,
      "operationId":try XCTUnwrap(confirmed["clientOperationId"] as? String),"copiedMessages":childHeaders.count,
      "sourceMessagesHash":sourceHash,"sourceDraftPreserved":true,"inheritedResourcePreviewVerified":resourcePreviewVerified,
      "newModelInputs":0,"newForkWrites":expectedWrites,"newProjectWrites":0,"wholeF62Passed":false]
    print("EASYWORK_COMPLEX_FORK_NO_MODEL_PROOF:"+String(decoding:try JSONSerialization.data(withJSONObject:proof,options:[.sortedKeys]),as:UTF8.self))
  }
  @MainActor func runEasyWorkResourceFormats(app:XCUIApplication,composer:XCUIElement,config:[String:Any],
    evidence:(String) async throws->[String:Any],shot:(String)->Void) async throws {
    guard config["newModelInputsAuthorized"] as? Int==0,config["reuseExistingClient"] as? Bool==true,
      app.responds(to:NSSelectorFromString("setCurrentInteractionOptions:")),
      let oldOptions=app.value(forKey:"currentInteractionOptions") else { throw NSError(domain:"EasyWorkResourceFormats",code:1) }
    app.setValue(NSNumber(value:3),forKey:"currentInteractionOptions")
    defer { app.setValue(oldOptions,forKey:"currentInteractionOptions") }
    let thread=try XCTUnwrap(config["resourceThreadId"] as? String)
    let original=try XCTUnwrap(config["originalThreadId"] as? String)
    var originalDraft=try XCTUnwrap(composer.value as? String)
    func currentThread()->String? { (app.staticTexts["agent-dev-target"].value as? String)?.components(separatedBy:"|").dropFirst().first }
    func select(_ id:String,_ title:String) throws {
      openEasyWorkSidebar(app:app)
      if id==thread {
        let list=app.scrollViews["sidebar-list"]
        XCTAssertTrue(list.waitForExistence(timeout:10))
        list.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.15)).press(forDuration:0.1,
          thenDragTo:list.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.85)))
      }
      let search=app.descendants(matching:.any).matching(identifier:"sidebar-search").firstMatch
      if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
      XCTAssertTrue(search.waitForExistence(timeout:10));try replaceEasyWorkManagementText(app:app,field:search,with:title)
      if app.keyboards.count>0 { app.scrollViews["sidebar-list"].swipeUp() }
      if id==thread {
        let available=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in self.easyWorkVisibleSidebarRow(app:app,thread:id) != nil },object:app)
        guard XCTWaiter.wait(for:[available],timeout:60) == .completed else {
          shot("native-ios-resource-thread-list-not-ready")
          easyWorkExaminerText(app.debugDescription,name:"resource-thread-list-not-ready-tree",testCase:self)
          throw NSError(domain:"EasyWorkResourceFormats",code:6)
        }
      }
      var row=easyWorkVisibleSidebarRow(app:app,thread:id)
      for _ in 0..<10 where row==nil { app.scrollViews["sidebar-list"].swipeUp();row=easyWorkVisibleSidebarRow(app:app,thread:id) }
      let exact=try XCTUnwrap(row)
      let text=title.filter { $0.isLetter || $0.isNumber }
      let recognition=VNRecognizeTextRequest();recognition.recognitionLanguages=["zh-Hans","en-US"];recognition.recognitionLevel = .accurate
      try VNImageRequestHandler(cgImage:try XCTUnwrap(XCUIScreen.main.screenshot().image.cgImage),options:[:]).perform([recognition])
      let visible=try XCTUnwrap(recognition.results?.first { observation in
        guard let line=observation.topCandidates(1).first?.string else { return false }
        let point=CGPoint(x:observation.boundingBox.midX*app.frame.width,y:(1-observation.boundingBox.midY)*app.frame.height)
        return exact.frame.contains(point) && line.filter { $0.isLetter || $0.isNumber }.contains(text)
      },"Verify the exact thread's visible label and its native hit region after refresh")
      let point=CGPoint(x:visible.boundingBox.midX*app.frame.width,y:(1-visible.boundingBox.midY)*app.frame.height)
      shot("native-ios-resource-thread-visible-before-selection")
      app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:point.x,dy:point.y)).tap()
      waitForEasyWorkDrawerClosed(app:app)
      let selected=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in currentThread()==id },object:app)
      guard XCTWaiter.wait(for:[selected],timeout:30) == .completed else { throw NSError(domain:"EasyWorkResourceFormats",code:3) }
    }
    if currentThread() != original {
      guard config["restoreOriginalSourceBeforeFormats"] as? Bool==true else { throw NSError(domain:"EasyWorkResourceFormats",code:2) }
      try select(original,try XCTUnwrap(config["originalSearchTitle"] as? String))
      originalDraft=try XCTUnwrap(composer.value as? String)
    }
    let done=app.descendants(matching:.any).matching(identifier:"QLOverlayDoneButtonAccessibilityIdentifier").firstMatch
    var pendingSystemShare=false
    defer {
      if !pendingSystemShare {
        let imageClose=app.buttons["resource-image-preview-close"]
        if imageClose.exists,imageClose.isHittable { imageClose.tap() }
        if done.exists { done.tap() }
        if currentThread() != original { try? select(original,config["originalSearchTitle"] as? String ?? "") }
        XCTAssertEqual(composer.value as? String,originalDraft)
      }
    }
    let before=try await evidence(thread)
    let oldIDs=Set((before["requests"] as? [[String:Any]] ?? []).compactMap { ($0["request"] as? [String:Any])?["requestId"] as? String })
    try select(thread,try XCTUnwrap(config["resourceSearchTitle"] as? String))
    if config["readingCoverArrival"] != nil {
      // Target selection precedes the real history read. Its old cache may
      // have no resource cards after the failed active-run projection.
      let loaded=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
        currentThread()==thread && !app.descendants(matching:.any).matching(identifier:"history-loading").firstMatch.exists
      },object:app)
      guard XCTWaiter.wait(for:[loaded],timeout:150) == .completed else {
        shot("native-ios-resource-history-not-ready")
        throw NSError(domain:"EasyWorkResourceFormats",code:21)
      }
    }
    let formats=try XCTUnwrap(config["resourceFormats"] as? [[String:Any]])
    guard !formats.isEmpty,formats.count<=3,Set(formats.compactMap { $0["kind"] as? String }).count==formats.count else { throw NSError(domain:"EasyWorkResourceFormats",code:4) }
    var nativeReactivations=0
    var completedPreviews:[String]=[]
    var productShareCancelled=false
    var versionStages:[String]=[]
    @discardableResult func verifyNativeImage(_ element:XCUIElement,expected:[Int],name:String,viewport:Bool=false,covered:Bool=false) throws -> CGPoint? {
      guard expected.count==3 else { throw NSError(domain:"EasyWorkResourceFormats",code:14) }
      var rgba=[UInt8](repeating:0,count:4)
      var visiblePoint:CGPoint?
      var matchingPixels=0
      let rendered=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
        guard app.state == .runningForeground,element.exists else { return false }
        if viewport {
          // A presented native cover correctly hides the underlying chat AX.
          // Its own exact controls establish presentation; inline checks still
          // require the selected thread's visible target marker.
          let targetVisible = covered
            ? app.buttons["resource-image-preview-close"].exists && app.buttons["resource-image-preview-close"].isHittable
            : currentThread()==thread
          guard targetVisible,let original=XCUIScreen.main.screenshot().image.cgImage else { return false }
          let width=215,height=Int(CGFloat(width)*app.frame.height/app.frame.width)
          var pixels=[UInt8](repeating:0,count:width*height*4)
          pixels.withUnsafeMutableBytes { bytes in
            let context=CGContext(data:bytes.baseAddress,width:width,height:height,bitsPerComponent:8,bytesPerRow:width*4,
              space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue)
            context?.draw(original,in:CGRect(x:0,y:0,width:width,height:height))
          }
          var xs=0,ys=0;matchingPixels=0
          for y in 0..<height { for x in 0..<width {
            let offset=(y*width+x)*4
            if (0..<3).allSatisfy({ abs(Int(pixels[offset+$0])-expected[$0])<=10 }) {
              matchingPixels+=1;xs+=x;ys+=y;rgba=Array(pixels[offset..<(offset+4)])
            }
          } }
          guard matchingPixels>=900 else { return false }
          // Validate the actual full-screen point too. This handles bitmap
          // context row orientation without relying on a stale AX crop/frame.
          let x=CGFloat(xs)/CGFloat(matchingPixels)*CGFloat(original.width)/CGFloat(width)
          let y=CGFloat(ys)/CGFloat(matchingPixels)*CGFloat(original.height)/CGFloat(height)
          for candidate in [y,CGFloat(original.height)-1-y] {
            guard let pixel=original.cropping(to:CGRect(x:x,y:candidate,width:1,height:1)) else { continue }
            var sample=[UInt8](repeating:0,count:4)
            sample.withUnsafeMutableBytes { bytes in
              let context=CGContext(data:bytes.baseAddress,width:1,height:1,bitsPerComponent:8,bytesPerRow:4,
                space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue)
              context?.draw(pixel,in:CGRect(x:0,y:0,width:1,height:1))
            }
            if (0..<3).allSatisfy({ abs(Int(sample[$0])-expected[$0])<=10 }) {
              visiblePoint=CGPoint(x:x*app.frame.width/CGFloat(original.width),y:candidate*app.frame.height/CGFloat(original.height))
              rgba=sample;return true
            }
          }
          return false
        }
        guard let original=element.screenshot().image.cgImage,
          let center=original.cropping(to:CGRect(x:original.width/2,y:original.height/2,width:1,height:1)) else { return false }
        rgba.withUnsafeMutableBytes { bytes in
          let context=CGContext(data:bytes.baseAddress,width:1,height:1,bitsPerComponent:8,bytesPerRow:4,
            space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue)
          context?.draw(center,in:CGRect(x:0,y:0,width:1,height:1))
        }
        return (0..<3).allSatisfy { abs(Int(rgba[$0])-expected[$0])<=10 }
      },object:app)
      let result=XCTWaiter.wait(for:[rendered],timeout:20)
      shot(name)
      easyWorkExaminerText(String(decoding:try JSONSerialization.data(withJSONObject:["actualRGBA":rgba.map(Int.init),"expectedRGB":expected,"nativeVisiblePixelsMatched":result == .completed,"fullViewportPixelRegion":viewport,"matchingSamplePixels":matchingPixels],options:[.sortedKeys]),as:UTF8.self),name:name+"-pixels",testCase:self)
      guard result == .completed else { throw NSError(domain:"EasyWorkResourceFormats",code:15) }
      return visiblePoint
    }
    @discardableResult func versionCheckpoint(_ stage:String,filename:String,allowPending:Bool=false) async throws -> Bool {
      let version=try XCTUnwrap(config["resourceVersion"] as? [String:Any])
      var call=URLRequest(url:try XCTUnwrap(URL(string:version["checkpointURL"] as? String ?? "")))
      call.httpMethod="POST";call.setValue("application/json",forHTTPHeaderField:"Content-Type")
      call.setValue(try XCTUnwrap(version["checkpointAuthorization"] as? String),forHTTPHeaderField:"x-easywork-checkpoint")
      call.httpBody=try JSONSerialization.data(withJSONObject:["stage":stage,"threadId":thread,"filename":filename])
      let (data,status)=try await easyWorkExaminerData(for:call)
      guard status==200,let result=try JSONSerialization.jsonObject(with:data) as? [String:Any] else {
        throw NSError(domain:"EasyWorkResourceFormats",code:16)
      }
      if result["verified"] as? Bool != true {
        if allowPending { return false }
        throw NSError(domain:"EasyWorkResourceFormats",code:16)
      }
      versionStages.append(stage)
      return true
    }
    func shareAndCancel(_ share:XCUIElement) throws {
      let system=XCUIApplication(bundleIdentifier:"com.apple.springboard")
      let savePredicate=NSPredicate(format:"label == 'Save to Files' OR label CONTAINS '存储到' OR label CONTAINS '储存到' OR label CONTAINS '保存到'")
      func saveAction()->XCUIElement? {
        for surface in [app,system] {
          let matches=surface.descendants(matching:.any).matching(savePredicate)
          for index in 0..<min(12,matches.count) {
            let value=matches.element(boundBy:index)
            if value.exists,value.isHittable { return value }
          }
        }
        return nil
      }
      share.tap();pendingSystemShare=true
      let ready=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in saveAction() != nil },object:app)
      let result=XCTWaiter.wait(for:[ready],timeout:20)
      let screen=XCTAttachment(screenshot:XCUIScreen.main.screenshot());screen.name="native-ios-product-share-action-ready";screen.lifetime = .keepAlways;add(screen)
      easyWorkExaminerText(app.debugDescription,name:"resource-product-share-sheet-tree",testCase:self)
      guard result == .completed,let save=saveAction() else { throw NSError(domain:"EasyWorkResourceFormats",code:7) }
      save.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
      func cancelAction()->XCUIElement? {
        for surface in [XCUIApplication(bundleIdentifier:"com.easyai.agenthub.easyworkacceptance"),system] {
          let value=surface.descendants(matching:.any).matching(NSPredicate(format:"label IN %@",["取消","Cancel"])).firstMatch
          if value.exists,value.isHittable { return value }
        }
        return nil
      }
      func filePicker()->XCUIElement? {
        for surface in [app,system] {
          let value=surface.descendants(matching:.any).matching(NSPredicate(format:"identifier BEGINSWITH 'DOC.browsingRoot Source:'")).firstMatch
          if value.exists,surface.buttons["DOCPicker.actionButton"].exists { return value }
        }
        return nil
      }
      let choosing=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in cancelAction() != nil || filePicker() != nil },object:app)
      guard XCTWaiter.wait(for:[choosing],timeout:20) == .completed else {
        let screen=XCTAttachment(screenshot:XCUIScreen.main.screenshot());screen.name="native-ios-save-picker-cancel-target-missing";screen.lifetime = .keepAlways;add(screen)
        easyWorkExaminerText(app.debugDescription,name:"resource-save-picker-cancel-missing-tree",testCase:self)
        throw NSError(domain:"EasyWorkResourceFormats",code:11)
      }
      shot("native-ios-verified-resource-save-picker-cancel")
      if let cancel=cancelAction() {
        cancel.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
      } else {
        // A folder's leading control is Browse. Return to the picker root to
        // expose Cancel; Save and any file/folder selection stay untouched.
        XCTAssertNotNil(filePicker())
        let back=app.buttons["BackButton"]
        guard back.exists,back.isHittable,["浏览","Browse"].contains(back.label) else {
          throw NSError(domain:"EasyWorkResourceFormats",code:17)
        }
        back.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
        let rootCancel=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in cancelAction() != nil },object:app)
        guard XCTWaiter.wait(for:[rootCancel],timeout:15) == .completed else {
          shot("native-ios-save-picker-root-cancel-missing")
          easyWorkExaminerText(app.debugDescription,name:"resource-save-picker-root-cancel-missing-tree",testCase:self)
          throw NSError(domain:"EasyWorkResourceFormats",code:18)
        }
        try XCTUnwrap(cancelAction()).coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
      }
      let dismissed=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in filePicker()==nil },object:app)
      guard XCTWaiter.wait(for:[dismissed],timeout:15) == .completed else {
        shot("native-ios-save-picker-dismissal-pending")
        easyWorkExaminerText(app.debugDescription,name:"resource-save-picker-dismissal-pending-tree",testCase:self)
        throw NSError(domain:"EasyWorkResourceFormats",code:13)
      }
      if saveAction() != nil {
        for surface in [app,system] {
          let outside=surface.descendants(matching:.any).matching(identifier:"PopoverDismissRegion").firstMatch
          if outside.exists { outside.coordinate(withNormalizedOffset:CGVector(dx:0.1,dy:0.2)).tap();break }
          let dismiss=surface.descendants(matching:.any).matching(NSPredicate(format:"label IN %@",["关闭","Close","取消","Cancel"])).firstMatch
          if dismiss.exists,dismiss.isHittable { dismiss.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap();break }
        }
      }
      let returned=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in share.exists && share.isHittable && saveAction()==nil && cancelAction()==nil },object:app)
      guard XCTWaiter.wait(for:[returned],timeout:20) == .completed else { throw NSError(domain:"EasyWorkResourceFormats",code:12) }
      pendingSystemShare=false;productShareCancelled=true
    }
    for format in formats {
      let filename=try XCTUnwrap(format["name"] as? String)
      guard filename==(filename as NSString).lastPathComponent else { throw NSError(domain:"EasyWorkResourceFormats",code:5) }
      let preview=app.descendants(matching:.any).matching(identifier:"preview-"+filename).firstMatch
      let download=app.descendants(matching:.any).matching(identifier:"download-"+filename).firstMatch
      func unobscured(_ element:XCUIElement)->Bool {
        guard element.exists else { return false }
        let frame=element.frame,top=app.buttons["client-switch"].frame.maxY+8
        let bottom=app.buttons["permission-settings"].frame.minY-8
        return !frame.isEmpty && frame.minY>=top && frame.maxY<=bottom
      }
      func expose(_ element:XCUIElement) throws {
        for _ in 0..<16 where !unobscured(element) {
          let list=app.scrollViews["message-list"]
          if element.exists,element.frame.minY<app.buttons["client-switch"].frame.maxY+8 {
            list.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.35)).press(forDuration:0.05,
              thenDragTo:list.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.55)))
          } else {
            list.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.65)).press(forDuration:0.05,
              thenDragTo:list.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.45)))
          }
        }
        guard unobscured(element) else {
          shot("native-ios-resource-control-obscured")
          easyWorkExaminerText(app.debugDescription,name:"resource-control-obscured-tree",testCase:self)
          throw NSError(domain:"EasyWorkResourceFormats",code:10)
        }
      }
      if config["readingCoverArrival"] != nil {
        // The original image is now in an earlier completed reply. Search
        // toward older messages before the existing forward scan.
        for _ in 0..<24 where !preview.exists && !download.exists {
          let earlier=app.buttons["history-local-more"]
          if earlier.exists && earlier.isHittable { earlier.tap() }
          else { app.scrollViews["message-list"].swipeDown() }
        }
      }
      for _ in 0..<12 where !preview.exists && !download.exists { app.scrollViews["message-list"].swipeUp() }
      let card=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in preview.exists || download.exists },object:app)
      guard XCTWaiter.wait(for:[card],timeout:20) == .completed else {
        shot("native-ios-resource-card-not-accessible")
        easyWorkExaminerText(app.debugDescription,name:"resource-card-not-accessible-tree",testCase:self)
        throw NSError(domain:"EasyWorkResourceFormats",code:19)
      }
      if !preview.exists { try expose(download);download.tap() }
      XCTAssertTrue(preview.waitForExistence(timeout:45));try expose(preview)
      let kind=try XCTUnwrap(format["kind"] as? String)
      if config["verifyProductShareOnly"] as? Bool==true {
        guard kind=="pdf",config["acceptedNativePreviewPrefix"] as? String != nil else { throw NSError(domain:"EasyWorkResourceFormats",code:13) }
        let share=app.buttons["share-resource-"+filename].firstMatch;try expose(share)
        try shareAndCancel(share);continue
      }
      if kind=="png",config["resourceVersion"] != nil {
        // On launch the accepted history cache can show the last V2 while
        // the real V1 history read is still loading. Open only the observed
        // requested version, so an old immutable cover is never mislabelled.
        let inline=app.descendants(matching:.any).matching(identifier:"received-image-"+filename).firstMatch
        let rgb=try XCTUnwrap(format["expectedRGB"] as? [Int])
        let point=try XCTUnwrap(verifyNativeImage(inline,expected:rgb,name:"native-ios-resource-v1-ready-before-preview",viewport:true))
        app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:point.x,dy:point.y)).tap()
      } else { preview.tap() }
      if kind=="png" {
        let image=app.descendants(matching:.any).matching(identifier:"resource-image-preview-image").firstMatch
        let close=app.buttons["resource-image-preview-close"]
        XCTAssertTrue(image.waitForExistence(timeout:20));XCTAssertTrue(close.exists)
        let expected=try XCTUnwrap(format["expectedRGB"] as? [Int])
        // Element screenshot crops can retain an obsolete AX element type
        // during presentation. The reading-cover case checks the real screen
        // pixels together with the exact preview controls instead.
        try verifyNativeImage(image,expected:expected,name:"resource-png-native",viewport:config["readingCoverArrival"] != nil,covered:config["readingCoverArrival"] != nil)
        shot("native-ios-existing-resource-png-app-preview")
        if let arrival=config["readingCoverArrival"] as? [String:Any] {
          let readinessOnly=arrival["readinessOnly"] as? Bool==true
          guard formats.count==1,config["newModelInputsAuthorized"] as? Int==0,
            arrival["jointNewModelBudget"] as? Int == (readinessOnly ? 0 : 1),
            UUID(uuidString:arrival["caseId"] as? String ?? "") != nil else { throw NSError(domain:"EasyWorkReadingImageCover",code:1) }
          func checkpoint(_ stage:String) async throws -> Bool {
            var call=URLRequest(url:try XCTUnwrap(URL(string:arrival["checkpointURL"] as? String ?? "")))
            call.httpMethod="POST";call.setValue("application/json",forHTTPHeaderField:"Content-Type")
            call.setValue(try XCTUnwrap(arrival["checkpointAuthorization"] as? String),forHTTPHeaderField:"x-easywork-checkpoint")
            call.httpBody=try JSONSerialization.data(withJSONObject:["stage":stage,"platform":"ios","threadId":thread,"caseId":try XCTUnwrap(arrival["caseId"] as? String)])
            let (data,status)=try await easyWorkExaminerData(for:call)
            guard status==200,let value=try JSONSerialization.jsonObject(with:data) as? [String:Any] else { throw NSError(domain:"EasyWorkReadingImageCover",code:2) }
            return value["verified"] as? Bool==true
          }
          guard try await checkpoint("ios-covered-ready") else { throw NSError(domain:"EasyWorkReadingImageCover",code:3) }
          if readinessOnly {
            close.tap();XCTAssertTrue(image.waitForNonExistence(timeout:15))
            guard try await checkpoint("ios-existing-image-readiness-complete") else { throw NSError(domain:"EasyWorkReadingImageCover",code:7) }
            try select(original,try XCTUnwrap(config["originalSearchTitle"] as? String))
            XCTAssertEqual(composer.value as? String,originalDraft)
            easyWorkExaminerText("Actual original PNG open/close and original source return only. No new reply or model; this is driver readiness, not new-arrival reading acceptance.",name:"easywork-image-cover-driver-readiness-only",testCase:self)
            return
          }
          var covered=false
          let deadline=Date().addingTimeInterval(210)
          while !covered && Date()<deadline {
            guard image.exists,close.exists,app.state == .runningForeground else { throw NSError(domain:"EasyWorkReadingImageCover",code:4) }
            covered=try await checkpoint("ios-new-final-received-covered")
            if !covered { try await Task.sleep(for:.milliseconds(500)) }
          }
          guard covered else { throw NSError(domain:"EasyWorkReadingImageCover",code:5) }
          try verifyNativeImage(image,expected:expected,name:"native-ios-new-reply-arrived-behind-image",viewport:true,covered:true)
          let marker=try XCTUnwrap(arrival["replyMarker"] as? String)
          let reply=app.staticTexts.matching(NSPredicate(format:"label == %@",marker)).firstMatch
          XCTAssertFalse(reply.isHittable,"A new reply behind the actual image cover is not visible")
          close.tap();XCTAssertTrue(image.waitForNonExistence(timeout:15))
          for _ in 0..<12 where !reply.isHittable { app.scrollViews["message-list"].swipeUp() }
          XCTAssertTrue(reply.waitForExistence(timeout:30));XCTAssertTrue(reply.isHittable)
          var visible=false
          let visibleDeadline=Date().addingTimeInterval(60)
          while !visible && Date()<visibleDeadline {
            visible=try await checkpoint("ios-new-final-visible")
            if !visible { try await Task.sleep(for:.milliseconds(500)) }
          }
          guard visible else { throw NSError(domain:"EasyWorkReadingImageCover",code:6) }
          shot("native-ios-new-final-read-after-image-dismissal")
          easyWorkExaminerText("Actual original PNG cover remained present while a new EasyWork final reply reached this installation. Its original reader watermark stayed below that new final revision; only normal dismissal plus actual visible reply acknowledged it. The Android side owns the one new model input; this iOS side sends none.",name:"easywork-new-final-image-cover-reading-proof",testCase:self)
          try select(original,try XCTUnwrap(config["originalSearchTitle"] as? String))
          XCTAssertEqual(composer.value as? String,originalDraft)
          return
        }
        image.doubleTap()
        let zoomed=XCTNSPredicateExpectation(predicate:NSPredicate(format:"value == %@","200%"),object:image)
        XCTAssertEqual(XCTWaiter.wait(for:[zoomed],timeout:10),.completed)
        image.doubleTap()
        let reset=XCTNSPredicateExpectation(predicate:NSPredicate(format:"value == %@","100%"),object:image)
        XCTAssertEqual(XCTWaiter.wait(for:[reset],timeout:10),.completed)
        close.tap()
        let returned=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
          app.descendants(matching:.any).matching(identifier:"resource-image-preview-image").count==0 && app.scrollViews["message-list"].exists
        },object:app)
        XCTAssertEqual(XCTWaiter.wait(for:[returned],timeout:15),.completed)
        if let version=config["resourceVersion"] as? [String:Any] {
          try await versionCheckpoint("image-v1-previewed",filename:filename)
          var resolved=try await versionCheckpoint("image-v2-resolved",filename:filename,allowPending:true)
          if !resolved {
            let refresh=app.descendants(matching:.any).matching(NSPredicate(format:"label == %@","刷新历史")).firstMatch
            if !refresh.exists {
              let menu=app.descendants(matching:.any).matching(identifier:"chat-menu").firstMatch
              guard menu.exists,menu.isEnabled,!menu.frame.isEmpty,
                app.frame.contains(CGPoint(x:menu.frame.midX,y:menu.frame.midY)) else { throw NSError(domain:"EasyWorkResourceFormats",code:20) }
              menu.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
            }
            guard refresh.waitForExistence(timeout:15) else {
              shot("native-ios-resource-refresh-menu-not-ready")
              easyWorkExaminerText(app.debugDescription,name:"resource-refresh-menu-not-ready-tree",testCase:self)
              throw NSError(domain:"EasyWorkResourceFormats",code:21)
            }
            refresh.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
          }
          for _ in 0..<45 where !resolved {
            // The compact native observer intentionally omits resource bytes
            // and digests. The independent desktop witness checks the actual
            // fresh resolve response, then native pixels must match it below.
            resolved=try await versionCheckpoint("image-v2-resolved",filename:filename,allowPending:true)
            if resolved { break }
            try await Task.sleep(for:.seconds(1))
          }
          XCTAssertTrue(resolved,"Refresh must resolve the actual changed resource version")
          let inline=app.descendants(matching:.any).matching(identifier:"received-image-"+filename).firstMatch
          let rgb=try XCTUnwrap(version["newRGB"] as? [Int])
          let point=try XCTUnwrap(verifyNativeImage(inline,expected:rgb,name:"native-ios-resource-current-v2-inline",viewport:true))
          app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:point.x,dy:point.y)).tap()
          XCTAssertTrue(image.waitForExistence(timeout:20))
          try verifyNativeImage(image,expected:rgb,name:"native-ios-resource-current-v2-preview")
          close.tap();XCTAssertEqual(XCTWaiter.wait(for:[XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in !image.exists },object:app)],timeout:15),.completed)
          try await versionCheckpoint("image-v2-previewed",filename:filename)
          let restoredPoint=try XCTUnwrap(verifyNativeImage(inline,expected:rgb,name:"native-ios-resource-v2-before-cache-recovery",viewport:true))
          app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:restoredPoint.x,dy:restoredPoint.y)).tap()
          XCTAssertTrue(image.waitForExistence(timeout:20))
          try verifyNativeImage(image,expected:rgb,name:"native-ios-resource-corrupt-cache-recovered")
          close.tap();XCTAssertEqual(XCTWaiter.wait(for:[XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in !image.exists },object:app)],timeout:15),.completed)
          try await versionCheckpoint("image-cache-recovered",filename:filename)
        }
        completedPreviews.append(kind)
        continue
      }
      let quickLook=app.otherElements["QLPreviewControllerView"].firstMatch
      XCTAssertTrue(quickLook.waitForExistence(timeout:20))
      if format["kind"] as? String=="png",!done.exists {
        let image=app.otherElements["QLMarkupImageItemViewControllerMarkupImageViewAccessibilityIdentifier"].firstMatch
        XCTAssertTrue(quickLook.exists);XCTAssertTrue(image.waitForExistence(timeout:10));image.tap()
      }
      if !done.exists { XCTAssertTrue(done.waitForExistence(timeout:20)) }
      if let text=format["visibleText"] as? String {
        XCTAssertTrue(app.textViews.matching(NSPredicate(format:"label CONTAINS %@ OR value CONTAINS %@",text,text)).firstMatch.waitForExistence(timeout:20))
      }
      XCTAssertFalse(app.staticTexts["无法预览文件"].exists)
      if let marker=format["visiblePDFMarker"] as? String {
        let expected=marker.lowercased().filter { $0.isLetter || $0.isNumber }
        var visible:[String]=[]
        let rendered=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
          do {
            let recognition=VNRecognizeTextRequest();recognition.recognitionLanguages=["en-US"];recognition.recognitionLevel = .accurate
            guard let image=XCUIScreen.main.screenshot().image.cgImage else { return false }
            try VNImageRequestHandler(cgImage:image,options:[:]).perform([recognition])
            visible=recognition.results?.compactMap { $0.topCandidates(1).first?.string } ?? []
            return visible.contains { $0.lowercased().filter { $0.isLetter || $0.isNumber }.contains(expected) }
          } catch { return false }
        },object:app)
        let ready=XCTWaiter.wait(for:[rendered],timeout:20)
        let capture=XCTAttachment(screenshot:XCUIScreen.main.screenshot());capture.name="native-ios-pdf-actual-render-readiness";capture.lifetime = .keepAlways;add(capture)
        easyWorkExaminerText(String(decoding:try JSONSerialization.data(withJSONObject:["recognizedText":visible,"expectedMarker":marker,"nativePixelsReady":ready == .completed],options:[.sortedKeys]),as:UTF8.self),name:"resource-pdf-pixel-readiness",testCase:self)
        XCTAssertEqual(ready,.completed,"Verify actual rendered PDF text pixels")
      }
      shot("native-ios-existing-resource-"+kind+"-quicklook")
      if kind=="png",!done.exists {
        app.otherElements["QLMarkupImageItemViewControllerMarkupImageViewAccessibilityIdentifier"].firstMatch.tap()
        XCTAssertTrue(done.waitForExistence(timeout:10))
      }
      // This system control reports legacy Button and modern PopUpButton.
      // Tap its current verified frame, avoiding XCTest's type-specific scroll.
      let closeFrame=done.frame
      guard !closeFrame.isEmpty,
        closeFrame.midX<120,closeFrame.midY<160,app.frame.contains(CGPoint(x:closeFrame.midX,y:closeFrame.midY)) else {
        throw NSError(domain:"EasyWorkResourceFormats",code:8)
      }
      app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:closeFrame.midX,dy:closeFrame.midY)).tap()
      let afterClose=XCTAttachment(screenshot:XCUIScreen.main.screenshot());afterClose.name="native-ios-resource-"+kind+"-after-close-screen";afterClose.lifetime = .keepAlways;add(afterClose)
      if !app.scrollViews["message-list"].exists {
        nativeReactivations+=1;app.activate()
      }
      let dismissed=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
        app.descendants(matching:.any).matching(identifier:"QLOverlayDoneButtonAccessibilityIdentifier").count==0 && app.scrollViews["message-list"].isHittable
      },object:app)
      XCTAssertEqual(XCTWaiter.wait(for:[dismissed],timeout:15),.completed)
      XCTAssertTrue(preview.waitForExistence(timeout:15))
      easyWorkExaminerText(app.debugDescription,name:"resource-"+kind+"-returned-tree",testCase:self)
      completedPreviews.append(kind)
      if config["verifyProductShare"] as? Bool==true,kind=="pdf" {
        let share=app.buttons["share-resource-"+filename].firstMatch
        XCTAssertTrue(share.waitForExistence(timeout:10));try expose(share);try shareAndCancel(share)
      }
    }
    try select(original,try XCTUnwrap(config["originalSearchTitle"] as? String))
    XCTAssertEqual(composer.value as? String,originalDraft)
    let final=try await evidence(thread)
    let fresh=(final["requests"] as? [[String:Any]] ?? []).compactMap { $0["request"] as? [String:Any] }.filter { !oldIDs.contains($0["requestId"] as? String ?? "") }
    for method in ["turn/start","thread/create","thread/fork","project/create","thread/manage"] { XCTAssertFalse(fresh.contains { $0["method"] as? String==method }) }
    let proof:[String:Any]=["resourceThreadId":thread,"originalThreadId":original,"originalDraftPreserved":true,
      "nativePreviewFormats":completedPreviews,"nativeQuickLookFormats":completedPreviews.filter { $0 != "png" },"acceptedPreviewPrefixAdopted":config["verifyProductShareOnly"] as? Bool==true,"sameAppNativeReactivations":nativeReactivations,"verifiedProductShareSaveCancelled":productShareCancelled,"newModelInputs":0,"wholeFeaturePassed":false]
    print("EASYWORK_RESOURCE_FORMATS_PROOF:"+String(decoding:try JSONSerialization.data(withJSONObject:proof.merging(["resourceVersionStages":versionStages]) { old,_ in old },options:[.sortedKeys]),as:UTF8.self))
  }
  @MainActor func runEasyWorkBatchArchive(app: XCUIApplication, composer: XCUIElement, config: [String: Any],
    beforeIds: Set<String>, firstMarker: String, evidence: (String) async throws -> [String: Any], shot: (String) -> Void,
    newConversation: () -> Void, replace: (XCUIElement, String) -> Void, send: () -> Void) async throws {
    func fresh(_ value: [String: Any], _ method: String) -> [[String: Any]] {
      (value["requests"] as? [[String: Any]] ?? []).filter {
        guard let request = $0["request"] as? [String: Any], let id = request["requestId"] as? String else { return false }
        return request["method"] as? String == method && !beforeIds.contains(id)
      }
    }
    let existing = config["batchThreadIds"] as? [String]
    let confirmedRoot = config["batchConfirmedOperationId"] as? String
    let ids: [String]
    let marker: String
    var preservedDraft: String?
    if let existing {
      guard existing.count == 2, Set(existing).count == 2,
        let markers = config["batchReplyMarkers"] as? [String], markers.count == 2,
        let searchTitle = config["batchSearch"] as? String else { throw NSError(domain: "EasyWorkAcceptance", code: 13) }
      ids = existing; marker = markers[1]
      if confirmedRoot != nil {
        app.buttons["chat-menu"].tap(); app.buttons["archived-chats"].firstMatch.tap()
        let row = app.buttons["archived-chat-" + ids[1]]
        XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
      } else {
      openEasyWorkSidebar(app: app)
      if !searchTitle.isEmpty {
        let search = app.textFields["sidebar-search"]
        if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        try replaceEasyWorkManagementText(app: app, field: search, with: searchTitle)
      }
      let row = try XCTUnwrap(easyWorkVisibleSidebarRow(app: app, thread: ids[1])); row.tap()
      waitForEasyWorkDrawerClosed(app: app)
      }
      let target = app.descendants(matching: .any).matching(identifier: "agent-dev-target").firstMatch
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", ids[1]), object: target)
      XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 15), .completed)
      preservedDraft = try XCTUnwrap(composer.value as? String)
      guard preservedDraft?.hasPrefix("EASYWORK_BATCH_UNSENT_") == true else { throw NSError(domain: "EasyWorkAcceptance", code: 14) }
      let reused = try await evidence("")
      XCTAssertTrue(fresh(reused, "turn/start").isEmpty)
    } else {
    let initial = try await evidence("")
    XCTAssertEqual(fresh(initial, "turn/start").count, 1)
    newConversation()
    marker = "EASYWORK_BATCH_" + String(UUID().uuidString.prefix(8))
    replace(composer, "Only reply " + marker + ". Do not call tools or modify files.")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    send()
    XCTAssertTrue(app.staticTexts[marker].firstMatch.waitForExistence(timeout: 180))
    var observed = try await evidence("")
    var modelsReady = false
    for _ in 0..<40 {
      let rows = fresh(observed, "turn/start")
      let executions = (observed["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
      let ready = rows.count == 2 && rows.allSatisfy { row in
        guard let response = row["response"] as? [String: Any], let result = response["result"] as? [String: Any], let run = result["runId"] as? String else { return false }
        return executions.contains { $0["id"] as? String == run && ($0["run"] as? [String: Any])?["status"] as? String == "completed" && !($0["providerUsage"] as? [[String: Any]] ?? []).isEmpty }
      }
      if ready { modelsReady = true; break }
      try await Task.sleep(for: .seconds(1)); observed = try await evidence("")
    }
    guard modelsReady else { XCTFail("Both original Desktop model runs must complete with actual Provider usage"); return }
    let starts = fresh(observed, "turn/start")
    XCTAssertEqual(starts.count, 2)
    ids = try starts.map { try XCTUnwrap((($0["request"] as? [String: Any])?["params"] as? [String: Any])?["threadId"] as? String) }
    XCTAssertEqual(Set(ids).count, 2)
    }
    func checkpoint(_ stage: String, operationID: String? = nil) async throws {
      var call = URLRequest(url: try XCTUnwrap(URL(string: config["managementCheckpointURL"] as? String ?? "")))
      call.httpMethod = "POST"; call.setValue("application/json", forHTTPHeaderField: "Content-Type")
      call.setValue(try XCTUnwrap(config["managementCheckpointAuthorization"] as? String), forHTTPHeaderField: "x-easywork-checkpoint")
      // Observer metadata intentionally omits full payloads; SQL witness reads the sealed canonical intent.
      var payload: [String: Any] = ["stage": stage, "ids": ids]
      if let operationID { payload["operationId"] = operationID }
      call.httpBody = try JSONSerialization.data(withJSONObject: payload)
      let (data, status) = try await easyWorkExaminerData(for: call)
      XCTAssertEqual(status, 200)
      XCTAssertEqual((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["verified"] as? Bool, true)
    }
    if existing == nil { try await checkpoint("batch-baselines") }
    let draft = preservedDraft ?? "EASYWORK_BATCH_UNSENT_" + String(UUID().uuidString.prefix(8))
    if preservedDraft == nil { replace(composer, draft) }
    else { XCTAssertEqual(composer.value as? String, draft) }
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    openEasyWorkSidebar(app: app)
    app.buttons["open-settings"].tap()
    let entry = app.buttons["settings-workspace-management"]
    XCTAssertTrue(entry.waitForExistence(timeout: 15)); entry.tap()
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    if confirmedRoot == nil {
    for id in ids {
      let row = app.buttons["management-item-" + id]
      XCTAssertTrue(row.waitForExistence(timeout: 30)); XCTAssertTrue(row.isEnabled); row.tap()
    }
    let submit = app.buttons["management-submit"]
    XCTAssertTrue(submit.isEnabled); XCTAssertTrue(submit.label.contains("2")); submit.tap()
    app.alerts.buttons["取消"].tap()
    let cancelled = try await evidence("")
    XCTAssertTrue(fresh(cancelled, "workspace/manage").isEmpty)
    for id in ids { XCTAssertTrue(app.buttons["management-item-" + id].exists) }
    submit.tap(); app.alerts.matching(NSPredicate(format: "label == %@", "归档选中的 2 个对话？")).firstMatch.buttons.matching(identifier: "management-confirm").firstMatch.tap()
    for id in ids { XCTAssertTrue(app.buttons["management-item-" + id].waitForNonExistence(timeout: 60)) }
    }
    let observed = try await evidence("")
    let batches: [[String: Any]]
    if let confirmedRoot {
      batches = (observed["requests"] as? [[String: Any]] ?? []).filter { row in
        guard let request = row["request"] as? [String: Any], request["method"] as? String == "workspace/manage",
          let params = request["params"] as? [String: Any] else { return false }
        return params["clientOperationId"] as? String == confirmedRoot
      }
    } else { batches = fresh(observed, "workspace/manage") }
    XCTAssertEqual(batches.count, 1)
    let request = try XCTUnwrap(batches.first?["request"] as? [String: Any])
    let original = try XCTUnwrap(request["params"] as? [String: Any])
    let rootID = try XCTUnwrap(original["clientOperationId"] as? String)
    XCTAssertEqual(original["action"] as? String, "threads:archive")
    let result = app.staticTexts["management-result-" + rootID]
    for _ in 0..<8 where !result.exists || !result.isHittable { app.swipeUp() }
    XCTAssertTrue(result.waitForExistence(timeout: 15)); XCTAssertTrue(result.label.contains("成功 2 项"))
    shot("native-ios-easywork-batch-two-confirmed")
    try await checkpoint("batch-archive", operationID: rootID)
    let done = app.navigationBars.buttons["完成"].firstMatch
    if !done.exists {
      let back = app.navigationBars.buttons["设置"].firstMatch
      XCTAssertTrue(back.waitForExistence(timeout: 10)); back.tap()
    }
    XCTAssertTrue(done.waitForExistence(timeout: 10)); done.tap()
    if confirmedRoot == nil {
      // A successful batch exits the selected archived chat; its draft remains under that exact target.
      app.buttons["chat-menu"].tap(); app.buttons["archived-chats"].firstMatch.tap()
      let selected = app.buttons["archived-chat-" + ids[1]]
      XCTAssertTrue(selected.waitForExistence(timeout: 30)); selected.tap()
    }
    XCTAssertEqual(composer.value as? String, draft)
    func action(_ identifier: String, _ label: String) -> XCUIElement {
      let identified = app.buttons[identifier]
      return identified.waitForExistence(timeout: 2) ? identified : app.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
    }
    let completedCleanup = config["batchCompletedCleanup"] as? [String: [String]] ?? [:]
    guard Set(completedCleanup.keys).isSubset(of: Set(ids)), completedCleanup.values.allSatisfy({ $0 == ["unarchive"] || $0 == ["unarchive", "delete"] }) else { throw NSError(domain: "EasyWorkAcceptance", code: 15) }
    func confirmedAction(_ id: String, _ action: String) async throws -> Bool {
      for _ in 0..<40 {
        let current = try await evidence(id)
        let confirmed = fresh(current, "thread/manage").contains { row in
          guard let request = row["request"] as? [String: Any], let params = request["params"] as? [String: Any],
            let response = row["response"] as? [String: Any], let result = response["result"] as? [String: Any] else { return false }
          return params["threadId"] as? String == id && params["action"] as? String == action && result["phase"] as? String == "confirmed"
        }
        if confirmed { return true }; try await Task.sleep(for: .seconds(1))
      }
      return false
    }
    for id in ids {
      let completed = completedCleanup[id] ?? []
      if completed.contains("delete") { continue }
      if completed.contains("unarchive") {
        openEasyWorkSidebar(app: app)
        let row = try XCTUnwrap(easyWorkVisibleSidebarRow(app: app, thread: id)); row.tap()
        waitForEasyWorkDrawerClosed(app: app)
      } else {
        app.buttons["chat-menu"].tap(); action("archived-chats", "已归档对话").tap()
        let archived = app.buttons["archived-chat-" + id]
        XCTAssertTrue(archived.waitForExistence(timeout: 30)); archived.tap()
        XCTAssertTrue(composer.waitForExistence(timeout: 20))
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "readonly-note").firstMatch.waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["send-message"].isEnabled, "Archived execution is read-only; the local draft can remain editable")
      }
      XCTAssertTrue(app.staticTexts[id == ids[0] ? firstMarker : marker].firstMatch.waitForExistence(timeout: 30), "Original reply remains readable")
      if !completed.contains("unarchive") {
        app.buttons["chat-menu"].tap(); action("chat-archive", "恢复对话").tap()
        let restored = try await confirmedAction(id, "unarchive")
        guard restored else { XCTFail("Explicit restoration must confirm before another action"); return }
      }
      let menu = app.buttons["chat-menu"]
      let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", "正在更新…"), object: menu)
      XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 20), .completed)
      menu.tap()
      let delete = action("chat-delete", "删除")
      guard delete.exists, delete.isEnabled, delete.frame.width > 0, delete.frame.height > 0,
        app.frame.contains(CGPoint(x: delete.frame.midX, y: delete.frame.midY)) else {
        XCTFail("Exact original menu action must have a valid native frame"); return
      }
      easyWorkExaminerText(app.debugDescription, name: "easywork-batch-delete-menu-" + id, testCase: self)
      // Legacy pop-up hit-point inference can leave the menu open; use the identified native row's center.
      delete.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let deletion = app.sheets.matching(NSPredicate(format: "label == %@", "删除此对话？")).firstMatch
      XCTAssertTrue(deletion.waitForExistence(timeout: 10))
      let identified = deletion.buttons.matching(identifier: "chat-delete-confirm").firstMatch
      let confirm = identified.waitForExistence(timeout: 2) ? identified : deletion.buttons.matching(NSPredicate(format: "label == %@", "删除")).firstMatch
      XCTAssertTrue(confirm.waitForExistence(timeout: 10)); confirm.tap()
      XCTAssertTrue(deletion.waitForNonExistence(timeout: 60))
      let deletionConfirmed = try await confirmedAction(id, "delete")
      guard deletionConfirmed else { XCTFail("Original explicit deletion must confirm before navigating to the next test thread"); return }
    }
    try await checkpoint("batch-cleanup", operationID: rootID)
    let final = try await evidence("")
    XCTAssertEqual(fresh(final, "turn/start").count, existing == nil ? 2 : 0)
    XCTAssertEqual(fresh(final, "workspace/manage").count, confirmedRoot == nil ? 1 : 0)
    XCTAssertEqual(fresh(final, "thread/manage").count, 4 - completedCleanup.values.reduce(0) { $0 + $1.count })
    shot("native-ios-easywork-batch-cleanup")
    easyWorkExaminerText(String(decoding: try JSONSerialization.data(withJSONObject: ["ids": ids, "operationId": rootID,
      "observedMetadata": original, "cancelHadNoDispatch": true, "newModelInputs": existing == nil ? 2 : 0, "reusedModelInputs": existing == nil ? 0 : 2, "batchAndCleanupModelInputs": 0], options: [.sortedKeys]), as: UTF8.self), name: "easywork-batch-archive-proof", testCase: self)
  }
  @MainActor private func waitForEasyWorkDrawerClosed(app: XCUIApplication) {
    let open = app.buttons["open-sidebar"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard app.state == .runningForeground, open.exists, open.isEnabled,
        !app.buttons["sidebar-new"].exists, app.sheets.count == 0, app.alerts.count == 0 else { return false }
      let frame = open.frame
      return !frame.isEmpty && app.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }, object: open)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed,
      "Finish the actual EasyWork drawer transition before another native tap")
  }
  @MainActor private func openEasyWorkSidebar(app: XCUIApplication) {
    waitForEasyWorkDrawerClosed(app: app)
    // XCTest may mark a visible floating control non-hittable when scrolled
    // message actions occupy the same AX coordinates behind it. Avoid its
    // automatic scroll-to-visible; keep the normal native animation wait and
    // tap only this exact enabled control's verified in-App center. The next
    // step must still find and operate the actual opened sidebar.
    app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    var previous:CGRect?
    let opened=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
      let toggle=app.buttons["sidebar-search-toggle"]
      guard app.state == .runningForeground,app.buttons["sidebar-new"].exists,toggle.exists,toggle.isEnabled else { previous=nil;return false }
      let frame=toggle.frame
      guard !frame.isEmpty,app.frame.contains(CGPoint(x:frame.midX,y:frame.midY)) else { previous=nil;return false }
      defer { previous=frame }
      return previous==frame
    },object:app)
    XCTAssertEqual(XCTWaiter.wait(for:[opened],timeout:15),.completed,
      "Wait for the opened sidebar's actual stable hit region before operating search")
  }
  @MainActor private func easyWorkVisibleSidebarRow(app: XCUIApplication, thread: String) -> XCUIElement? {
    // One chat can legitimately appear in both recent and its project. Select
    // one uniquely identified presentation of the full original Thread ID.
    for prefix in ["pinned-thread-", "recent-thread-", "thread-"] {
      let rows = app.buttons.matching(identifier: prefix + thread)
      if rows.firstMatch.exists, rows.count == 1, rows.firstMatch.isHittable { return rows.firstMatch }
    }
    return nil
  }
  @MainActor func runEasyWorkJointSecondResume(app: XCUIApplication, composer: XCUIElement,
    config: [String: Any], shot: @escaping (String) -> Void) async throws {
    let thread = try XCTUnwrap(config["readingThreadId"] as? String)
    let markers = try XCTUnwrap(config["readingReplyMarkers"] as? [String]); XCTAssertEqual(markers.count, 2)
    let baseline = try XCTUnwrap(config["readingCoveredBaselineCount"] as? Int)
    let endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(config["readingCheckpointURL"] as? String)))
    let token = try XCTUnwrap(config["readingCheckpointAuthorization"] as? String)
    let clients = app.navigationBars["客户端"]
    XCTAssertTrue(clients.exists)
    let rows = app.buttons.allElementsBoundByIndex.filter { $0.identifier.hasPrefix("client-easywork-") && $0.isSelected }
    XCTAssertEqual(rows.count, 1)
    let row = try XCTUnwrap(rows.first)
    let count = try XCTUnwrap(Int((row.value as? String ?? "").prefix(while: { $0.isNumber })))
    XCTAssertEqual(count, baseline + 1, "The original covering sheet must retain the actual second reply as unread")
    func checkpoint(_ stage: String, count: Int? = nil) async throws -> [String: Any] {
      var request = URLRequest(url: endpoint); request.httpMethod = "POST"
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.setValue(token, forHTTPHeaderField: "x-easywork-checkpoint")
      var body: [String: Any] = ["stage": stage, "threadId": thread]
      if let count { body["unreadCount"] = count }
      request.httpBody = try JSONSerialization.data(withJSONObject: body)
      let (data, status) = try await easyWorkExaminerData(for: request)
      guard status == 200, let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw NSError(domain: "EasyWorkReadingResume", code: status)
      }
      return value
    }
    let covered = try await checkpoint("ios-covered-second", count: count)
    XCTAssertEqual(covered["verified"] as? Bool, true)
    shot("native-ios-joint-original-cover-resumed")
    clients.buttons["完成"].firstMatch.tap()
    XCTAssertTrue(clients.waitForNonExistence(timeout: 15))
    XCTAssertTrue(app.staticTexts[markers[1]].firstMatch.waitForExistence(timeout: 40))
    app.scrollViews["message-list"].swipeUp()
    var settled = false
    let deadline = Date().addingTimeInterval(50)
    while !settled && Date() < deadline {
      settled = try await checkpoint("ios-second-visible")["verified"] as? Bool == true
      if !settled { try await Task.sleep(for: .milliseconds(400)) }
    }
    XCTAssertTrue(settled)
    let sentinel = try XCTUnwrap(composer.value as? String)
    XCTAssertNotNil(sentinel.range(of: "^EASYWORK_JOINT_UNSENT_[A-F0-9]{8}$", options: .regularExpression))
    try replaceEasyWorkManagementText(app: app, field: composer, with: "")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    shot("native-ios-joint-original-second-read")
    easyWorkExaminerText("Preserved original running App and client sheet; unread count +1 while covered; original second reply read only after normal uncover; no model input repeated.", name: "easywork-joint-reading-original-second-proof", testCase: self)
  }
  @MainActor func runEasyWorkJointReading(app: XCUIApplication, composer: XCUIElement,
    config: [String: Any], shot: @escaping (String) -> Void) async throws {
    let thread = try XCTUnwrap(config["readingThreadId"] as? String)
    let markers = try XCTUnwrap(config["readingReplyMarkers"] as? [String]); XCTAssertEqual(markers.count, 2)
    let endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(config["readingCheckpointURL"] as? String)))
    let token = try XCTUnwrap(config["readingCheckpointAuthorization"] as? String)
    func checkpoint(_ stage: String, count: Int? = nil) async throws -> [String: Any] {
      var request = URLRequest(url: endpoint); request.httpMethod = "POST"
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.setValue(token, forHTTPHeaderField: "x-easywork-checkpoint")
      var body: [String: Any] = ["stage": stage, "threadId": thread]
      if let count { body["unreadCount"] = count }
      request.httpBody = try JSONSerialization.data(withJSONObject: body)
      let (data, status) = try await easyWorkExaminerData(for: request)
      guard status == 200, let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw NSError(domain: "EasyWorkJointReading", code: status)
      }
      return result
    }
    openEasyWorkSidebar(app: app)
    let search = app.textFields["sidebar-search"]
    if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
    XCTAssertTrue(search.waitForExistence(timeout: 15))
    let searchTitle = try XCTUnwrap(config["readingSearch"] as? String)
    try replaceEasyWorkManagementText(app: app, field: search, with: searchTitle)
    let deadline = Date().addingTimeInterval(60)
    var row: XCUIElement?
    while row == nil && Date() < deadline {
      row = easyWorkVisibleSidebarRow(app: app, thread: thread)
      if row == nil { try await Task.sleep(for: .milliseconds(350)) }
    }
    let targetRow = try XCTUnwrap(row)
    let unread = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "未读"), object: targetRow)
    XCTAssertEqual(XCTWaiter.wait(for: [unread], timeout: 30), .completed,
      "Another phone reading must not clear this newly discovered reply")
    shot("native-ios-joint-unopened-unread")
    targetRow.tap(); waitForEasyWorkDrawerClosed(app: app)
    let target = app.descendants(matching: .any).matching(identifier: "agent-dev-target").firstMatch
    let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", thread), object: target)
    XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 20), .completed)
    XCTAssertTrue(app.staticTexts[markers[0]].firstMatch.waitForExistence(timeout: 40))
    app.scrollViews["message-list"].swipeUp()
    var visible = false
    let readDeadline = Date().addingTimeInterval(50)
    while !visible && Date() < readDeadline {
      visible = try await checkpoint("ios-first-visible")["verified"] as? Bool == true
      if !visible { try await Task.sleep(for: .milliseconds(400)) }
    }
    XCTAssertTrue(visible)
    let originalDraft = try XCTUnwrap(composer.value as? String)
    let sentinel = "EASYWORK_JOINT_UNSENT_" + String(UUID().uuidString.prefix(8))
    try replaceEasyWorkManagementText(app: app, field: composer, with: sentinel)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let clientId = try XCTUnwrap((target.value as? String)?.components(separatedBy: "|").first)
    app.buttons["client-switch"].tap()
    let clients = app.navigationBars["客户端"]
    XCTAssertTrue(clients.waitForExistence(timeout: 15))
    let client = app.buttons["client-" + clientId]
    func count() throws -> Int {
      let value = try XCTUnwrap(client.value as? String)
      let prefix = value.prefix(while: { $0.isNumber })
      return try XCTUnwrap(Int(prefix))
    }
    var baseline = try count(), stable = 0
    let stableDeadline = Date().addingTimeInterval(15)
    while stable < 2 && Date() < stableDeadline {
      try await Task.sleep(for: .milliseconds(500))
      let current = try count(); stable = current == baseline ? stable + 1 : 0; baseline = current
    }
    XCTAssertEqual(stable, 2)
    var secondReady = false
    let secondDeadline = Date().addingTimeInterval(200)
    while !secondReady && Date() < secondDeadline {
      XCTAssertTrue(clients.exists, "The actual reply stays covered while the other device submits its next model input")
      secondReady = try await checkpoint("ios-covered", count: baseline)["secondReady"] as? Bool == true
      if !secondReady { try await Task.sleep(for: .milliseconds(600)) }
    }
    XCTAssertTrue(secondReady)
    let countDeadline = Date().addingTimeInterval(45)
    while try count() != baseline + 1 && Date() < countDeadline { try await Task.sleep(for: .milliseconds(400)) }
    XCTAssertEqual(try count(), baseline + 1)
    let coveredProof = try await checkpoint("ios-covered-second", count: try count())
    XCTAssertEqual(coveredProof["verified"] as? Bool, true)
    shot("native-ios-joint-covered-new-reply")
    clients.buttons["完成"].firstMatch.tap()
    XCTAssertTrue(clients.waitForNonExistence(timeout: 15))
    XCTAssertTrue(app.staticTexts[markers[1]].firstMatch.waitForExistence(timeout: 40))
    app.scrollViews["message-list"].swipeUp()
    var settled = false
    let settledDeadline = Date().addingTimeInterval(50)
    while !settled && Date() < settledDeadline {
      settled = try await checkpoint("ios-second-visible")["verified"] as? Bool == true
      if !settled { try await Task.sleep(for: .milliseconds(400)) }
    }
    XCTAssertTrue(settled)
    XCTAssertEqual(composer.value as? String, sentinel)
    try replaceEasyWorkManagementText(app: app, field: composer, with: originalDraft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    shot("native-ios-joint-second-visible")
    easyWorkExaminerText("New thread unread while unopened; second actual model reply stayed unread under the client sheet; exact original per-device watermarks independently checked; unsent draft restored.", name: "easywork-joint-reading-proof", testCase: self)
  }
  @MainActor func runEasyWorkMigrationReauthorization(app:XCUIApplication, config:[String:Any], evidence:(String) async throws -> [String:Any], control:(String) async throws -> Void, shot:(String)->Void) async throws {
    let clientId = try XCTUnwrap(config["migrationClientId"] as? String)
    let thread = try XCTUnwrap(config["sidebarThreadId"] as? String)
    let expectedOffer = try XCTUnwrap(config["offerId"] as? String)
    let resumeApproved = config["resumeApprovedGrant"] as? Bool == true
    let composer = app.textFields["composer-input"]
    XCTAssertTrue(app.staticTexts["migration-verification-required"].waitForExistence(timeout:30))
    XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
    app.buttons["client-switch"].tap()
    let row = app.buttons["client-"+clientId]
    XCTAssertTrue(row.waitForExistence(timeout:15));XCTAssertTrue((row.value as? String ?? "").contains("迁移后需重新配对"))
    shot("native-ios-migration-unverified-client")
    let edit = app.buttons["edit-client-"+clientId]
    XCTAssertTrue(edit.isHittable);edit.tap()
    let method = app.descendants(matching:.any).matching(identifier:"easywork-connection-method").firstMatch
    XCTAssertTrue(method.waitForExistence(timeout:15));method.tap();app.buttons["通过中继连接"].firstMatch.tap()
    let origin = try XCTUnwrap(config["relayOrigin"] as? String)
    let code = try XCTUnwrap(config["connectionCode"] as? String)
    let address = app.textFields["easywork-relay-url"]
    if address.value as? String != origin { try replaceEasyWorkManagementText(app:app,field:address,with:origin) }
    try replaceEasyWorkManagementText(app:app,field:app.textFields["easywork-relay-code"],with:code)
    let finishInput=app.buttons["完成输入"]
    if finishInput.exists,finishInput.isHittable { finishInput.tap() }
    let pair = app.buttons["easywork-pair-relay"]
    let form=app.collectionViews.containing(.button,identifier:"easywork-pair-relay").firstMatch
    for _ in 0..<4 where !pair.isHittable { if form.exists { form.swipeUp() } }
    let pairReady=XCTNSPredicateExpectation(predicate:NSPredicate(format:"exists == true AND enabled == true AND hittable == true"),object:pair)
    guard XCTWaiter.wait(for:[pairReady],timeout:15) == .completed else {
      shot("native-ios-migration-pair-form-not-ready")
      throw NSError(domain:"EasyWorkMigrationExaminer",code:7)
    }
    XCTAssertEqual(app.textFields["easywork-relay-code"].value as? String,code)
    pair.tap()
    let progress = app.descendants(matching:.any).matching(identifier:"easywork-connecting").firstMatch
    if !resumeApproved {
      let waiting = XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","确认此设备"),object:progress)
      await fulfillment(of:[waiting],timeout:60)
    }
    var pending:[String:Any]?
    let deadline = Date().addingTimeInterval(30)
    while Date()<deadline {
      let value = try await evidence(thread)
      guard let offer = value["deviceOffer"] as? [String:Any], offer["offerId"] as? String == expectedOffer else { throw NSError(domain:"EasyWorkMigrationExaminer",code:3) }
      if let claim = offer["claim"] as? [String:Any], claim["device"] is [String:Any] { pending=offer;break }
      try await Task.sleep(for:.milliseconds(350))
    }
    let offer = try XCTUnwrap(pending), claim = try XCTUnwrap(offer["claim"] as? [String:Any]), device = try XCTUnwrap(claim["device"] as? [String:Any])
    let deviceId = try XCTUnwrap(device["deviceId"] as? String)
    if resumeApproved {
      XCTAssertEqual(offer["status"] as? String,"approved")
      XCTAssertEqual(deviceId,config["approvedDeviceId"] as? String)
      XCTAssertEqual(claim["claimId"] as? String,config["approvedClaimId"] as? String)
    } else {
      let verification = try XCTUnwrap(claim["verificationCode"] as? String)
      XCTAssertFalse(verification.isEmpty)
      guard progress.label.contains(verification) else {
        XCTFail("The computer claim must match this App's own authenticated pending verification code")
        throw NSError(domain:"EasyWorkMigrationExaminer",code:4)
      }
    }
    XCTAssertEqual(device["platform"] as? String,"ios");XCTAssertFalse((config["priorDeviceIds"] as? [String] ?? []).contains(deviceId))
    let publicKey = try XCTUnwrap(device["publicKey"] as? String), jwk = try XCTUnwrap(device["authPublicKey"] as? [String:Any])
    XCTAssertFalse(publicKey.isEmpty);XCTAssertEqual(jwk["kty"] as? String,"EC");XCTAssertEqual(jwk["crv"] as? String,"P-256");XCTAssertNil(jwk["d"])
    let evidenceValue:[String:Any] = ["deviceId":deviceId,"publicKey":publicKey,"authPublicKey":jwk,"offerId":expectedOffer,"claimId":try XCTUnwrap(claim["claimId"] as? String),"target":try XCTUnwrap(offer["target"] as? [String:Any]),"newModelInputs":0]
    easyWorkExaminerText(String(decoding:try JSONSerialization.data(withJSONObject:evidenceValue,options:[.sortedKeys]),as:UTF8.self),name:"easywork-migration-pending-new-device",testCase:self)
    if !resumeApproved { try await control("desktop.device-claim-approve") }
    // A busy SwiftUI Form can remove its button from AX before the network or
    // durable save completes. Wait for the actual usable conversation instead.
    let connectedUntil = Date().addingTimeInterval(120)
    var connected = false
    while Date() < connectedUntil {
      let error = app.staticTexts["easywork-pair-error"]
      if error.exists && error.isHittable {
        easyWorkExaminerText(error.label,name:"easywork-migration-connection-error",testCase:self)
        throw NSError(domain:"EasyWorkMigrationExaminer",code:5,userInfo:[NSLocalizedDescriptionKey:error.label])
      }
      if composer.exists && composer.isHittable && !app.staticTexts["migration-verification-required"].exists { connected = true;break }
      if let done = app.buttons.matching(NSPredicate(format:"label IN %@",["完成","Done"])).allElementsBoundByIndex.first(where:{$0.isHittable}) { done.tap() }
      try await Task.sleep(for:.milliseconds(400))
    }
    XCTAssertTrue(connected,"The same approved grant must finish runtime/workspace validation and durable configuration before the conversation becomes usable")
    guard connected else { throw NSError(domain:"EasyWorkMigrationExaminer",code:6) }
    XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
    if let operationId = config["migrationUnknownOperationId"] as? String {
      guard config["ownedUnknownMigrationReceiver"] as? Bool == true,
        UUID(uuidString:operationId) != nil,config["newModelInputsAuthorized"] as? Int == 0,
        let filename = config["unknownAttachmentFilename"] as? String else {
        throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:3)
      }
      @MainActor func tapStable(_ element:XCUIElement) async throws {
        let deadline=Date().addingTimeInterval(15);var previous:CGRect?;var stable=0
        while Date()<deadline {
          if element.exists,element.isEnabled,element.isHittable {
            let frame=element.frame
            if frame==previous { stable+=1 } else { previous=frame;stable=0 }
            if stable>=2,frame.width>0,frame.height>0 { element.tap();return }
          } else { previous=nil;stable=0 }
          try await Task.sleep(for:.milliseconds(200))
        }
        throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:4)
      }
      try await tapStable(app.descendants(matching:.any).matching(identifier:"chat-menu").firstMatch)
      try await tapStable(app.descendants(matching:.any).matching(identifier:"reply-delivery-records").firstMatch)
      XCTAssertTrue(app.descendants(matching:.any).matching(identifier:"reply-record-"+operationId).firstMatch.waitForExistence(timeout:15))
      XCTAssertTrue(app.staticTexts[filename].exists)
      XCTAssertTrue(app.staticTexts["reply-record-state-"+operationId].label.contains("结果待确认"))
      try await tapStable(app.descendants(matching:.any).matching(identifier:"query-detached-reply-"+operationId).firstMatch)
      let notFound=app.staticTexts.matching(NSPredicate(format:"label CONTAINS %@","Host 尚未找到此操作回执")).firstMatch
      XCTAssertTrue(notFound.waitForExistence(timeout:30))
      XCTAssertTrue(app.staticTexts["reply-record-state-"+operationId].label.contains("结果待确认"))
      var request=URLRequest(url:try XCTUnwrap(URL(string:try XCTUnwrap(config["migrationCheckpointURL"] as? String))))
      request.httpMethod="POST";request.setValue("application/json",forHTTPHeaderField:"content-type")
      request.setValue(try XCTUnwrap(config["migrationCheckpointAuthorization"] as? String),forHTTPHeaderField:"x-easywork-checkpoint")
      request.httpBody=try JSONSerialization.data(withJSONObject:["stage":"migration-original-unknown-query","deviceId":deviceId,"offerId":expectedOffer,"threadId":thread])
      let (data,status)=try await easyWorkExaminerData(for:request)
      XCTAssertEqual(status,200)
      XCTAssertEqual((try JSONSerialization.jsonObject(with:data) as? [String:Any])?["verified"] as? Bool,true)
      shot("native-ios-reauthorized-original-unknown-device-scoped-receipt")
      try await tapStable(app.navigationBars["发送记录"].buttons.matching(NSPredicate(format:"label == %@","完成")).firstMatch)
      XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
      return
    }
    let marker = try XCTUnwrap(config["latestMarker"] as? String)
    let reply = app.staticTexts.matching(NSPredicate(format:"label == %@",marker)).firstMatch
    for _ in 0..<4 where !reply.isHittable { app.scrollViews["message-list"].swipeUp() }
    XCTAssertTrue(reply.isHittable)
    let observeUntil = Date().addingTimeInterval(45);var verified = false
    while Date()<observeUntil {
      if let endpoint = config["migrationCheckpointURL"] as? String {
        var request = URLRequest(url:try XCTUnwrap(URL(string:endpoint)));request.httpMethod="POST"
        request.setValue("application/json",forHTTPHeaderField:"content-type")
        request.setValue(try XCTUnwrap(config["migrationCheckpointAuthorization"] as? String),forHTTPHeaderField:"x-easywork-checkpoint")
        request.httpBody=try JSONSerialization.data(withJSONObject:["stage":"migration-new-device-read","deviceId":deviceId,"offerId":expectedOffer,"threadId":thread])
        let (data,status) = try await easyWorkExaminerData(for:request);XCTAssertEqual(status,200)
        verified=(try JSONSerialization.jsonObject(with:data) as? [String:Any])?["verified"] as? Bool == true
      } else {
        let value = try await evidence(thread)
        let fresh = (value["requests"] as? [[String:Any]] ?? []).filter {
          ($0["request"] as? [String:Any])?["method"] as? String == "thread/acknowledge-read" && $0["deviceId"] as? String == deviceId
        }
        verified = !fresh.isEmpty
      }
      if verified { break }
      try await Task.sleep(for:.milliseconds(400))
    }
    if config["migrationCheckpointURL"] != nil { XCTAssertTrue(verified,"The actual new device must acknowledge the original latest reply without replaying any original operation") }
    // The Mac independently checks canonical receipt bytes and the fourth
    // device's read watermark even if the observer omits request device IDs.
    shot("native-ios-migration-reauthorized-original-reply")
    easyWorkExaminerText(String(decoding:try JSONSerialization.data(withJSONObject:["newDeviceId":deviceId,"offerId":expectedOffer,"originalDraftRetained":true,"latestReplyVisible":true,"observerIncludedDeviceReadAck":verified,"newModelInputs":0],options:[.sortedKeys]),as:UTF8.self),name:"easywork-migration-reauthorized-proof",testCase:self)
  }
  @MainActor func runEasyWorkMigrationAuthorizedUnknownQuery(app:XCUIApplication,config:[String:Any],shot:@escaping (String)->Void) async throws {
    let operationId=try XCTUnwrap(config["migrationUnknownOperationId"] as? String)
    let deviceId=try XCTUnwrap(config["approvedDeviceId"] as? String)
    let expectedOffer=try XCTUnwrap(config["offerId"] as? String)
    let thread=try XCTUnwrap(config["sidebarThreadId"] as? String)
    let composer=app.textFields["composer-input"]
    XCTAssertTrue(composer.waitForExistence(timeout:30))
    XCTAssertFalse(app.staticTexts["migration-verification-required"].exists)
    XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
      guard config["ownedUnknownMigrationReceiver"] as? Bool == true,
        UUID(uuidString:operationId) != nil,config["newModelInputsAuthorized"] as? Int == 0,
        let filename = config["unknownAttachmentFilename"] as? String else {
        throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:3)
      }
      @MainActor func tapStable(_ element:XCUIElement) async throws {
        let deadline=Date().addingTimeInterval(15);var previous:CGRect?;var stable=0
        while Date()<deadline {
          if element.exists,element.isEnabled,element.isHittable {
            let frame=element.frame
            if frame==previous { stable+=1 } else { previous=frame;stable=0 }
            if stable>=2,frame.width>0,frame.height>0 { element.tap();return }
          } else { previous=nil;stable=0 }
          try await Task.sleep(for:.milliseconds(200))
        }
        throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:4)
      }
      try await tapStable(app.buttons["chat-menu"])
      let records=app.buttons["reply-delivery-records"]
      try await tapStable(records)
      let query=app.buttons["query-detached-reply-"+operationId]
      if !query.waitForExistence(timeout:5),records.exists,records.isHittable {
        // Only a local navigation action may be reacquired after its unchanged
        // menu proves no transition occurred. Never repeat a receipt query/write.
        let frame=records.frame
        try await Task.sleep(for:.milliseconds(500))
        guard records.exists,records.isHittable,records.frame==frame else { throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:5) }
        records.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
      }
      XCTAssertTrue(app.descendants(matching:.any).matching(identifier:"reply-record-"+operationId).firstMatch.waitForExistence(timeout:15))
      XCTAssertTrue(app.staticTexts[filename].exists)
      XCTAssertTrue(app.staticTexts["reply-record-state-"+operationId].label.contains("结果待确认"))
      try await tapStable(app.descendants(matching:.any).matching(identifier:"query-detached-reply-"+operationId).firstMatch)
      let notFound=app.staticTexts.matching(NSPredicate(format:"label CONTAINS %@","Host 尚未找到此操作回执")).firstMatch
      XCTAssertTrue(notFound.waitForExistence(timeout:30))
      XCTAssertTrue(app.staticTexts["reply-record-state-"+operationId].label.contains("结果待确认"))
      var request=URLRequest(url:try XCTUnwrap(URL(string:try XCTUnwrap(config["migrationCheckpointURL"] as? String))))
      request.httpMethod="POST";request.setValue("application/json",forHTTPHeaderField:"content-type")
      request.setValue(try XCTUnwrap(config["migrationCheckpointAuthorization"] as? String),forHTTPHeaderField:"x-easywork-checkpoint")
      request.httpBody=try JSONSerialization.data(withJSONObject:["stage":"migration-original-unknown-query","deviceId":deviceId,"offerId":expectedOffer,"threadId":thread])
      let (data,status)=try await easyWorkExaminerData(for:request)
      XCTAssertEqual(status,200)
      XCTAssertEqual((try JSONSerialization.jsonObject(with:data) as? [String:Any])?["verified"] as? Bool,true)
      shot("native-ios-reauthorized-original-unknown-device-scoped-receipt")
      try await tapStable(app.navigationBars["发送记录"].buttons.matching(NSPredicate(format:"label == %@","完成")).firstMatch)
      XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
  }
  @MainActor func runEasyWorkMigrationAuthorizedRestore(app: XCUIApplication, config: [String: Any], shot: @escaping (String) -> Void) async throws {
    let composer = app.textFields["composer-input"]
    XCTAssertTrue(composer.waitForExistence(timeout:30));XCTAssertTrue(composer.isHittable)
    XCTAssertFalse(app.staticTexts["migration-verification-required"].exists)
    XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    openEasyWorkSidebar(app:app);app.buttons["open-settings"].tap()
    let entry = app.buttons["settings-migration"]
    for _ in 0..<5 where !entry.isHittable { app.swipeUp() }
    XCTAssertTrue(entry.isHittable);entry.tap()
    let password = app.secureTextFields["migration-password"]
    XCTAssertTrue(password.waitForExistence(timeout:15))
    let current = password.value as? String ?? ""
    guard current.isEmpty || current == password.placeholderValue else { throw NSError(domain:"EasyWorkMigrationExaminer",code:7) }
    password.tap();password.typeText(try XCTUnwrap(config["migrationPassword"] as? String))
    app.buttons["migration-import"].tap()
    let stem = ((try XCTUnwrap(config["migrationFilename"] as? String)) as NSString).deletingPathExtension
    let row = app.cells.matching(NSPredicate(format:"identifier BEGINSWITH %@ OR label BEGINSWITH %@",stem,stem)).firstMatch
    if !row.waitForExistence(timeout:3) {
      let browse = app.buttons.matching(NSPredicate(format:"label IN %@",["Browse","浏览"])).firstMatch
      if browse.exists { browse.tap() }
      let local = app.staticTexts.matching(NSPredicate(format:"label IN %@",["On My iPhone","我的 iPhone","我的iPhone"])).firstMatch
      if local.waitForExistence(timeout:5) { local.tap() }
      let folder = app.cells.matching(NSPredicate(format:"label == 'Agent Hub' OR label BEGINSWITH 'Agent Hub,' OR identifier BEGINSWITH 'Agent Hub,'")).firstMatch
      if folder.waitForExistence(timeout:5) { folder.tap() }
    }
    XCTAssertTrue(row.waitForExistence(timeout:30));XCTAssertTrue(row.isHittable);row.tap()
    let result = app.staticTexts["migration-result"]
    let refused = XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","尚未添加真实电脑的新安装"),object:result)
    await fulfillment(of:[refused],timeout:45)
    shot("native-ios-authorized-migration-overwrite-refused")
    let done = app.buttons["settings-done"]
    if !done.isHittable { let back = app.navigationBars["安全迁移"].buttons["BackButton"];XCTAssertTrue(back.waitForExistence(timeout:10));XCTAssertTrue(back.isHittable);back.tap() }
    XCTAssertTrue(done.waitForExistence(timeout:10));XCTAssertTrue(done.isHittable);done.tap()
    XCTAssertTrue(composer.waitForExistence(timeout:20));XCTAssertTrue(composer.isHittable)
    XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
    XCTAssertFalse(app.staticTexts["migration-verification-required"].exists)
    let marker = try XCTUnwrap(config["latestMarker"] as? String)
    let reply = app.staticTexts.matching(NSPredicate(format:"label == %@",marker)).firstMatch
    for _ in 0..<4 where !reply.isHittable { app.scrollViews["message-list"].swipeUp() }
    XCTAssertTrue(reply.isHittable)
    let until = Date().addingTimeInterval(45);var verified = false
    while Date()<until {
      var request = URLRequest(url:try XCTUnwrap(URL(string:try XCTUnwrap(config["migrationCheckpointURL"] as? String))));request.httpMethod="POST"
      request.setValue("application/json",forHTTPHeaderField:"content-type")
      request.setValue(try XCTUnwrap(config["migrationCheckpointAuthorization"] as? String),forHTTPHeaderField:"x-easywork-checkpoint")
      request.httpBody=try JSONSerialization.data(withJSONObject:["stage":"migration-authorized-restore"])
      let (data,status) = try await easyWorkExaminerData(for:request);XCTAssertEqual(status,200)
      verified=(try JSONSerialization.jsonObject(with:data) as? [String:Any])?["verified"] as? Bool == true
      if verified { break };try await Task.sleep(for:.milliseconds(400))
    }
    XCTAssertTrue(verified,"The same new device must make fresh authenticated runtime requests while all original operations and read watermarks remain unchanged")
    shot("native-ios-migration-authorized-process-original-reply")
    easyWorkExaminerText("Same accepted and authorized installation, new process, original system-selected ciphertext refused over existing connection, fresh authenticated runtime traffic, original draft and reply retained. No new pairing, read acknowledgement, model or import.",name:"easywork-migration-authorized-process-proof",testCase:self)
  }
  @MainActor func openEasyWorkActualSystemNotice(app:XCUIApplication,title:String,minimumHeight:CGFloat=0) throws {
    guard !title.isEmpty,Bundle.main.bundleIdentifier=="com.easyai.agenthub.easyworkacceptance.uitests.xctrunner" else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:1) }
    let springboard=XCUIApplication(bundleIdentifier:"com.apple.springboard")
    // A product sheet can retain its own interactive presentation during a status-bar drag.
    // Start from the OS Home surface and away from Dynamic Island instead.
    XCUIDevice.shared.press(.home)
    guard springboard.wait(for:.runningForeground,timeout:10) else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:4) }
    springboard.coordinate(withNormalizedOffset:CGVector(dx:0.15,dy:0.01)).press(forDuration:0.15,thenDragTo:springboard.coordinate(withNormalizedOffset:CGVector(dx:0.15,dy:0.85)))
    easyWorkExaminerText(springboard.debugDescription,name:"easywork-system-notice-center-before-query",testCase:self)
    let cards=springboard.buttons.matching(NSPredicate(format:"identifier == 'ListCell' AND label CONTAINS %@",title))
    guard cards.firstMatch.waitForExistence(timeout:15),cards.count==1 else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:2) }
    guard cards.firstMatch.frame.height>=minimumHeight else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:7) }
    for _ in 0..<4 {
      let visible=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in
        let card=cards.firstMatch,frame=card.frame
        return card.isHittable&&frame.minY>=0&&frame.maxY<=springboard.frame.maxY-12
      },object:cards.firstMatch)
      if XCTWaiter.wait(for:[visible],timeout:1.5) == .completed { break }
      let list=springboard.otherElements.matching(identifier:"combined-list-view")
      guard list.count==1,list.firstMatch.isHittable else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:5) }
      list.firstMatch.swipeUp()
    }
    guard cards.count==1,cards.firstMatch.isHittable,cards.firstMatch.frame.maxY<=springboard.frame.maxY-12 else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:6) }
    easyWorkExaminerText(springboard.debugDescription,name:"easywork-original-system-notice-before-swipe",testCase:self)
    cards.firstMatch.swipeRight()
    if cards.count==1&&cards.firstMatch.isHittable {
      let row=cards.firstMatch.frame
      let actions=springboard.buttons.matching(NSPredicate(format:"label IN %@",["打开","Open","OPEN"]))
      let exact=actions.allElementsBoundByIndex.filter { $0.isHittable&&abs($0.frame.midY-row.midY)<row.height/2&&$0.frame.midX<row.midX }
      if exact.count==1 { exact[0].tap() }
      else {
        guard exact.isEmpty,row.width>300,row.height>40,row.minX>=0,row.maxX<=springboard.frame.maxX else { throw NSError(domain:"EasyWorkSystemNoticeDriver",code:3) }
        cards.firstMatch.coordinate(withNormalizedOffset:CGVector(dx:0.12,dy:0.5)).tap()
      }
    }
    easyWorkExaminerText(springboard.debugDescription,name:"easywork-original-system-notice-after-open",testCase:self)
  }
  @MainActor func runEasyWorkTestNoticeDriver(app: XCUIApplication, config: [String: Any], shot: @escaping (String) -> Void) async throws {
    let run=try XCTUnwrap(config["notificationObservationRun"] as? String)
    let endpoint=try XCTUnwrap(URL(string:try XCTUnwrap(config["notificationCheckpointURL"] as? String)))
    let token=try XCTUnwrap(config["notificationCheckpointAuthorization"] as? String)
    func checkpoint(_ stage:String,timeout:TimeInterval=15) async throws -> Bool {
      let deadline=Date().addingTimeInterval(timeout)
      while Date()<deadline {
        var request=URLRequest(url:endpoint);request.httpMethod="POST"
        request.setValue("application/json",forHTTPHeaderField:"content-type");request.setValue(token,forHTTPHeaderField:"x-easywork-checkpoint")
        request.httpBody=try JSONSerialization.data(withJSONObject:["runId":run,"stage":stage])
        let (data,status)=try await easyWorkExaminerData(for:request);XCTAssertEqual(status,200)
        if (try JSONSerialization.jsonObject(with:data) as? [String:Any])?["verified"] as? Bool==true { return true }
        try await Task.sleep(for:.milliseconds(400))
      }
      return false
    }
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout:30))
    if config["reuseOriginalTestNotice"] as? Bool != true {
    guard try await checkpoint("initial-local-test-driver") else { throw NSError(domain:"EasyWorkLocalNoticeDriver",code:1) }
    if app.buttons["composer-dismiss-keyboard"].isHittable { app.buttons["composer-dismiss-keyboard"].tap() }
    openEasyWorkSidebar(app:app);let settings=app.buttons["open-settings"]
    XCTAssertTrue(settings.waitForExistence(timeout:10));settings.tap()
    let notifications=app.buttons["通知"].firstMatch
    XCTAssertTrue(notifications.waitForExistence(timeout:10));notifications.tap()
    let button=app.buttons["notification-test"]
    for _ in 0..<12 where !button.isHittable { app.collectionViews.firstMatch.swipeUp() }
    XCTAssertTrue(button.isHittable);button.tap()
    }
    guard try await checkpoint("actual-local-test-delivered") else { throw NSError(domain:"EasyWorkLocalNoticeDriver",code:2) }
    if config["noticeUseSharedOpenDriver"] as? Bool==true {
      try openEasyWorkActualSystemNotice(app:app,title:"Agent Hub 测试通知",minimumHeight:config["noticeUseTallLocalUtility"] as? Bool==true ? 90:0)
      guard try await checkpoint("actual-local-test-response",timeout:20) else { throw NSError(domain:"EasyWorkLocalNoticeDriver",code:4) }
      XCTAssertTrue(app.wait(for:.runningForeground,timeout:15));shot("easywork-ios-test-notice-sheet-normal-home-open")
      return
    }
    let springboard=XCUIApplication(bundleIdentifier:"com.apple.springboard")
    app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.01)).press(forDuration:0.15,thenDragTo:app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.85)))
    easyWorkExaminerText(springboard.debugDescription,name:"easywork-system-test-notice-before-action",testCase:self)
    var received=false
    let modes=config["noticeUseSwipeToOpen"] as? Bool==true ? ["swipe-open"] : ["tap","open"]
    for mode in modes where !received {
      let card=springboard.buttons.matching(NSPredicate(format:"identifier == 'ListCell' AND label CONTAINS %@","Agent Hub 测试通知"))
      guard card.firstMatch.waitForExistence(timeout:8),card.count==1,card.firstMatch.isHittable else { break }
      if mode=="swipe-open" { card.firstMatch.swipeRight() }
      if mode=="open" || mode=="swipe-open" && card.count==1 && card.firstMatch.isHittable {
        // The actual native recording shows this card's leading Open action after tap.
        // Prefer its OS node; iOS 27's snapshot sometimes omits the revealed action.
        let row=card.firstMatch.frame
        let actions=springboard.buttons.matching(NSPredicate(format:"label IN %@",["打开","Open","OPEN"]))
        let exact=actions.allElementsBoundByIndex.filter { $0.isHittable&&abs($0.frame.midY-row.midY)<row.height/2&&$0.frame.midX<row.midX }
        if exact.count==1 { exact[0].tap() }
        else {
          guard exact.isEmpty,row.width>300,row.height>40,row.minX>=0,row.maxX<=springboard.frame.maxX else { throw NSError(domain:"EasyWorkLocalNoticeDriver",code:3) }
          easyWorkExaminerText(springboard.debugDescription,name:"easywork-verified-own-card-leading-open",testCase:self)
          // Only the already observed leading action area of this uniquely matched card.
          card.firstMatch.coordinate(withNormalizedOffset:CGVector(dx:0.12,dy:0.5)).tap()
        }
      } else if mode=="tap" { card.firstMatch.tap() }
      received=try await checkpoint("actual-local-test-response",timeout:4)
      easyWorkExaminerText(springboard.debugDescription,name:"easywork-system-test-notice-after-"+mode,testCase:self)
    }
    XCTAssertTrue(received,"Only a real UN delegate default response confirms an OS notification tap; no synthetic response is sent")
    if !received { return }
    XCTAssertTrue(app.wait(for:.runningForeground,timeout:15));shot("easywork-ios-real-local-test-default-response")
  }
  @MainActor func runEasyWorkMigrationFreshImport(app: XCUIApplication, config: [String: Any], shot: @escaping (String) -> Void) async throws {
    func identityCheckpoint(_ stage: String) async throws {
      guard let address=config["migrationCheckpointURL"] as? String else { return }
      let endpoint=try XCTUnwrap(URL(string:address)), token=try XCTUnwrap(config["migrationCheckpointAuthorization"] as? String)
      let run=try XCTUnwrap(config["notificationObservationRun"] as? String)
      let deadline=Date().addingTimeInterval(60)
      while Date()<deadline {
        var request=URLRequest(url:endpoint);request.httpMethod="POST"
        request.setValue("application/json",forHTTPHeaderField:"content-type");request.setValue(token,forHTTPHeaderField:"x-easywork-checkpoint")
        request.httpBody=try JSONSerialization.data(withJSONObject:["stage":stage,"runId":run])
        let (data,status)=try await easyWorkExaminerData(for:request)
        guard status==200 else { throw NSError(domain:"EasyWorkMigrationIdentityExaminer",code:1) }
        if (try JSONSerialization.jsonObject(with:data) as? [String:Any])?["verified"] as? Bool==true { return }
        try await Task.sleep(for:.seconds(1))
      }
      throw NSError(domain:"EasyWorkMigrationIdentityExaminer",code:2,userInfo:[NSLocalizedDescriptionKey:stage])
    }
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout:30))
    if config["resumeImported"] as? Bool == true { try await identityCheckpoint("legacy-notification-identity-kept-local") }
    else { try await identityCheckpoint("fresh-notification-identity") }
    let composer = app.textFields["composer-input"]
    if config["resumeImported"] as? Bool != true {
      XCTAssertFalse(app.buttons["client-switch"].label.contains("EasyWork"),"Use the independently verified new installation, never reset an existing client")
      openEasyWorkSidebar(app:app);app.buttons["open-settings"].tap()
      let entry = app.buttons["settings-migration"]
      for _ in 0..<5 where !entry.isHittable { app.swipeUp() }
      XCTAssertTrue(entry.isHittable);entry.tap()
      let password = app.secureTextFields["migration-password"]
      XCTAssertTrue(password.waitForExistence(timeout:15))
      let filename = try XCTUnwrap(config["migrationFilename"] as? String)
      func chooseOriginalDocument(_ selectedFilename:String? = nil) throws {
        let stem = ((selectedFilename ?? filename) as NSString).deletingPathExtension
        let row = app.cells.matching(NSPredicate(format:"identifier BEGINSWITH %@ OR label BEGINSWITH %@",stem,stem)).firstMatch
        if !row.waitForExistence(timeout:3) {
          let browse = app.buttons.matching(NSPredicate(format:"label IN %@",["Browse","浏览"])).firstMatch
          guard browse.waitForExistence(timeout:15) else { throw NSError(domain:"EasyWorkMigrationExaminer",code:3,userInfo:[NSLocalizedDescriptionKey:"The actual document browser tab has not appeared"]) }
          let browseReady=XCTNSPredicateExpectation(predicate:NSPredicate(format:"hittable == true"),object:browse)
          XCTAssertEqual(XCTWaiter.wait(for:[browseReady],timeout:10),.completed)
          browse.tap()
          let local = app.staticTexts.matching(NSPredicate(format:"label IN %@",["On My iPhone","我的 iPhone","我的iPhone"])).firstMatch
          if !local.waitForExistence(timeout:5) && browse.isHittable { browse.tap() }
          if local.waitForExistence(timeout:10) { local.tap() }
          let folder = app.cells.matching(NSPredicate(format:"label == 'Agent Hub' OR label BEGINSWITH 'Agent Hub,' OR identifier BEGINSWITH 'Agent Hub,'")).firstMatch
          if folder.waitForExistence(timeout:5) { folder.tap() }
        }
        guard row.waitForExistence(timeout:30) else {
          shot("native-ios-migration-missing-original-document")
          easyWorkExaminerText(app.debugDescription,name:"easywork-migration-import-picker-missing",testCase:self)
          throw NSError(domain:"EasyWorkMigrationExaminer",code:1,userInfo:[NSLocalizedDescriptionKey:"The exact original encrypted archive is not visible in the system document picker"])
        }
        XCTAssertTrue(row.isHittable);row.tap()
      }
      func typeEmptyMigrationPassword(_ text: String) throws {
        let current = password.value as? String ?? ""
        guard current.isEmpty || current == password.placeholderValue else { throw NSError(domain:"EasyWorkMigrationExaminer",code:2,userInfo:[NSLocalizedDescriptionKey:"Reopen the local migration form before typing into its empty secure field"] ) }
        password.tap();password.typeText(text)
        XCTAssertFalse((password.value as? String ?? "").isEmpty,"The system secure field must retain masked input; actual password bytes are verified by decryption")
      }
      try typeEmptyMigrationPassword("incorrect-migration-password-2026")
      app.buttons["migration-import"].tap();try chooseOriginalDocument()
      let result = app.staticTexts["migration-result"]
      XCTAssertTrue(result.waitForExistence(timeout:30));XCTAssertTrue(result.label.contains("密码错误或迁移文件已损坏"),result.label)
      try await identityCheckpoint("wrong-password-identity-preserved")
      shot("native-ios-migration-wrong-password-preserved")
      let backToSettings = app.navigationBars["安全迁移"].buttons["BackButton"]
      XCTAssertTrue(backToSettings.waitForExistence(timeout:10));backToSettings.tap()
      XCTAssertTrue(entry.waitForExistence(timeout:10));XCTAssertTrue(entry.isHittable);entry.tap()
      XCTAssertTrue(password.waitForExistence(timeout:15))
      try typeEmptyMigrationPassword(try XCTUnwrap(config["migrationPassword"] as? String))
      if let negatives=config["migrationNegativeFilenames"] as? [String] {
        guard negatives.count<=4,config["ownedVoiceMigrationReceiver"] as? Bool==true,
          negatives.allSatisfy({ $0.hasPrefix("EWInvalidMixed-") && !$0.contains("/") && !$0.contains("\\") }) else { throw NSError(domain:"EasyWorkMigrationExaminer",code:4) }
        for (index,name) in negatives.enumerated() {
          app.buttons["migration-import"].tap();try chooseOriginalDocument(name)
          let rejected=XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","密码错误或迁移文件已损坏"),object:result)
          await fulfillment(of:[rejected],timeout:45)
          try await identityCheckpoint("invalid-archive-"+String(index)+"-identity-preserved")
          shot("native-ios-invalid-mixed-archive-"+String(index))
          XCTAssertTrue(backToSettings.isHittable);backToSettings.tap()
          XCTAssertTrue(entry.waitForExistence(timeout:10));XCTAssertTrue(entry.isHittable);entry.tap()
          XCTAssertTrue(password.waitForExistence(timeout:15))
          try typeEmptyMigrationPassword(try XCTUnwrap(config["migrationPassword"] as? String))
        }
      }
      app.buttons["migration-import"].tap();try chooseOriginalDocument()
      let restored = XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","连接、草稿"),object:result)
      await fulfillment(of:[restored],timeout:45)
      try await identityCheckpoint("legacy-notification-identity-kept-local")
      shot("native-ios-migration-imported")
      let done = app.buttons["settings-done"]
      let back = app.navigationBars["安全迁移"].buttons["BackButton"]
      if back.isHittable { back.tap() }
      if done.waitForExistence(timeout:3) { XCTAssertTrue(done.isHittable);done.tap() }
    }
    let migrationAlert=app.alerts.containing(.staticText,identifier:"迁移的 EasyWork 连接需要重新配对，请先核对电脑身份。").firstMatch
    if migrationAlert.waitForExistence(timeout:3) { migrationAlert.buttons["好"].tap() }
    let banner = app.staticTexts["migration-verification-required"]
    XCTAssertTrue(banner.waitForExistence(timeout:30));XCTAssertTrue(app.buttons["client-switch"].label.contains("EasyWork"))
    XCTAssertTrue(composer.waitForExistence(timeout:15));XCTAssertEqual(composer.value as? String,config["expectedDraft"] as? String)
    if let marker = config["latestMarker"] as? String {
      let reply = app.staticTexts.matching(NSPredicate(format:"label == %@",marker)).firstMatch
      for _ in 0..<4 where !reply.isHittable { app.scrollViews["message-list"].swipeUp() }
      XCTAssertTrue(reply.exists);XCTAssertTrue(reply.isHittable)
    } else if let operationId=config["migrationUnknownOperationId"] as? String {
      guard config["ownedUnknownMigrationReceiver"] as? Bool==true,UUID(uuidString:operationId) != nil,
        config["newModelInputsAuthorized"] as? Int==0,
        let originalText=config["expectedDraft"] as? String,let filename=config["unknownAttachmentFilename"] as? String else {
        throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:1)
      }
      @MainActor func tapStable(_ element:XCUIElement) async throws {
        let deadline=Date().addingTimeInterval(15);var previous:CGRect?;var stable=0
        while Date()<deadline {
          if element.exists,element.isEnabled,element.isHittable {
            let frame=element.frame
            if frame==previous { stable+=1 } else { previous=frame;stable=0 }
            if stable>=2,frame.width>0,frame.height>0 { element.tap();return }
          } else { previous=nil;stable=0 }
          try await Task.sleep(for:.milliseconds(200))
        }
        throw NSError(domain:"EasyWorkUnknownMigrationReceiver",code:2)
      }
      try await tapStable(app.descendants(matching:.any).matching(identifier:"chat-menu").firstMatch)
      try await tapStable(app.descendants(matching:.any).matching(identifier:"reply-delivery-records").firstMatch)
      let record=app.descendants(matching:.any).matching(identifier:"reply-record-"+operationId).firstMatch
      XCTAssertTrue(record.waitForExistence(timeout:15))
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format:"label == %@",originalText)).firstMatch.exists)
      XCTAssertTrue(app.staticTexts[filename].exists)
      XCTAssertTrue(app.staticTexts["reply-record-state-"+operationId].label.contains("结果待确认"))
      try await identityCheckpoint("unknown-original-record-visible")
      shot("native-ios-imported-genuine-unknown-original-record")
      try await tapStable(app.descendants(matching:.any).matching(identifier:"query-detached-reply-"+operationId).firstMatch)
      let refused=app.staticTexts.matching(NSPredicate(format:"label CONTAINS %@","迁移的 EasyWork 连接需要重新配对")).firstMatch
      XCTAssertTrue(refused.waitForExistence(timeout:15))
      try await identityCheckpoint("unknown-offline-query-preserved")
      let done=app.navigationBars["发送记录"].buttons.matching(NSPredicate(format:"label == %@","完成")).firstMatch
      try await tapStable(done)
      try await identityCheckpoint("unknown-record-returned-to-original-draft")
      XCTAssertEqual(composer.value as? String,originalText)
    } else { XCTAssertNotNil(config["migrationVoiceOperationId"] as? String) }
    XCTAssertTrue(app.buttons["send-message"].exists);XCTAssertFalse(app.buttons["send-message"].isEnabled)
    if let names=config["mixedMigrationFilenames"] as? [String] {
      XCTAssertEqual(app.buttons.matching(identifier:"remove-attachment").count,names.count)
      for name in names { XCTAssertTrue(app.staticTexts[name].exists) }
      shot("native-ios-imported-mixed-attachment-draft")
    }
    shot("native-ios-migration-offline-original-reply")
    easyWorkExaminerText("Actual new installation restored original encrypted archive and rejected wrong password; cached original reply and draft retained offline, explicit re-pair banner, no model inputs. Full migration remains open.",name:"easywork-migration-fresh-import-proof",testCase:self)
    if let voiceId = config["migrationVoiceOperationId"] as? String {
      try await runEasyWorkMigratedOriginalVoice(app:app,config:config,voiceId:voiceId,checkpoint:identityCheckpoint,shot:shot)
    }
  }
  @MainActor private func runEasyWorkMigratedOriginalVoice(app: XCUIApplication, config: [String:Any], voiceId: String,
    checkpoint: (String) async throws -> Void, shot: (String)->Void) async throws {
    guard UUID(uuidString:voiceId)?.uuidString.lowercased()==voiceId else { throw NSError(domain:"EasyWorkMigratedVoiceExaminer",code:1) }
    let composer=app.textFields["composer-input"]
    let originalDraft=try XCTUnwrap(composer.value as? String)
    if app.buttons["composer-dismiss-keyboard"].isHittable { app.buttons["composer-dismiss-keyboard"].tap() }
    openEasyWorkSidebar(app:app)
    let settings=app.descendants(matching:.any).matching(identifier:"open-settings").firstMatch
    XCTAssertTrue(settings.waitForExistence(timeout:15));XCTAssertTrue(settings.isHittable);settings.tap()
    let done=app.descendants(matching:.any).matching(identifier:"settings-done").firstMatch
    XCTAssertTrue(done.waitForExistence(timeout:15))
    let entry=app.descendants(matching:.any).matching(identifier:"settings-migration").firstMatch
    for _ in 0..<5 where !entry.isHittable { app.swipeUp() }
    XCTAssertTrue(entry.isHittable);entry.tap()
    let row=app.descendants(matching:.any).matching(identifier:"migration-voice-record-"+voiceId).firstMatch
    // SwiftUI exposes this Form row's identifier on its child action as well.
    // Match the observed Button type and exact action label within this original ID.
    let actions=app.buttons.matching(NSPredicate(format:"label == %@ AND identifier IN %@","保存原录音",
      ["migration-save-voice-"+voiceId,"migration-voice-record-"+voiceId]))
    let save=actions.firstMatch
    let loaded=XCTNSPredicateExpectation(predicate:NSPredicate(format:"exists == true AND isEnabled == true AND isHittable == true"),object:save)
    let appeared=XCTWaiter.wait(for:[loaded],timeout:15) == .completed
    for _ in 0..<5 where !save.isHittable { app.swipeUp() }
    guard row.exists,actions.count==1,save.exists,save.isHittable else {
      easyWorkExaminerText(app.debugDescription,name:"original-imported-voice-actual-ax-not-ready",testCase:self)
      throw NSError(domain:"EasyWorkMigratedVoiceExaminer",code:3,userInfo:[NSLocalizedDescriptionKey:"Original readonly voice action not accessible after page load; initially ready: \(appeared)"])
    }
    XCTAssertTrue(app.descendants(matching:.any).matching(NSPredicate(format:"label == %@",voiceId)).firstMatch.exists)
    XCTAssertFalse(row.buttons.matching(NSPredicate(format:"label CONTAINS '重试' OR label CONTAINS '转写'")).firstMatch.exists)
    try await checkpoint("voice-readonly-visible")
    shot("native-ios-imported-original-voice-readonly")
    if config["migrationVoiceSave"] as? Bool == true {
      let action=app.buttons["DOCPicker.actionButton"]
      save.tap();XCTAssertTrue(action.waitForExistence(timeout:30));XCTAssertTrue(action.label=="保存" || action.label=="Save")
      shot("native-ios-original-voice-system-save-before-cancel")
      app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.085)).press(forDuration:0.1,thenDragTo:app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.85)))
      XCTAssertTrue(action.waitForNonExistence(timeout:15))
      let result=app.staticTexts["migration-voice-result"]
      let cancelled=XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","原录音未保存"),object:result)
      await fulfillment(of:[cancelled],timeout:15)
      XCTAssertTrue(save.isHittable);try await checkpoint("voice-save-cancelled")
      save.tap();XCTAssertTrue(action.waitForExistence(timeout:30));XCTAssertTrue(action.isHittable);action.tap()
      XCTAssertTrue(action.waitForNonExistence(timeout:15))
      let saved=XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","原录音已保存"),object:result)
      await fulfillment(of:[saved],timeout:15)
      try await checkpoint("voice-system-saved")
      shot("native-ios-original-voice-system-saved")
    }
    if config["migrationVerifyOverwrite"] as? Bool == true {
      let password=app.secureTextFields["migration-password"]
      for _ in 0..<5 where !password.isHittable { app.swipeDown() }
      XCTAssertTrue(password.isHittable)
      let current=password.value as? String ?? ""
      guard current.isEmpty || current==password.placeholderValue else { throw NSError(domain:"EasyWorkMigratedVoiceExaminer",code:2) }
      password.tap();password.typeText(try XCTUnwrap(config["migrationPassword"] as? String))
      app.buttons["migration-import"].tap()
      let stem=(try XCTUnwrap(config["migrationFilename"] as? String) as NSString).deletingPathExtension
      let document=app.cells.matching(NSPredicate(format:"identifier BEGINSWITH %@ OR label BEGINSWITH %@",stem,stem)).firstMatch
      XCTAssertTrue(document.waitForExistence(timeout:30));XCTAssertTrue(document.isHittable);document.tap()
      let result=app.staticTexts["migration-result"]
      let refused=XCTNSPredicateExpectation(predicate:NSPredicate(format:"label CONTAINS %@","尚未添加真实电脑的新安装"),object:result)
      await fulfillment(of:[refused],timeout:45)
      try await checkpoint("voice-existing-data-overwrite-refused")
      shot("native-ios-original-voice-overwrite-refused")
    }
    let back=app.navigationBars["安全迁移"].buttons["BackButton"]
    XCTAssertTrue(back.isHittable);back.tap();XCTAssertTrue(done.waitForExistence(timeout:10));XCTAssertTrue(done.isHittable);done.tap()
    XCTAssertTrue(composer.waitForExistence(timeout:15));XCTAssertEqual(composer.value as? String,originalDraft)
    XCTAssertTrue(app.staticTexts["migration-verification-required"].exists)
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    easyWorkExaminerText("Original imported voice is read-only; original ID/draft retained, explicit re-pair required. System WAV save/cancel and sandbox bytes are independently checked, no recorder restore, retry, upload, pairing or model input.",name:"easywork-original-voice-import-readonly-proof",testCase:self)
  }
  @MainActor func prepareEasyWorkOriginalVoiceMixedDraft(app: XCUIApplication, config: [String:Any], shot:@escaping (String)->Void) async throws {
    guard config["ownedFreshVoiceSource"] as? Bool == true,
      config["newModelInputsAuthorized"] as? Int == 0, config["newVoiceCapturesAuthorized"] as? Int == 0,
      let caseId=config["mixedMigrationCaseId"] as? String, UUID(uuidString:caseId)?.uuidString.lowercased()==caseId,
      let names=config["mixedMigrationFilenames"] as? [String], names.count==2,
      names.allSatisfy({ $0.hasPrefix("easywork-mixed-"+String(caseId.prefix(8))+"-") && !$0.contains("/") && !$0.contains("\\") }),
      config["voiceMigrationOperationId"] as? String != nil else { throw NSError(domain:"EasyWorkMixedMigrationExaminer",code:1) }
    let composer=app.textFields["composer-input"]
    let draft=try XCTUnwrap(composer.value as? String)
    let remove=app.buttons.matching(identifier:"remove-attachment")
    if config["resumeOriginalMixedDraft"] as? Bool == true {
      XCTAssertEqual(remove.count,2)
      for name in names { XCTAssertTrue(app.staticTexts[name].exists) }
      return
    }
    guard remove.count==0 else { throw NSError(domain:"EasyWorkMixedMigrationExaminer",code:2,userInfo:[NSLocalizedDescriptionKey:"An existing attachment must be independently reconciled before continuing"]) }
    if app.buttons["composer-dismiss-keyboard"].isHittable { app.buttons["composer-dismiss-keyboard"].tap() }
    for (index,name) in names.enumerated() {
      app.buttons["attachment-menu"].tap();app.buttons["选择文件"].tap()
      let stem=(name as NSString).deletingPathExtension
      let row=app.cells.matching(NSPredicate(format:"identifier BEGINSWITH %@ OR label BEGINSWITH %@",stem,stem)).firstMatch
      if !row.waitForExistence(timeout:3) {
        let browse=app.buttons.matching(NSPredicate(format:"label IN %@",["Browse","浏览"])).firstMatch
        if browse.waitForExistence(timeout:10),browse.isHittable { browse.tap() }
        let local=app.staticTexts.matching(NSPredicate(format:"label IN %@",["On My iPhone","我的 iPhone","我的iPhone"])).firstMatch
        if !local.waitForExistence(timeout:3),browse.isHittable { browse.tap() }
        if local.waitForExistence(timeout:8),local.isHittable { local.tap() }
        let folder=app.cells.matching(NSPredicate(format:"label == 'Agent Hub' OR label BEGINSWITH 'Agent Hub,' OR identifier BEGINSWITH 'Agent Hub,'")).firstMatch
        if folder.waitForExistence(timeout:5),folder.isHittable { folder.tap() }
      }
      guard row.waitForExistence(timeout:20),row.isHittable else {
        easyWorkExaminerText(app.debugDescription,name:"easywork-mixed-source-document-not-ready",testCase:self)
        throw NSError(domain:"EasyWorkMixedMigrationExaminer",code:3)
      }
      row.tap()
      let open=app.buttons.matching(NSPredicate(format:"label IN %@",["Open","打开"])).firstMatch
      if open.waitForExistence(timeout:3),open.isHittable,open.isEnabled { open.tap() }
      let imported=XCTNSPredicateExpectation(predicate:NSPredicate(format:"count == %d",index+1),object:remove)
      guard XCTWaiter.wait(for:[imported],timeout:15) == .completed else {
        easyWorkExaminerText(app.debugDescription,name:"easywork-mixed-source-import-not-complete",testCase:self)
        throw NSError(domain:"EasyWorkMixedMigrationExaminer",code:4)
      }
      XCTAssertEqual(composer.value as? String,draft)
      shot("native-ios-mixed-source-document-"+String(index))
    }
    for name in names { XCTAssertTrue(app.staticTexts[name].exists) }
    easyWorkExaminerText("Two case-owned synthetic files imported through the actual system document picker into the original voice source draft. Original text unchanged; no microphone, transcription, model, upload or pairing action.",name:"easywork-mixed-original-voice-source-file-selection",testCase:self)
  }
  @MainActor func runEasyWorkMigrationExportCancel(app: XCUIApplication, composer: XCUIElement,
    config: [String: Any], evidence: (String) async throws -> [String: Any], shot: (String) -> Void) async throws {
    let thread = try XCTUnwrap(config["sidebarThreadId"] as? String)
    let before = try await evidence(thread)
    let originalDraft = try XCTUnwrap(composer.value as? String)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    openEasyWorkSidebar(app: app)
    if config["voiceMigrationOperationId"] as? String != nil {
      func dismissActualLocalError() throws {
        let alert=app.alerts.firstMatch
        guard alert.exists else { return }
        easyWorkExaminerText(alert.debugDescription,name:"original-voice-export-local-error",testCase:self)
        let buttons=alert.buttons.allElementsBoundByIndex.filter { ["好","确定","关闭","取消","OK","Cancel"].contains($0.label) && $0.isHittable }
        guard buttons.count==1 else { throw NSError(domain:"EasyWorkVoiceMigrationNavigation",code:1) }
        buttons[0].tap();XCTAssertTrue(alert.waitForNonExistence(timeout:10))
      }
      var opened=false
      for _ in 0..<3 where !opened {
        try dismissActualLocalError()
        let settings=app.descendants(matching:.any).matching(identifier:"open-settings").firstMatch
        let ready=XCTNSPredicateExpectation(predicate:NSPredicate(format:"exists == true AND isEnabled == true AND isHittable == true"),object:settings)
        guard XCTWaiter.wait(for:[ready],timeout:10) == .completed else {
          easyWorkExaminerText(app.debugDescription,name:"original-voice-export-settings-entry-not-ready",testCase:self)
          throw NSError(domain:"EasyWorkVoiceMigrationNavigation",code:2)
        }
        let frame=settings.frame
        app.coordinate(withNormalizedOffset:.zero).withOffset(CGVector(dx:frame.midX-app.frame.minX,dy:frame.midY-app.frame.minY)).tap()
        let done=app.descendants(matching:.any).matching(identifier:"settings-done").firstMatch
        opened=done.waitForExistence(timeout:5)
        if !opened && !app.alerts.firstMatch.exists {
          easyWorkExaminerText(app.debugDescription,name:"original-voice-export-settings-page-not-open",testCase:self)
          throw NSError(domain:"EasyWorkVoiceMigrationNavigation",code:3)
        }
      }
      guard opened else { throw NSError(domain:"EasyWorkVoiceMigrationNavigation",code:4) }
    } else { app.buttons["open-settings"].tap() }
    let entry = config["voiceMigrationOperationId"] as? String != nil
      ? app.descendants(matching:.any).matching(identifier:"settings-migration").firstMatch : app.buttons["settings-migration"]
    for _ in 0..<5 where !entry.isHittable { app.swipeUp() }
    guard entry.exists,entry.isHittable else {
      easyWorkExaminerText(app.debugDescription,name:"original-migration-entry-not-ready",testCase:self)
      throw NSError(domain:"EasyWorkVoiceMigrationNavigation",code:5)
    }
    entry.tap()
    let password = app.secureTextFields["migration-password"]
    XCTAssertTrue(password.waitForExistence(timeout: 15));password.tap();password.typeText(try XCTUnwrap(config["migrationPassword"] as? String))
    app.buttons["migration-export"].tap()
    let action = app.buttons["DOCPicker.actionButton"]
    guard action.waitForExistence(timeout: 30), action.label == "保存" || action.label == "Save" else {
      XCTFail("The actual system export picker must open: " + app.debugDescription);return
    }
    shot("native-ios-migration-export-picker")
    easyWorkExaminerText(app.debugDescription, name: "easywork-migration-system-export-picker", testCase: self)
    let saving = config["scenario"] as? String == "migration-export-save"
    if saving { XCTAssertTrue(action.isHittable);action.tap() }
    else {
      let start = app.coordinate(withNormalizedOffset: CGVector(dx:0.5,dy:0.085))
      let end = app.coordinate(withNormalizedOffset: CGVector(dx:0.5,dy:0.85))
      start.press(forDuration:0.1,thenDragTo:end)
    }
    XCTAssertTrue(action.waitForNonExistence(timeout:15),"The actual system save sheet must dismiss")
    let result = app.staticTexts["migration-result"]
    XCTAssertTrue(result.waitForExistence(timeout: 15));XCTAssertTrue(result.label.contains(saving ? "加密文件已保存" : "文件未保存"),result.label)
    shot(saving ? "native-ios-migration-export-saved" : "native-ios-migration-export-cancelled")
    let done = app.buttons["settings-done"]
    if !done.isHittable {
      let back = app.navigationBars["安全迁移"].buttons["BackButton"]
      XCTAssertTrue(back.waitForExistence(timeout:10));XCTAssertTrue(back.isHittable);back.tap()
    }
    XCTAssertTrue(done.waitForExistence(timeout:10));XCTAssertTrue(done.isHittable);done.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 15));XCTAssertEqual(composer.value as? String,originalDraft)
    let old = Set((before["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
    let final = try await evidence(thread)
    let new = (final["requests"] as? [[String: Any]] ?? []).compactMap { $0["request"] as? [String: Any] }.filter { !old.contains($0["requestId"] as? String ?? "") }
    XCTAssertFalse(new.contains { ["turn/start", "thread/manage", "thread/fork", "resource/upload", "input/send"].contains($0["method"] as? String ?? "") })
    easyWorkExaminerText("Actual default restored EasyWork state prepared encrypted export; system action: \(saving ? "save" : "cancel"); original draft retained; no model inputs. Full migration remains open.", name: "easywork-migration-export-proof", testCase: self)
  }
  @MainActor func runEasyWorkSidebarReadOnly(app: XCUIApplication, composer: XCUIElement,
    config: [String: Any], before: [String: Any], evidence: (String) async throws -> [String: Any],
    shot: (String) -> Void) async throws {
    let thread = try XCTUnwrap(config["sidebarThreadId"] as? String)
    let searchTitle = try XCTUnwrap(config["sidebarSearch"] as? String)
    var originalDraft: String?
    for _ in 0..<3 {
      openEasyWorkSidebar(app: app)
      let search = app.textFields["sidebar-search"]
      if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
      XCTAssertTrue(search.waitForExistence(timeout: 10))
      try replaceEasyWorkManagementText(app: app, field: search, with: searchTitle)
      let row = try XCTUnwrap(easyWorkVisibleSidebarRow(app: app, thread: thread))
      row.tap()
      waitForEasyWorkDrawerClosed(app: app)
      let target = app.descendants(matching: .any).matching(identifier: "agent-dev-target").firstMatch
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", thread), object: target)
      XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 15), .completed)
      let text = try XCTUnwrap(composer.value as? String)
      if let originalDraft { XCTAssertEqual(text, originalDraft) } else { originalDraft = text }
    }
    shot("native-ios-easywork-sidebar-readonly")
    if config["readingAuthority"] as? Bool == true {
      // Exercise the product's normal visibility-driven acknowledgement, never call it from the examiner.
      app.scrollViews["message-list"].swipeUp()
      let snippet = try XCTUnwrap(config["readingVisibleText"] as? String)
      let body = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", snippet)).firstMatch
      XCTAssertTrue(body.waitForExistence(timeout: 30), "The original latest body must really be presented")
      let endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(config["managementCheckpointURL"] as? String)))
      let token = try XCTUnwrap(config["managementCheckpointAuthorization"] as? String)
      let deadline = Date().addingTimeInterval(60)
      var confirmed = false
      while Date() < deadline && !confirmed {
        var request = URLRequest(url: endpoint); request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "x-easywork-checkpoint")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["stage": "reading-observe", "threadId": thread])
        let (data, status) = try await easyWorkExaminerData(for: request)
        XCTAssertEqual(status, 200)
        confirmed = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["verified"] as? Bool == true
        if !confirmed { try await Task.sleep(for: .milliseconds(400)) }
      }
      XCTAssertTrue(confirmed, "Independent Host must retain both actual device watermarks for this exact original reply")
      XCTAssertEqual(composer.value as? String, originalDraft)
      shot("native-ios-easywork-authority-visible")
    }
    let oldIds = Set((before["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
    let final = try await evidence(thread)
    let requests = (final["requests"] as? [[String: Any]] ?? []).compactMap { $0["request"] as? [String: Any] }.filter { !oldIds.contains($0["requestId"] as? String ?? "") }
    XCTAssertFalse(requests.contains { ["turn/start", "thread/manage", "thread/fork", "resource/upload", "input/send"].contains($0["method"] as? String ?? "") })
    easyWorkExaminerText("Three exact EasyWork sidebar reads, same original draft; zero business actions",
      name: "easywork-sidebar-readonly-proof", testCase: self)
  }
  @MainActor func replaceEasyWorkManagementText(app: XCUIApplication, field: XCUIElement, with text: String) throws {
    if !text.isEmpty {
      try pasteEasyWorkManagementText(app:app,field:field,with:text)
      return
    }
    field.tap()
    if let value = field.value as? String, !value.isEmpty, value != field.placeholderValue {
      // A restored field can put the caret at the beginning. Select the text
      // through the native editing menu instead of assuming a caret position.
      field.press(forDuration: 1.1)
      let labels = NSPredicate(format: "label IN %@", ["全选", "Select All"])
      let button = app.buttons.matching(labels).firstMatch
      let item = app.menuItems.matching(labels).firstMatch
      if button.waitForExistence(timeout: 2) { button.tap() }
      else if item.exists { item.tap() }
      else { field.tap(withNumberOfTaps: 3, numberOfTouches: 1) }
      field.typeText(XCUIKeyboardKey.delete.rawValue)
      let cleared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        let current = field.value as? String ?? ""
        return current.isEmpty || current == field.placeholderValue
      }, object: field)
      guard XCTWaiter.wait(for: [cleared], timeout: 5) == .completed else {
        XCTFail("Native selection must clear the complete EasyWork field before replacement")
        throw NSError(domain: "EasyWorkAcceptance", code: 5)
      }
    }
    field.typeText(text)
    guard field.value as? String == text else {
      XCTFail("Verify the complete EasyWork field before any remote operation")
      throw NSError(domain: "EasyWorkAcceptance", code: 6)
    }
  }
  @MainActor private func pasteEasyWorkManagementText(app: XCUIApplication, field: XCUIElement, with text: String) throws {
    // A single normal Paste action avoids Xcode keyboard injection reordering
    // characters, including plain ASCII. Keep the clipboard local and restore its contents.
    let board=UIPasteboard.general;let original=board.items
    board.setItems([["public.utf8-plain-text":text]],options:[.localOnly:true])
    let revision=board.changeCount
    defer { if board.changeCount==revision && board.string==text { board.setItems(original,options:[.localOnly:true]) } }
    field.tap()
    if let value=field.value as? String,!value.isEmpty,value != field.placeholderValue {
      field.press(forDuration:1.1)
      let labels=NSPredicate(format:"label IN %@",["全选","Select All"])
      let button=app.buttons.matching(labels).firstMatch;let item=app.menuItems.matching(labels).firstMatch
      if button.waitForExistence(timeout:2) { button.tap() }
      else if item.exists { item.tap() }
      else { field.tap(withNumberOfTaps:3,numberOfTouches:1) }
    } else { field.press(forDuration:1.1) }
    let labels=NSPredicate(format:"label IN %@",["粘贴","Paste"])
    var button=app.buttons.matching(labels).firstMatch;var item=app.menuItems.matching(labels).firstMatch
    if !button.waitForExistence(timeout:2) && !item.exists {
      field.press(forDuration:1.1);button=app.buttons.matching(labels).firstMatch;item=app.menuItems.matching(labels).firstMatch
    }
    if button.waitForExistence(timeout:2) { button.tap() }
    else if item.exists { item.tap() }
    else { throw NSError(domain:"EasyWorkAcceptance",code:16,userInfo:[NSLocalizedDescriptionKey:"Native Paste menu was not available; no remote operation permitted"]) }
    let allow=app.alerts.buttons.matching(NSPredicate(format:"label IN %@",["允许粘贴","Allow Paste"])).firstMatch
    if allow.waitForExistence(timeout:2) { allow.tap() }
    if field.identifier=="composer-input",app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let exact=XCTNSPredicateExpectation(predicate:NSPredicate { _,_ in field.value as? String==text },object:field)
    guard XCTWaiter.wait(for:[exact],timeout:8) == .completed else {
      throw NSError(domain:"EasyWorkAcceptance",code:17,userInfo:[NSLocalizedDescriptionKey:"Whole native pasted value differs; original editable draft retained and no remote operation permitted"])
    }
  }
  @MainActor func resumeEasyWorkChatManagement(app: XCUIApplication, composer: XCUIElement, config: [String: Any], beforeIds: Set<String>,
    evidence: (String) async throws -> [String: Any], shot: (String) -> Void, replace: (XCUIElement, String) -> Void) async throws {
    let thread = try XCTUnwrap(config["managementThreadId"] as? String)
    openEasyWorkSidebar(app: app)
    let search = app.textFields["sidebar-search"]
    if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
    XCTAssertTrue(search.waitForExistence(timeout: 10))
    try replaceEasyWorkManagementText(app: app, field: search, with: try XCTUnwrap(config["managementSearch"] as? String))
    // A confirmed pin moves the exact chat into another sidebar section.
    // Resume by full thread identity rather than assuming its old section.
    let list = app.scrollViews["sidebar-list"]
    var row = easyWorkVisibleSidebarRow(app: app, thread: thread)
    for _ in 0..<5 where row == nil { list.swipeUp(); row = easyWorkVisibleSidebarRow(app: app, thread: thread) }
    guard let row else {
      easyWorkExaminerText(app.debugDescription, name: "management-original-row-not-visible", testCase: self)
      XCTFail("Exact original management chat must be uniquely visible")
      throw NSError(domain: "EasyWorkAcceptance", code: 4)
    }
    row.tap()
    waitForEasyWorkDrawerClosed(app: app)
    let expected = config["originalManagementDraft"] as? String
    let restored = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard let text = composer.value as? String else { return false }
      return expected.map { text == $0 } ?? (text.range(of: "^EASYWORK_IOS_MANAGEMENT_DRAFT_[A-F0-9]{8}$", options: .regularExpression) != nil)
    }, object: composer)
    XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 30), .completed)
    let originalDraft = try XCTUnwrap(composer.value as? String)
    if config["managementRenameInspectOnly"] as? Bool == true {
      let title=try XCTUnwrap(config["managementExpectedOriginalTitle"] as? String)
      app.buttons["chat-menu"].tap()
      let identified=app.buttons["chat-rename"]
      let rename=identified.waitForExistence(timeout:2) ? identified : app.buttons.matching(NSPredicate(format:"label == %@","重命名")).firstMatch
      XCTAssertTrue(rename.waitForExistence(timeout:15));XCTAssertTrue(rename.isEnabled);rename.tap()
      let field=app.descendants(matching:.any).matching(identifier:"chat-rename-title").firstMatch
      XCTAssertTrue(field.waitForExistence(timeout:10));XCTAssertEqual(field.value as? String,title)
      shot("native-ios-restored-rename-title-readonly")
      if let probe=config["managementRenameInputProbe"] as? String {
        guard config["newModelInputsAuthorized"] as? Int==0,probe.count<=100,!probe.isEmpty,probe != title else {
          throw NSError(domain:"EasyWorkAcceptance",code:20)
        }
        try replaceEasyWorkManagementText(app:app,field:field,with:probe)
        XCTAssertEqual(field.value as? String,probe)
        shot("native-ios-rename-unsaved-unicode-paste-exact")
        app.buttons["chat-rename-cancel"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout:10))
        app.buttons["chat-menu"].tap()
        let reopened=app.buttons["chat-rename"].exists ? app.buttons["chat-rename"] : app.buttons.matching(NSPredicate(format:"label == %@","重命名")).firstMatch
        XCTAssertTrue(reopened.waitForExistence(timeout:10));reopened.tap()
        XCTAssertTrue(field.waitForExistence(timeout:10));XCTAssertEqual(field.value as? String,title)
        print("EASYWORK_IOS_RENAME_UNSAVED_INPUT:whole-native-paste-exact;cancel-and-reopen-original-title;zero-writes")
      }
      app.buttons["chat-rename-cancel"].tap()
      XCTAssertTrue(field.waitForNonExistence(timeout:20));XCTAssertEqual(composer.value as? String,originalDraft)
      let observed=try await evidence(thread)
      let fresh=(observed["requests"] as? [[String:Any]] ?? []).compactMap { $0["request"] as? [String:Any] }.filter { !beforeIds.contains($0["requestId"] as? String ?? "") }
      XCTAssertFalse(fresh.contains { ["thread/manage","turn/start"].contains($0["method"] as? String ?? "") })
      print("EASYWORK_IOS_RENAME_READONLY_RESTORED:exact-original-title;original-draft;zero-models;zero-management-writes")
      return
    }
    if let operationId = config["recoverManagementOperationId"] as? String {
      let prior = try await evidence(thread)
      let priorIds = Set((prior["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
      app.buttons["chat-menu"].tap()
      let exact = app.buttons.matching(NSPredicate(format: "value CONTAINS %@ AND (identifier == %@ OR label BEGINSWITH %@)",
        operationId, "chat-action-query", "查询聊天操作记录"))
      let labelled = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@ AND label CONTAINS %@",
        "查询聊天操作记录", String(operationId.prefix(8)), try XCTUnwrap(config["managementSearch"] as? String)))
      let candidates = exact.firstMatch.waitForExistence(timeout: 2) ? exact : labelled
      let query = candidates.firstMatch
      guard query.waitForExistence(timeout: 15), query.isEnabled else {
        XCTFail("Recover only the exact saved management operation: " + app.debugDescription)
        throw NSError(domain: "EasyWorkAcceptance", code: 9)
      }
      XCTAssertEqual(candidates.count, 1)
      query.tap()
      var queriedExactOriginal = false
      for _ in 0..<60 {
        let observed = try await evidence(thread)
        queriedExactOriginal = (observed["requests"] as? [[String: Any]] ?? []).contains { row in
          guard let request = row["request"] as? [String: Any], let id = request["requestId"] as? String else { return false }
          return !priorIds.contains(id) && request["method"] as? String == "command/status"
            && (request["params"] as? [String: Any])?["commandId"] as? String == operationId
        }
        if queriedExactOriginal { break }; try await Task.sleep(for: .milliseconds(250))
      }
      guard queriedExactOriginal else {
        XCTFail("No exact original read was observed; do not submit any new management operation")
        throw NSError(domain: "EasyWorkAcceptance", code: 10)
      }
    }
    var captured = config; captured["originalManagementDraft"] = originalDraft
    easyWorkExaminerText(originalDraft, name: "easywork-management-original-draft-before-actions", testCase: self)
    try await runEasyWorkChatManagement(app: app, composer: composer, threadId: thread, config: captured, beforeIds: beforeIds,
      evidence: evidence, shot: shot, replace: replace)
  }
  @MainActor func runEasyWorkChatManagement(app: XCUIApplication, composer: XCUIElement, threadId: String,
    config: [String: Any], beforeIds: Set<String>, evidence: (String) async throws -> [String: Any],
    shot: (String) -> Void, replace: (XCUIElement, String) -> Void) async throws {
    if config["managementUnknownDelivery"] as? Bool == true {
      try await runEasyWorkUnknownManagement(app: app, composer: composer, threadId: threadId,
        config: config, beforeIds: beforeIds, evidence: evidence, shot: shot)
      return
    }
    let suffix = String(UUID().uuidString.prefix(8))
    let draft = config["originalManagementDraft"] as? String ?? "EASYWORK_IOS_MANAGEMENT_DRAFT_" + suffix
    let defaultRenamed = config["managementRenamedTitle"] as? String ?? "EasyWork iOS " + suffix
    let renameSequence=config["managementRenameOnlyTitles"] as? [String]
    if let renameSequence {
      guard config["scenario"] as? String == "management-rename-existing",renameSequence.count==2,
        renameSequence[0] != renameSequence[1],renameSequence.allSatisfy({ !$0.isEmpty && $0.count<=200 }),
        config["originalManagementDraft"] is String else { throw NSError(domain:"EasyWorkAcceptance",code:18) }
    }
    let actions = renameSequence.map { Array(repeating:"rename",count:$0.count) } ?? ["rename", "pin", "unpin", "archive", "unarchive", "delete"]
    let completed = config["managementCompletedActions"] as? [String] ?? []
    guard completed.count < actions.count, Array(actions.prefix(completed.count)) == completed,
      completed.isEmpty || ["management-existing","management-rename-existing"].contains(config["scenario"] as? String ?? "")
        && config["managementRenamedTitle"] is String && config["originalManagementDraft"] is String else {
      throw NSError(domain: "EasyWorkAcceptance", code: 11,
        userInfo: [NSLocalizedDescriptionKey: "Resume only a confirmed prefix of the original management scenario"])
    }
    let endpoint = try XCTUnwrap(URL(string: config["managementCheckpointURL"] as? String ?? ""))
    let authorization = try XCTUnwrap(config["managementCheckpointAuthorization"] as? String)
    if config["originalManagementDraft"] != nil { XCTAssertEqual(composer.value as? String, draft) }
    else { replace(composer, draft) }
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    var seen = beforeIds
    var results: [[String: Any]] = []
    for (index, action) in actions.enumerated() where index >= completed.count {
      let renamed=renameSequence?[index] ?? defaultRenamed
      easyWorkExaminerText(action, name: "management-" + action + "-native-start", testCase: self)
      let menu = app.buttons["chat-menu"]
      XCTAssertTrue(menu.waitForExistence(timeout: 15)); menu.tap()
      let buttonID = action == "rename" ? "chat-rename" : ["pin", "unpin"].contains(action) ? "chat-pin" : action == "delete" ? "chat-delete" : "chat-archive"
      let labelled = ["rename": "重命名", "pin": "置顶", "unpin": "取消置顶", "archive": "归档", "unarchive": "恢复对话", "delete": "删除"][action]!
      let identified = app.buttons[buttonID]
      let button = identified.waitForExistence(timeout: 2) ? identified : app.buttons.matching(NSPredicate(format: "label == %@", labelled)).firstMatch
      guard button.waitForExistence(timeout: 15), button.isEnabled else {
        XCTFail("Original target action must be enabled: " + action + " " + app.debugDescription)
        throw NSError(domain: "EasyWorkAcceptance", code: 1)
      }
      button.tap()
      if action == "rename" {
        let field = app.descendants(matching: .any).matching(identifier: "chat-rename-title").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        try replaceEasyWorkManagementText(app: app, field: field, with: renamed)
        let save = app.buttons["chat-rename-save"]
        XCTAssertTrue(save.isEnabled); save.tap()
        guard field.waitForNonExistence(timeout: 60) else {
          XCTFail("Rename must retain a failed/unknown form and stop this scenario: " + app.debugDescription)
          throw NSError(domain: "EasyWorkAcceptance", code: 2)
        }
      } else if action == "delete" {
        let sheet = app.sheets.matching(NSPredicate(format: "label == %@", "删除此对话？")).firstMatch
        XCTAssertTrue(sheet.waitForExistence(timeout: 10))
        // System confirmation UI can expose both a wrapper and its child with
        // the same identifier. Keep the query inside this exact confirmation.
        let identified = sheet.buttons.matching(identifier: "chat-delete-confirm").firstMatch
        let confirm = identified.waitForExistence(timeout: 2) ? identified : sheet.buttons.matching(NSPredicate(format: "label == %@", "删除")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10)); confirm.tap()
      }
      var canonical: [String: Any]?
      for _ in 0..<120 {
        let value = try await evidence(threadId)
        let matches = (value["requests"] as? [[String: Any]] ?? []).filter { row in
          guard let request = row["request"] as? [String: Any], request["method"] as? String == "thread/manage",
            let id = request["requestId"] as? String, !seen.contains(id), let params = request["params"] as? [String: Any] else { return false }
          return params["threadId"] as? String == threadId && params["action"] as? String == action
        }
        XCTAssertLessThanOrEqual(matches.count, 1, "One explicit action, no automatic replay")
        if let row = matches.first, let response = row["response"] as? [String: Any], response["ok"] as? Bool == true,
          (response["result"] as? [String: Any])?["phase"] as? String == "confirmed",
          menu.value as? String != "正在更新…" { canonical = row; break }
        try await Task.sleep(for: .milliseconds(250))
      }
      let row = try XCTUnwrap(canonical), request = try XCTUnwrap(row["request"] as? [String: Any])
      let params = try XCTUnwrap(request["params"] as? [String: Any]), operationId = try XCTUnwrap(params["clientOperationId"] as? String)
      seen.insert(try XCTUnwrap(request["requestId"] as? String))
      if action != "delete" { XCTAssertEqual(composer.value as? String, draft, "Management must preserve the original local draft") }
      if action == "archive" {
        XCTAssertFalse(app.buttons["send-message"].isEnabled)
        menu.tap(); let archived = app.buttons["archived-chats"]
        XCTAssertTrue(archived.waitForExistence(timeout: 10)); archived.tap()
        let originalRow = app.buttons["archived-chat-" + threadId]
        XCTAssertTrue(originalRow.waitForExistence(timeout: 15)); originalRow.tap()
        XCTAssertEqual(composer.value as? String, draft)
      }
      var check: [String: Any] = ["sequence": index + 1, "action": action, "threadId": threadId,
        "operationId": operationId, "requestId": try XCTUnwrap(request["requestId"] as? String),
        "request": params, "renamedTitle": renamed, "originalDraft": draft]
      var verification = URLRequest(url: endpoint); verification.httpMethod = "POST"
      verification.setValue("application/json", forHTTPHeaderField: "Content-Type")
      verification.setValue(authorization, forHTTPHeaderField: "x-easywork-acceptance")
      verification.httpBody = try JSONSerialization.data(withJSONObject: check)
      let (data, status) = try await easyWorkExaminerData(for: verification)
      XCTAssertEqual(status, 200)
      let verified = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
      guard verified["verified"] as? Bool == true else {
        XCTFail("Independent desktop checkpoint failed for " + action)
        throw NSError(domain: "EasyWorkAcceptance", code: 3)
      }
      check["independentSQLVerified"] = true; results.append(check)
      easyWorkExaminerText(String(decoding: try JSONSerialization.data(withJSONObject: check, options: [.sortedKeys]), as: UTF8.self),
        name: "easywork-management-" + action + "-proof", testCase: self)
      shot("native-ios-easywork-management-" + action)
    }
    if renameSequence == nil {
    let draftReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: composer)
    XCTAssertEqual(XCTWaiter.wait(for: [draftReady], timeout: 15), .completed, "Confirmed deletion must leave an authorized fresh draft usable")
    try replaceEasyWorkManagementText(app: app, field: composer, with: "EASYWORK_IOS_AFTER_DELETE_" + suffix)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    XCTAssertEqual(composer.value as? String, "EASYWORK_IOS_AFTER_DELETE_" + suffix)
    shot("native-ios-easywork-management-fresh-draft")
    } else { XCTAssertEqual(composer.value as? String,draft);shot("native-ios-easywork-rename-original-draft-preserved") }
    let final = try await evidence(threadId)
    let fresh = (final["requests"] as? [[String: Any]] ?? []).compactMap { $0["request"] as? [String: Any] }.filter {
      guard let id = $0["requestId"] as? String else { return false }; return !beforeIds.contains(id)
    }
    XCTAssertEqual(fresh.filter { $0["method"] as? String == "thread/manage" }.count, actions.count - completed.count)
    XCTAssertEqual(fresh.filter { $0["method"] as? String == "turn/start" }.count, ["management-existing","management-rename-existing"].contains(config["scenario"] as? String ?? "") ? 0 : 1, "No repeated real-model baseline")
    print("EASYWORK_IOS_MANAGEMENT_PROOF:" + String(decoding: try JSONSerialization.data(withJSONObject: ["threadId": threadId, "scopedActions": results, "originalDraftPreservedBeforeDelete": true, "newManagementModelInputs": 0], options: [.sortedKeys]), as: UTF8.self))
  }
  @MainActor private func runEasyWorkUnknownManagement(app: XCUIApplication, composer: XCUIElement, threadId: String,
    config: [String: Any], beforeIds: Set<String>, evidence: (String) async throws -> [String: Any], shot: (String) -> Void) async throws {
    let suffix = String(UUID().uuidString.prefix(8))
    let firstTitle = "EASYWORK_UNKNOWN_ORIGINAL_" + suffix
    let editedTitle = "EASYWORK_UNKNOWN_EDITED_" + suffix
    let draft = config["originalManagementDraft"] as? String ?? "EASYWORK_UNKNOWN_UNSENT_" + suffix
    if config["originalManagementDraft"] != nil { XCTAssertEqual(composer.value as? String, draft) }
    else { try replaceEasyWorkManagementText(app: app, field: composer, with: draft) }
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["chat-menu"].tap()
    let rename = app.buttons.matching(NSPredicate(format: "label == %@", "重命名")).firstMatch
    XCTAssertTrue(rename.waitForExistence(timeout: 10))
    let renameReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: rename)
    XCTAssertEqual(XCTWaiter.wait(for: [renameReady], timeout: 20), .completed); rename.tap()
    let field = app.descendants(matching: .any).matching(identifier: "chat-rename-title").firstMatch
    XCTAssertTrue(field.waitForExistence(timeout: 10))
    try replaceEasyWorkManagementText(app: app, field: field, with: firstTitle)
    let save = app.buttons["chat-rename-save"]
    save.tap()
    let query = app.buttons["chat-rename-query"], end = app.buttons["chat-rename-end"]
    let alert = app.alerts["无法完成操作"]
    let surfaced = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in query.exists || alert.exists }, object: app)
    XCTAssertEqual(XCTWaiter.wait(for: [surfaced], timeout: 30), .completed)
    if alert.exists { alert.buttons["好"].tap() }
    easyWorkExaminerText(app.debugDescription, name: "easywork-unknown-management-after-alert", testCase: self)
    XCTAssertTrue(query.waitForExistence(timeout: 30))
    let endReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: end)
    XCTAssertEqual(XCTWaiter.wait(for: [endReady], timeout: 15), .completed)
    XCTAssertEqual(field.value as? String, firstTitle); XCTAssertFalse(save.isEnabled)
    end.tap(); XCTAssertTrue(end.waitForNonExistence(timeout: 10))
    try replaceEasyWorkManagementText(app: app, field: field, with: editedTitle)
    XCTAssertTrue(save.isEnabled, "Ending the original wait must allow a fresh explicit save")
    var seen = beforeIds
    func original(_ action: String) async throws -> [String: Any] {
      for _ in 0..<120 {
        let observation = try await evidence(threadId)
        let matches = (observation["requests"] as? [[String: Any]] ?? []).filter { row in
          guard let request = row["request"] as? [String: Any], let id = request["requestId"] as? String,
            !seen.contains(id), request["method"] as? String == "thread/manage",
            let params = request["params"] as? [String: Any] else { return false }
          return params["threadId"] as? String == threadId && params["action"] as? String == action
        }
        XCTAssertLessThanOrEqual(matches.count, 1)
        if let row = matches.first, let response = row["response"] as? [String: Any],
          response["ok"] as? Bool == true, (response["result"] as? [String: Any])?["phase"] as? String == "confirmed" { return row }
        try await Task.sleep(for: .milliseconds(250))
      }
      throw NSError(domain: "EasyWorkAcceptance", code: 7)
    }
    let first = try await original("rename")
    let firstRequest = try XCTUnwrap(first["request"] as? [String: Any])
    let firstID = try XCTUnwrap(firstRequest["requestId"] as? String)
    seen.insert(firstID)
    let endpoint = try XCTUnwrap(URL(string: config["managementCheckpointURL"] as? String ?? ""))
    var stages: [[String: Any]] = []
    func checkpoint(_ stage: String, _ row: [String: Any], title: String, phase: String) async throws {
      let request = try XCTUnwrap(row["request"] as? [String: Any]), params = try XCTUnwrap(request["params"] as? [String: Any])
      let check: [String: Any] = ["sequence": stages.count + 1, "stage": stage, "action": try XCTUnwrap(params["action"] as? String),
        "threadId": threadId, "requestId": try XCTUnwrap(request["requestId"] as? String),
        "operationId": try XCTUnwrap(params["clientOperationId"] as? String), "request": params,
        "originalOperationId": firstID, "renamedTitle": title, "editedTitle": editedTitle,
        "originalDraft": draft, "phase": phase, "detached": true]
      var verification = URLRequest(url: endpoint); verification.httpMethod = "POST"
      verification.setValue("application/json", forHTTPHeaderField: "Content-Type")
      verification.setValue(try XCTUnwrap(config["managementCheckpointAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      verification.httpBody = try JSONSerialization.data(withJSONObject: check)
      let (data, status) = try await easyWorkExaminerData(for: verification)
      guard status == 200,
        (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["verified"] as? Bool == true else {
        XCTFail("Independent original-operation checkpoint failed: " + stage)
        throw NSError(domain: "EasyWorkAcceptance", code: 8)
      }
      stages.append(check)
    }
    try await checkpoint("unknown-ended", first, title: firstTitle, phase: "outcome-unknown")
    query.tap()
    let status = app.staticTexts["原操作已完成，当前修改尚未保存。"]
    XCTAssertTrue(status.waitForExistence(timeout: 30))
    XCTAssertTrue(field.exists); XCTAssertEqual(field.value as? String, editedTitle)
    XCTAssertTrue(save.isEnabled)
    try await checkpoint("late-confirmation", first, title: firstTitle, phase: "confirmed")
    shot("native-ios-management-late-confirmation-edited-title-retained")
    save.tap(); XCTAssertTrue(field.waitForNonExistence(timeout: 30))
    let second = try await original("rename")
    let secondID = try XCTUnwrap((second["request"] as? [String: Any])?["requestId"] as? String)
    XCTAssertNotEqual(firstID, secondID); seen.insert(secondID)
    XCTAssertEqual(composer.value as? String, draft)
    try await checkpoint("new-title-saved", second, title: editedTitle, phase: "confirmed")
    app.buttons["chat-menu"].tap(); app.buttons.matching(NSPredicate(format: "label == %@", "删除")).firstMatch.tap()
    let sheet = app.sheets.matching(NSPredicate(format: "label == %@", "删除此对话？")).firstMatch
    XCTAssertTrue(sheet.waitForExistence(timeout: 10)); sheet.buttons.matching(identifier: "chat-delete-confirm").firstMatch.tap()
    let deleted = try await original("delete")
    try await checkpoint("delete", deleted, title: editedTitle, phase: "confirmed")
    let final = try await evidence(threadId)
    let fresh = (final["requests"] as? [[String: Any]] ?? []).compactMap { $0["request"] as? [String: Any] }.filter {
      !beforeIds.contains($0["requestId"] as? String ?? "")
    }
    XCTAssertEqual(fresh.filter { $0["method"] as? String == "thread/manage" }.count, 3)
    XCTAssertEqual(fresh.filter { $0["method"] as? String == "turn/start" }.count, config["scenario"] as? String == "management-existing" ? 0 : 1)
    print("EASYWORK_IOS_UNKNOWN_MANAGEMENT_PROOF:" + String(decoding: try JSONSerialization.data(withJSONObject:
      ["stages": stages, "fault": "authentic local confirmation delivery withheld; original query delayed 8 seconds", "newManagementModelInputs": 0], options: [.sortedKeys]), as: UTF8.self))
  }
}

extension CodexManageUITests {
  @MainActor func runEasyWorkActiveApprovalNoticeRoute(app: XCUIApplication, composer: XCUIElement, config: [String: Any], shot: @escaping (String) -> Void) async throws {
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
      let run = config["notificationObservationRun"] as? String, UUID(uuidString: run)?.uuidString.lowercased() == run,
      let endpointText = config["notificationCheckpointURL"] as? String, let endpoint = URL(string: endpointText),
      let token = config["notificationCheckpointAuthorization"] as? String,
      let message = config["notificationLiveInput"] as? String, !message.isEmpty,
      let thread = config["notificationLiveThreadId"] as? String, UUID(uuidString: thread) != nil,
      let searchText = config["notificationLiveSearch"] as? String, !searchText.isEmpty,
      let returnThread = config["notificationReturnThreadId"] as? String, UUID(uuidString: returnThread) != nil,
      let returnSearch = config["notificationReturnSearch"] as? String, !returnSearch.isEmpty else {
      throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 1)
    }
    func checkpoint(_ stage: String, timeout: TimeInterval = 100) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(timeout)
      while Date() < deadline {
        var request = URLRequest(url: endpoint); request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.setValue(token, forHTTPHeaderField: "x-easywork-checkpoint")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["runId": run, "stage": stage])
        let (data, status) = try await easyWorkExaminerData(for: request)
        guard status == 200 else { throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 2) }
        let value = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        if value["verified"] as? Bool == true { return value }
        try await Task.sleep(for: .seconds(1))
      }
      throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 3, userInfo: [NSLocalizedDescriptionKey: stage])
    }
    func select(_ id: String, searchText: String) throws {
      openEasyWorkSidebar(app: app)
      if easyWorkVisibleSidebarRow(app: app, thread: id) == nil {
        let search = app.textFields["sidebar-search"]
        if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        try replaceEasyWorkManagementText(app: app, field: search, with: searchText)
      }
      let row = try XCTUnwrap(easyWorkVisibleSidebarRow(app: app, thread: id)); row.tap()
      waitForEasyWorkDrawerClosed(app: app)
      let target = app.descendants(matching: .any).matching(identifier: "agent-dev-target").firstMatch
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", id), object: target)], timeout: 30), .completed)
    }
    func openNotifications() {
      openEasyWorkSidebar(app: app)
      XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout: 15)); app.buttons["open-settings"].tap()
      XCTAssertTrue(app.buttons["通知"].firstMatch.waitForExistence(timeout: 15)); app.buttons["通知"].firstMatch.tap()
      XCTAssertTrue(app.switches["notifications-enabled"].waitForExistence(timeout: 15))
    }
    func closeNotifications() {
      let back = app.navigationBars["通知"].buttons.firstMatch
      XCTAssertTrue(back.waitForExistence(timeout: 10)); back.tap()
      XCTAssertTrue(app.buttons["完成"].firstMatch.waitForExistence(timeout: 10)); app.buttons["完成"].firstMatch.tap()
    }
    func set(_ id: String, _ enabled: Bool, down: Bool) throws {
      let element = app.switches[id], list = app.collectionViews.firstMatch
      XCTAssertTrue(list.waitForExistence(timeout: 10))
      for _ in 0..<12 {
        let frame=element.exists ? element.frame : .zero,viewport=list.frame.intersection(app.frame).insetBy(dx:0,dy:8)
        if element.exists && !app.alerts.firstMatch.exists && frame.width>0 && frame.height>0 && !frame.isNull && !frame.isInfinite && viewport.contains(CGPoint(x:frame.midX,y:frame.midY)) {
          guard let value = element.value as? String, ["0", "1"].contains(value) else { throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 4) }
          let expected=enabled ? "1":"0"
          if value != expected {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            let confirmed=XCTNSPredicateExpectation(predicate:NSPredicate(format:"value == %@",expected),object:element)
            guard XCTWaiter.wait(for:[confirmed],timeout:10) == .completed else { throw NSError(domain:"EasyWorkApprovalNoticeExaminer",code:11) }
          }
          return
        }
        if down { list.swipeDown() } else { list.swipeUp() }
      }
      throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 5, userInfo: [NSLocalizedDescriptionKey: id])
    }
    let actual: [String: Any]
    if config["notificationResumeOriginalApproval"] as? Bool == true {
      _ = try await checkpoint("resume-existing-active")
      openNotifications()
      try set("notifications-enabled", false, down: true)
      _ = try await checkpoint("master-disabled")
      try set("notifications-enabled", true, down: true)
      actual = try await checkpoint("same-active-master-restored")
    } else {
    _ = try await checkpoint("initial-disabled")
    openNotifications(); try set("notifications-enabled", true, down: true)
    _ = try await checkpoint("live-registration-ready")
    closeNotifications(); try select(thread, searchText: searchText)
    XCTAssertTrue(composer.waitForExistence(timeout: 20))
    guard (composer.value as? String ?? "").isEmpty else { throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 6) }
    let permissions = app.buttons["permission-settings"]
    XCTAssertTrue(permissions.waitForExistence(timeout: 10)); permissions.tap()
    let choice = app.buttons["permission-confirm_every_operation"]
    for _ in 0..<6 where !choice.isHittable { app.swipeUp() }
    XCTAssertTrue(choice.isHittable); choice.tap(); app.buttons["generation-done"].tap()
    _ = try await checkpoint("normal-permission-policy-ready")
    try replaceEasyWorkManagementText(app: app, field: composer, with: message)
    XCTAssertEqual(composer.value as? String, message)
    let dismiss = app.buttons["composer-dismiss-keyboard"]
    if dismiss.exists && dismiss.isHittable && app.frame.contains(CGPoint(x: dismiss.frame.midX, y: dismiss.frame.midY)) { dismiss.tap() }
    easyWorkExaminerText(message, name: "easywork-approval-immutable-new-input", testCase: self)
    let send = app.buttons["send-message"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: send)], timeout: 45), .completed)
    send.tap()
    easyWorkExaminerText("One new input clicked once", name: "easywork-approval-input-once", testCase: self)
    try select(returnThread, searchText: returnSearch); openNotifications()
    _ = try await checkpoint("original-active-notice-visible", timeout: 240)
    try set("notification-kind-approval", false, down: false)
    _ = try await checkpoint("category-disabled")
    try set("notification-kind-approval", true, down: true)
    _ = try await checkpoint("same-active-category-restored")
    try set("notifications-enabled", false, down: true)
    _ = try await checkpoint("master-disabled")
    try set("notifications-enabled", true, down: true)
    actual = try await checkpoint("same-active-master-restored")
    shot("easywork-ios-same-active-approval-restored")
    }
    let title = try XCTUnwrap(actual["actualNotificationTitle"] as? String)
    XCTAssertFalse(title.isEmpty)
    if config["notificationResolveOriginalDirect"] as? Bool == true {
      closeNotifications(); try select(thread, searchText: searchText)
      _ = try await checkpoint("normal-navigation-exact-original-approval")
    } else {
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    // Reveal Notification Center above the still-running original App. Its
    // original selection and covering settings sheet are independently checked.
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.01)).press(forDuration: 0.15, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85)))
    easyWorkExaminerText(springboard.debugDescription, name: "easywork-actual-notification-center", testCase: self)
    let cards = springboard.descendants(matching: .any).matching(NSPredicate(format: "identifier == 'NotificationShortLookView' AND label CONTAINS %@", title))
    guard cards.firstMatch.waitForExistence(timeout: 15), cards.count == 1, cards.firstMatch.isHittable else {
      throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 7, userInfo: [NSLocalizedDescriptionKey: "Original system notification card could not be uniquely identified; approval remains pending"])
    }
    let cell = springboard.buttons.matching(NSPredicate(format: "identifier == 'ListCell' AND label CONTAINS %@", title))
    guard cell.count == 1, cell.firstMatch.isHittable else { throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 10) }
    cell.firstMatch.tap()
    if !app.wait(for: .runningForeground, timeout: 3) {
      // A collapsed Notification Center group expands on its first tap. Re-query
      // the observed inner notification button; never tap a stale coordinate.
      let content = springboard.buttons.matching(NSPredicate(format: "identifier == 'ShortLook.Platter.Content.Seamless' AND label CONTAINS %@", title))
      easyWorkExaminerText(springboard.debugDescription, name: "easywork-expanded-original-notification", testCase: self)
      guard content.count == 1, content.firstMatch.isHittable else { throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 9) }
      content.firstMatch.tap()
    }
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
    _ = try await checkpoint("actual-system-tap-exact-original-approval")
    shot("easywork-ios-system-tap-original-approval")
    }
    let deny = app.buttons["approval-deny"]
    for _ in 0..<8 where !deny.isHittable { app.swipeUp() }
    guard app.buttons.matching(identifier: "approval-deny").count == 1, deny.isHittable, deny.isEnabled else { throw NSError(domain: "EasyWorkApprovalNoticeExaminer", code: 8) }
    deny.tap()
    _ = try await checkpoint("original-denied-once-resolved", timeout: 180)
    try select(returnThread, searchText: returnSearch); openNotifications()
    try set("notifications-enabled", false, down: true)
    _ = try await checkpoint("original-preferences-disabled-restored")
    shot("easywork-ios-approval-route-disabled-restored")
  }

  @MainActor func runEasyWorkOriginalCompletedNoticeSettings(app: XCUIApplication, composer: XCUIElement, config: [String: Any], shot: @escaping (String) -> Void) async throws {
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance.uitests.xctrunner",
      let run = config["notificationObservationRun"] as? String, UUID(uuidString: run)?.uuidString.lowercased() == run else { throw NSError(domain:"EasyWorkNoticeExaminer",code:1) }
    let endpoint = try XCTUnwrap(URL(string: try XCTUnwrap(config["notificationCheckpointURL"] as? String)))
    let token = try XCTUnwrap(config["notificationCheckpointAuthorization"] as? String)
    var live: (message:String,thread:String,search:String,returnThread:String,returnSearch:String)?
    if let message=config["notificationLiveInput"] as? String {
      guard let thread=config["notificationLiveThreadId"] as? String, UUID(uuidString:thread) != nil,
        let search=config["notificationLiveSearch"] as? String, !search.isEmpty,
        let returnThread=config["notificationReturnThreadId"] as? String, UUID(uuidString:returnThread) != nil,
        let returnSearch=config["notificationReturnSearch"] as? String, !returnSearch.isEmpty, !message.isEmpty else {
        throw NSError(domain:"EasyWorkNoticeExaminer",code:6,userInfo:[NSLocalizedDescriptionKey:"Invalid explicit notice input navigation; no settings or input changed"])
      }
      live=(message,thread,search,returnThread,returnSearch)
    }
    @discardableResult func checkpoint(_ stage: String, timeout: TimeInterval = 90) async throws -> [String:Any] {
      let deadline = Date().addingTimeInterval(timeout)
      while Date() < deadline {
        var request = URLRequest(url:endpoint);request.httpMethod="POST"
        request.setValue("application/json",forHTTPHeaderField:"content-type");request.setValue(token,forHTTPHeaderField:"x-easywork-checkpoint")
        request.httpBody = try JSONSerialization.data(withJSONObject:["runId":run,"stage":stage])
        let (data,status) = try await easyWorkExaminerData(for:request)
        guard status == 200 else { throw NSError(domain:"EasyWorkNoticeExaminer",code:2) }
        let value=try XCTUnwrap(try JSONSerialization.jsonObject(with:data) as? [String:Any])
        if value["verified"] as? Bool == true { return value }
        try await Task.sleep(for:.seconds(1))
      }
      throw NSError(domain:"EasyWorkNoticeExaminer",code:3,userInfo:[NSLocalizedDescriptionKey:"Actual original OS notice checkpoint did not settle: " + stage])
    }
    func reveal(_ id:String, down:Bool) throws -> XCUIElement {
      let element=app.switches[id];let list=app.collectionViews.firstMatch
      XCTAssertTrue(list.waitForExistence(timeout:10))
      for _ in 0..<12 {
        if element.exists && !app.alerts.firstMatch.exists {
          let frame=element.frame,viewport=list.frame.intersection(app.frame).insetBy(dx:0,dy:8)
          if frame.width>0 && frame.height>0 && !frame.isNull && !frame.isInfinite && viewport.contains(CGPoint(x:frame.midX,y:frame.midY)) { return element }
        }
        if down { list.swipeDown() } else { list.swipeUp() }
      }
      throw NSError(domain:"EasyWorkNoticeExaminer",code:4,userInfo:[NSLocalizedDescriptionKey:"Exact settings switch is not reachable: " + id])
    }
    func set(_ id:String,_ value:Bool,down:Bool) throws {
      let element=try reveal(id,down:down);let expected=value ? "1":"0"
      guard let before=element.value as? String,["0","1"].contains(before) else { throw NSError(domain:"EasyWorkNoticeExaminer",code:5) }
      if before != expected {
        element.coordinate(withNormalizedOffset:CGVector(dx:0.92,dy:0.5)).tap()
        let confirmed=XCTNSPredicateExpectation(predicate:NSPredicate(format:"value == %@",expected),object:element)
        guard XCTWaiter.wait(for:[confirmed],timeout:10) == .completed else { throw NSError(domain:"EasyWorkNoticeExaminer",code:10,userInfo:[NSLocalizedDescriptionKey:"The exact native switch did not reach its requested value: "+id]) }
      }
    }
    func openNotifications() {
      openEasyWorkSidebar(app:app)
      XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout:15));app.buttons["open-settings"].tap()
      XCTAssertTrue(app.buttons["通知"].firstMatch.waitForExistence(timeout:15));app.buttons["通知"].firstMatch.tap()
      XCTAssertTrue(app.switches["notifications-enabled"].waitForExistence(timeout:15))
    }
    func select(_ id:String,searchText:String) throws {
      openEasyWorkSidebar(app:app)
      if easyWorkVisibleSidebarRow(app:app,thread:id) == nil {
        let search=app.textFields["sidebar-search"]
        if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
        XCTAssertTrue(search.waitForExistence(timeout:10))
        try replaceEasyWorkManagementText(app:app,field:search,with:searchText)
      }
      let row=try XCTUnwrap(easyWorkVisibleSidebarRow(app:app,thread:id));row.tap();waitForEasyWorkDrawerClosed(app:app)
      let target=app.descendants(matching:.any).matching(identifier:"agent-dev-target").firstMatch
      let selected=XCTNSPredicateExpectation(predicate:NSPredicate(format:"value CONTAINS %@",id),object:target)
      XCTAssertEqual(XCTWaiter.wait(for:[selected],timeout:30),.completed)
    }
    if config["notificationCleanupOnly"] as? Bool == true {
      guard live == nil else { throw NSError(domain:"EasyWorkNoticeExaminer",code:7) }
      openNotifications()
      try set("notification-content-preview",true,down:false)
      try set("notification-kind-completed",false,down:true)
      try set("notifications-enabled",false,down:true)
      try await checkpoint("original-preferences-disabled-restored")
      return
    }
    try await checkpoint("initial-disabled")
    openNotifications()
    XCTAssertEqual(app.switches["notifications-enabled"].value as? String,"0")
    var primary: Error?
    do {
    try set("notification-kind-completed",true,down:false)
    try set("notifications-enabled",true,down:true)
    let springboard=XCUIApplication(bundleIdentifier:"com.apple.springboard")
    let system=springboard.alerts.firstMatch
    if system.waitForExistence(timeout:6) {
      let allow=system.buttons.matching(NSPredicate(format:"label == '允许' OR label == 'Allow'")).firstMatch
      XCTAssertTrue(allow.waitForExistence(timeout:5));allow.tap()
    }
    if let live {
      try await checkpoint("live-registration-ready")
      let back=app.navigationBars["通知"].buttons.firstMatch
      XCTAssertTrue(back.waitForExistence(timeout:10));back.tap()
      XCTAssertTrue(app.buttons["完成"].firstMatch.waitForExistence(timeout:10));app.buttons["完成"].firstMatch.tap()
      try select(live.thread,searchText:live.search)
      XCTAssertTrue(composer.waitForExistence(timeout:20))
      let current = composer.value as? String ?? ""
      if let expected = config["notificationResumeDraftSHA256"] as? String {
        let actual=SHA256.hash(data:Data(current.utf8)).map { String(format:"%02x",$0) }.joined()
        guard actual==expected else { throw NSError(domain:"EasyWorkNoticeExaminer",code:9,userInfo:[NSLocalizedDescriptionKey:"Original editable test draft changed; no input replaced"]) }
      } else {
        guard current.isEmpty else { throw NSError(domain:"EasyWorkNoticeExaminer",code:8,userInfo:[NSLocalizedDescriptionKey:"Explicit target has an unsent draft; no input changed"]) }
      }
      try replaceEasyWorkManagementText(app:app,field:composer,with:live.message)
      XCTAssertEqual(composer.value as? String,live.message)
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      easyWorkExaminerText(live.message,name:"easywork-notice-immutable-new-input",testCase:self)
      let send=app.buttons["send-message"];let enabled=XCTNSPredicateExpectation(predicate:NSPredicate(format:"isEnabled == true AND isHittable == true"),object:send)
      XCTAssertEqual(XCTWaiter.wait(for:[enabled],timeout:45),.completed);send.tap()
      easyWorkExaminerText("One explicit new input clicked once; no original input replay",name:"easywork-notice-input-clicked-once",testCase:self)
      try select(live.returnThread,searchText:live.returnSearch)
      openNotifications()
    }
    let actualNotice=try await checkpoint("original-canonical-notice-visible")
    shot("easywork-ios-original-completed-notice-settings")
    if config["notificationVerifySystemOpen"] as? Bool==true {
      let title=try XCTUnwrap(actualNotice["actualNotificationTitle"] as? String)
      try openEasyWorkActualSystemNotice(app:app,title:title)
      try await checkpoint("actual-system-default-open-exact-new-thread")
      shot("easywork-ios-real-system-default-open-original-thread")
      let destination=try XCTUnwrap(live)
      try select(destination.returnThread,searchText:destination.returnSearch)
      openNotifications()
    } else {
    try set("notification-content-preview",false,down:false)
    try await checkpoint("same-original-notice-private")
    shot("easywork-ios-original-notice-preview-disabled")
    try set("notifications-enabled",false,down:true)
    try await checkpoint("original-notice-disabled")
    try set("notifications-enabled",true,down:true)
    try await checkpoint("old-terminal-not-replayed")
    shot("easywork-ios-completed-not-replayed-on-enable")
    }
    } catch {
      primary = error
      easyWorkExaminerText(String(describing:error),name:"easywork-original-notice-primary-failure",testCase:self)
    }
    do {
    if app.alerts["通知"].buttons["好"].exists { app.alerts["通知"].buttons["好"].tap() }
    try set("notification-content-preview",true,down:false)
    try set("notification-kind-completed",false,down:true)
    try set("notifications-enabled",false,down:true)
    try await checkpoint("original-preferences-disabled-restored")
    } catch {
      easyWorkExaminerText(String(describing:error),name:"easywork-original-notice-cleanup-failure",testCase:self)
      if primary == nil { throw error }
    }
    if let primary { throw primary }
    let inputCount = config["notificationLiveInput"] == nil ? 0 : 1
    easyWorkExaminerText("Canonical completed notice presentation/privacy/master-reenable inspected by actual App UNUserNotificationCenter read-only probe; distinct new model inputs \(inputCount); physical iPhone false; Codex tests false",name:"easywork-original-notice-native-scope",testCase:self)
  }
}
