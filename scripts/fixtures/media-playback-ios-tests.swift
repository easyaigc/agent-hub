  // These helpers are injected only into the task-owned UI-test source.
  private final class MediaFixturePinDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let host: String
    private let port: Int
    private let fingerprint: String
    init(host: String, port: Int, fingerprint: String) {
      self.host = host.lowercased(); self.port = port; self.fingerprint = fingerprint.lowercased()
    }
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
      let space = challenge.protectionSpace
      guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
            space.host.lowercased() == host, space.port == port,
            let trust = space.serverTrust,
            let cert = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first else {
        completionHandler(.cancelAuthenticationChallenge, nil); return
      }
      let digest = SHA256.hash(data: SecCertificateCopyData(cert) as Data).map { String(format: "%02x", $0) }.joined()
      guard digest == fingerprint else { completionHandler(.cancelAuthenticationChallenge, nil); return }
      completionHandler(.useCredential, URLCredential(trust: trust))
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
      completionHandler(nil)
    }
  }

  private func mediaFixtureMetadata() throws -> [String: Any] {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
  }

  private func mediaFixtureData(for request: URLRequest, fixture: LiveFixture) async throws -> (Data, URLResponse) {
    let metadata = try mediaFixtureMetadata()
    if let transport = metadata["controlTransport"] {
      guard let transport = transport as? String, transport == "mac-file-v1" else { throw URLError(.unsupportedURL) }
      return try await mediaFixtureFileControl(request, fixture: fixture, metadata: metadata)
    }
    guard let rawPin = metadata["certificateFingerprint"] else {
      return try await URLSession.shared.data(for: request)
    }
    guard let pin = rawPin as? String else { throw URLError(.serverCertificateUntrusted) }
    guard pin.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil,
          let base = URLComponents(string: fixture.endpoint), base.scheme?.lowercased() == "https",
          let host = base.host, base.user == nil, base.password == nil,
          let url = request.url, let target = URLComponents(url: url, resolvingAgainstBaseURL: false),
          target.scheme?.lowercased() == "https", target.host?.lowercased() == host.lowercased(),
          (target.port ?? 443) == (base.port ?? 443), target.user == nil, target.password == nil else {
      throw URLError(.badURL)
    }
    let delegate = MediaFixturePinDelegate(host: host, port: base.port ?? 443, fingerprint: pin)
    let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    return try await session.data(for: request)
  }

  private struct MediaFixtureControlResponse: Decodable {
    let id: String
    let caseNonce: String
    let source: String
    let status: Int
    let headers: [String: String]
    let dataBase64: String
    let dataSHA256: String
  }

  private func mediaFixtureFileControl(_ request: URLRequest, fixture: LiveFixture, metadata: [String: Any]) async throws -> (Data, URLResponse) {
    guard let nonce = metadata["caseNonce"] as? String, UUID(uuidString: nonce) != nil,
          let url = request.url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let base = URLComponents(string: fixture.endpoint), base.scheme?.lowercased() == "https",
          base.user == nil, base.password == nil, components.scheme == base.scheme,
          components.host?.lowercased() == base.host?.lowercased(), (components.port ?? 443) == (base.port ?? 443),
          components.user == nil, components.password == nil, components.fragment == nil,
          request.httpBody == nil else { throw URLError(.badURL) }
    let method = request.httpMethod ?? "GET"
    let items = components.queryItems ?? []
    let path = components.path
    let allowed: Bool
    switch (method, path) {
    case ("GET", "/counts"), ("GET", "/fixture-requests"), ("POST", "/fixture-legacy"): allowed = items.isEmpty
    case ("POST", "/fixture-forbidden"): allowed = items.count == 1 && items[0].name == "enabled" && ["0", "1"].contains(items[0].value ?? "")
    case ("POST", "/fixture-entry"): allowed = items.count == 1 && items[0].name == "case" && ["inline", "link", "attachment", "document"].contains(items[0].value ?? "")
    case ("POST", "/fixture-lease"): allowed = items.count == 1 && items[0].name == "mode" && ["short", "renew-forbidden", "renew-version"].contains(items[0].value ?? "")
    default: allowed = false
    }
    guard allowed else { throw URLError(.unsupportedURL) }
    let id = UUID().uuidString.lowercased()
    let relative = path + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
    let marker: [String: String] = ["id": id, "caseNonce": nonce, "verb": method, "path": relative]
    let markerData = try JSONSerialization.data(withJSONObject: marker, options: [.sortedKeys])
    guard let markerText = String(data: markerData, encoding: .utf8) else { throw URLError(.cannotDecodeContentData) }
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("media-fixture-" + id + ".json", isDirectory: false)
    print("MEDIA_FIXTURE_CONTROL " + markerText); fflush(stdout)
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(20))
    while clock.now < deadline {
      if FileManager.default.fileExists(atPath: file.path) {
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        guard let size = attributes[.size] as? NSNumber, size.intValue <= 2 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
        let bytes = try Data(contentsOf: file)
        guard bytes.count <= 2 * 1024 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
        guard let response = try? JSONDecoder().decode(MediaFixtureControlResponse.self, from: bytes) else {
          try await Task.sleep(for: .milliseconds(100)); continue
        }
        guard response.id == id, response.caseNonce == nonce, response.source == "host-https-v1",
              (100...599).contains(response.status), let body = Data(base64Encoded: response.dataBase64),
              body.count <= 1024 * 1024,
              SHA256.hash(data: body).map({ String(format: "%02x", $0) }).joined() == response.dataSHA256,
              let http = HTTPURLResponse(url: url, statusCode: response.status, httpVersion: "HTTP/1.1", headerFields: response.headers) else {
          throw URLError(.cannotDecodeContentData)
        }
        // Keep the unique response until the task Runner is uninstalled: CoreDevice
        // verifies its destination after writing, so immediate deletion races its receipt.
        return (body, http)
      }
      try await Task.sleep(for: .milliseconds(100))
    }
    throw URLError(.timedOut)
  }

  @MainActor private func mediaFixtureConnect(_ fixture: LiveFixture) throws {
    let metadata = try mediaFixtureMetadata()
    guard let pairingURI = metadata["pairingURI"] as? String, !pairingURI.isEmpty else {
      connectLive(fixture, supportsCreation: false); return
    }
    guard let pin = metadata["certificateFingerprint"] as? String,
          pin.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil else {
      XCTFail("Fixture certificate fingerprint missing or invalid"); throw URLError(.serverCertificateUntrusted)
    }
    if app.alerts["无法完成操作"].exists { app.alerts["无法完成操作"].buttons["好"].tap() }
    dismissLiveQuestionPresentation()
    allowAcceptanceLocalNetworkPermission()
    let clientSwitch = app.buttons["client-switch"]
    guard clientSwitch.waitForExistence(timeout: 20) else { XCTFail("Client switch unavailable"); throw URLError(.cannotConnectToHost) }
    clientSwitch.tap()
    let addClient = app.buttons["add-client"]
    for _ in 0..<6 where !addClient.isHittable { app.swipeUp() }
    guard addClient.waitForExistence(timeout: 15) else { XCTFail("Add client unavailable"); throw URLError(.cannotConnectToHost) }
    addClient.tap()
    selectConnectionMethod("扫码连接", agent: "codex")
    let field = app.textFields["codex-qr-description"]
    guard field.waitForExistence(timeout: 15) else { XCTFail("Pairing field unavailable"); throw URLError(.cannotConnectToHost) }
    field.tap(); field.typeText(pairingURI)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    let connect = app.buttons["codex-connect-qr"]
    guard connect.waitForExistence(timeout: 15) else { XCTFail("Pairing action unavailable"); throw URLError(.cannotConnectToHost) }
    connect.tap()
    allowAcceptanceLocalNetworkPermission()
    guard field.waitForNonExistence(timeout: 90) else {
      let error = app.staticTexts["client-form-error"].firstMatch
      XCTFail("Fixture pairing failed: " + (error.exists ? error.label : "connection did not complete"))
      throw URLError(.cannotConnectToHost)
    }
    dismissSystemPasswordOffer()
    guard app.buttons["new-chat"].waitForExistence(timeout: 25) else {
      XCTFail("Connected client navigation unavailable"); throw URLError(.cannotConnectToHost)
    }
  }

  // Injected only into a task-owned copy of CodexManageUITests.swift.
  @MainActor func testMediaPlaybackStreamingNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    func counters() async throws -> [String: Int] {
      var request = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + "/counts")))
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await mediaFixtureData(for: request, fixture: fixture)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try JSONDecoder().decode([String: Int].self, from: data)
    }
    func element(_ id: String) -> XCUIElement {
      app.descendants(matching: .any).matching(identifier: id).firstMatch
    }
    func require(_ id: String, timeout: TimeInterval = 30) -> XCUIElement {
      let result = element(id)
      XCTAssertTrue(result.waitForExistence(timeout: timeout), "Missing \(id): \(app.debugDescription)")
      return result
    }
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = require("message-media-video")
    let initial = try await counters()
    XCTAssertEqual(initial["mediaRequests"], 0, "Opening history must not fetch video bytes")
    XCTAssertEqual(initial["preparations"], 0, "Opening history must not prepare a snapshot")
    shot("playback-before-open")
    let tapStarted = ProcessInfo.processInfo.systemUptime
    video.tap()
    _ = require("media-player")
    let firstFrame = require("media-player-first-frame", timeout: 30)
    guard firstFrame.exists else { return }
    let clickToFirstFrame = ProcessInfo.processInfo.systemUptime - tapStarted
    let timing = XCTAttachment(string: "click-to-first-frame AX polling upper bound: \(clickToFirstFrame) seconds; includes link resolution")
    timing.name = "click-to-first-frame"
    timing.lifetime = .keepAlways
    add(timing)
    let first = try await counters()
    XCTAssertLessThanOrEqual(try XCTUnwrap(first["mediaBytes"]), 8 * 1024 * 1024)
    XCTAssertLessThan(try XCTUnwrap(first["mediaBytes"]), try XCTUnwrap(first["mediaSize"]))
    XCTAssertTrue(require("media-player-mode").label.contains("streaming"), app.debugDescription)
    shot("playback-first-frame")
    let seek = require("media-player-seek")
    let before = String(describing: seek.value)
    sleep(4)
    let after = String(describing: seek.value)
    XCTAssertNotEqual(before, after, "Playback time must advance")
    shot("playback-time-advanced")
    let beforeSeek = try await counters()
    let playPause = require("media-player-play-pause")
    playPause.tap()
    let paused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "paused"), object: element("media-player-state"))
    XCTAssertEqual(XCTWaiter.wait(for: [paused], timeout: 15), .completed, app.debugDescription)
    func playbackTime() -> (Double, Double)? {
      let label = element("media-player-time").label
      let pieces = label.components(separatedBy: " / ")
      guard pieces.count == 2 else { return nil }
      func seconds(_ value: String) -> Double? {
        let numbers = value.split(separator: ":").compactMap { Double($0) }
        guard numbers.count == value.split(separator: ":").count, !numbers.isEmpty else { return nil }
        return numbers.reduce(0) { $0 * 60 + $1 }
      }
      guard let position = seconds(pieces[0]), let duration = seconds(pieces[1]), duration > 0 else { return nil }
      return (position, duration)
    }
    var requested = 0.8
    var landing: (Double, Double)?
    for attempt in 0..<3 {
      print("MEDIA_SEEK attempt=\(attempt) before value=\(String(describing: seek.value)) frame=\(seek.frame) requestedNormalized=\(requested)")
      seek.adjust(toNormalizedSliderPosition: requested)
      print("MEDIA_SEEK attempt=\(attempt) after value=\(String(describing: seek.value)) frame=\(seek.frame)")
      let deadline = Date().addingTimeInterval(30)
      while Date() < deadline {
        if element("media-player-state").label == "paused", let current = playbackTime(), current.1 > 0 {
          landing = current
          break
        }
        sleep(1)
      }
      guard let current = landing else { break }
      let ratio = current.0 / current.1
      print("MEDIA_SEEK attempt=\(attempt) pausedPosition=\(current.0) duration=\(current.1) ratio=\(ratio)")
      if ratio >= 0.75 && ratio <= 0.88 { break }
      requested = min(0.95, max(0.05, requested + (0.8 - ratio) * 0.6))
      landing = nil
    }
    let (positionAfterSeek, duration) = try XCTUnwrap(landing, "Cannot read paused playback time after seek")
    XCTAssertGreaterThanOrEqual(positionAfterSeek / duration, 0.75)
    XCTAssertLessThanOrEqual(positionAfterSeek / duration, 0.88)
    shot("playback-seek-paused")
    playPause.tap()
    let playing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "playing"), object: element("media-player-state"))
    XCTAssertEqual(XCTWaiter.wait(for: [playing], timeout: 30), .completed, app.debugDescription)
    XCTAssertEqual(require("media-player-state").label, "playing")
    sleep(4)
    let (positionAfterPlaying, _) = try XCTUnwrap(playbackTime())
    XCTAssertGreaterThan(positionAfterPlaying, positionAfterSeek, "Playback must advance after seek")
    XCTAssertEqual(require("media-player-state").label, "playing")
    shot("playback-seek-80")
    let afterSeek = try await counters()
    XCTAssertGreaterThan(try XCTUnwrap(afterSeek["seekRanges"]), try XCTUnwrap(beforeSeek["seekRanges"]), "Expected a new direct range near 80% after seek")
    let seekPosition = String(describing: seek.value)
    require("media-player-fullscreen").tap()
    _ = require("media-player-exit-fullscreen")
    shot("playback-fullscreen")
    require("media-player-exit-fullscreen").tap()
    XCTAssertTrue(element("media-player-exit-fullscreen").waitForNonExistence(timeout: 20), "Fullscreen transition did not finish")
    let inlineFullscreen = app.buttons["media-player-fullscreen"]
    let sidebar = app.buttons["open-sidebar"]
    for target in [inlineFullscreen, sidebar] {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isHittable == true"), object: target)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed, "Inline/root controls are not yet interactive: \(app.debugDescription)")
    }
    let inlineSurface = require("media-player")
    XCTAssertNotEqual(String(describing: seek.value), before, "Exiting fullscreen must retain playback progress; previous \(seekPosition)")
    shot("playback-returned-inline")
    func rawPixels() throws -> (Int, Int, Int, Data) {
      let image = inlineSurface.screenshot().image
      let cgImage = try XCTUnwrap(image.cgImage, "Missing AVPlayerLayer screenshot image")
      let provider = try XCTUnwrap(cgImage.dataProvider, "Missing AVPlayerLayer pixel provider")
      return (cgImage.width, cgImage.height, cgImage.bytesPerRow, try XCTUnwrap(provider.data as Data?, "Missing AVPlayerLayer pixel data"))
    }
    let firstPixels = try rawPixels()
    sleep(2)
    let secondPixels = try rawPixels()
    XCTAssertEqual(firstPixels.0, secondPixels.0, "Pixel widths differ during fullscreen transition")
    XCTAssertEqual(firstPixels.1, secondPixels.1, "Pixel heights differ during fullscreen transition")
    XCTAssertEqual(firstPixels.2, secondPixels.2, "Pixel row strides differ during fullscreen transition")
    XCTAssertNotEqual(firstPixels.3, secondPixels.3, "Inline AVPlayerLayer pixels must change after returning from fullscreen")
    shot("playback-returned-inline-advanced")
    require("media-player-close").tap()
    sleep(3)
    let closed = try await counters()
    XCTAssertGreaterThan(try XCTUnwrap(closed["cancellations"]), 0)
    XCTAssertEqual(closed["activeMediaResponses"], 0)
    XCTAssertFalse(element("media-player").exists, "Late completion reopened the player")
    shot("playback-closed")
  }

  // Warm-cache regression: the cold streaming case separately requires new Range reads.
  @MainActor func testMediaPlaybackCachedSeekNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    func time() throws -> (Double, Double) {
      let values = element("media-player-time").label.components(separatedBy: " / ")
      XCTAssertEqual(values.count, 2)
      func seconds(_ text: String) throws -> Double {
        let parts = text.split(separator: ":")
        let numbers = parts.compactMap { Double($0) }
        XCTAssertEqual(parts.count, numbers.count)
        guard !numbers.isEmpty, parts.count == numbers.count else { throw URLError(.cannotDecodeContentData) }
        return numbers.reduce(0) { $0 * 60 + $1 }
      }
      guard values.count == 2 else { throw URLError(.cannotDecodeContentData) }
      return (try seconds(values[0]), try seconds(values[1]))
    }
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = element("message-media-video")
    XCTAssertTrue(video.waitForExistence(timeout: 30)); video.tap()
    XCTAssertTrue(element("media-player-first-frame").waitForExistence(timeout: 30), app.debugDescription)
    XCTAssertTrue(element("media-player-mode").label.contains("streaming"))
    let playPause = element("media-player-play-pause")
    playPause.tap()
    let paused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "paused"), object: element("media-player-state"))
    XCTAssertEqual(XCTWaiter.wait(for: [paused], timeout: 15), .completed)
    let slider = element("media-player-seek")
    XCTAssertTrue(slider.waitForExistence(timeout: 10))
    slider.adjust(toNormalizedSliderPosition: 0.8)
    let seekDeadline = Date().addingTimeInterval(30)
    var landing: (Double, Double)?
    while Date() < seekDeadline {
      if element("media-player-state").label == "paused" {
        let current = try time()
        if current.1 > 0, current.0 / current.1 >= 0.75, current.0 / current.1 <= 0.88 { landing = current; break }
      }
      sleep(1)
    }
    let landed = try XCTUnwrap(landing, "Cached seek must settle within 75%-88%")
    shot("playback-cached-seek-paused")
    playPause.tap()
    let playing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "playing"), object: element("media-player-state"))
    XCTAssertEqual(XCTWaiter.wait(for: [playing], timeout: 30), .completed)
    sleep(4)
    XCTAssertGreaterThan(try time().0, landed.0, "Warm-cache playback must advance after seek")
    XCTAssertEqual(element("media-player-state").label, "playing")
    shot("playback-cached-seek-advanced")
    element("media-player-close").tap()
    XCTAssertTrue(element("media-player").waitForNonExistence(timeout: 10))
  }

  @MainActor func testMediaPlaybackLegacyNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    var request = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + "/fixture-legacy")))
    request.httpMethod = "POST"
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (_, response) = try await mediaFixtureData(for: request, fixture: fixture)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = app.buttons["message-media-video"]
    XCTAssertTrue(video.waitForExistence(timeout: 30))
    video.tap()
    let frame = app.descendants(matching: .any)["media-player-first-frame"].firstMatch
    XCTAssertTrue(frame.waitForExistence(timeout: 30), app.debugDescription)
    guard frame.exists else { return }
    let mode = app.descendants(matching: .any)["media-player-mode"].firstMatch
    XCTAssertTrue(mode.label.contains("downloaded"), app.debugDescription)
    shot("playback-legacy-downloaded")
    app.buttons["media-player-close"].tap()
  }

  @MainActor func testMediaPlaybackForbiddenRetryNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    func request(_ path: String, method: String = "GET") async throws -> Data {
      var req = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + path)))
      req.httpMethod = method
      req.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await mediaFixtureData(for: req, fixture: fixture)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return data
    }
    func counters() async throws -> [String: Int] {
      try JSONDecoder().decode([String: Int].self, from: await request("/counts"))
    }
    _ = try await request("/fixture-forbidden?enabled=1", method: "POST")
    let initial = try await counters()
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = app.buttons["message-media-video"]
    XCTAssertTrue(video.waitForExistence(timeout: 30)); video.tap()
    let error = app.descendants(matching: .any)["media-player-error"].firstMatch
    XCTAssertTrue(error.waitForExistence(timeout: 45), app.debugDescription)
    let failed = try await counters()
    XCTAssertGreaterThan(try XCTUnwrap(failed["forbiddenResponses"]), 0)
    XCTAssertEqual(failed["fullRequests"], initial["fullRequests"], "403 must not silently trigger full download")
    shot("playback-forbidden")
    _ = try await request("/fixture-forbidden?enabled=0", method: "POST")
    let retry = app.buttons["media-player-retry"]
    XCTAssertTrue(retry.waitForExistence(timeout: 10)); retry.tap()
    let frame = app.descendants(matching: .any)["media-player-first-frame"].firstMatch
    XCTAssertTrue(frame.waitForExistence(timeout: 45), app.debugDescription)
    let recovered = try await counters()
    XCTAssertGreaterThan(try XCTUnwrap(recovered["rangeRequests"]), try XCTUnwrap(failed["rangeRequests"]))
    XCTAssertEqual(recovered["fullRequests"], initial["fullRequests"])
    shot("playback-retry-first-frame")
    let state = app.descendants(matching: .any)["media-player-state"].firstMatch
    let clock = app.descendants(matching: .any)["media-player-time"].firstMatch
    let beforeWait = clock.label
    sleep(25)
    XCTAssertFalse(error.exists, "Retry must not trip a stale first-frame timeout after 20 seconds")
    XCTAssertEqual(state.label, "playing", app.debugDescription)
    XCTAssertNotEqual(clock.label, beforeWait, "Retry playback must continue advancing after 20 seconds")
    shot("playback-retry-after-25-seconds")
    app.buttons["media-player-close"].tap()
  }

  @MainActor private func exerciseMediaEntry(_ entry: String) async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    var req = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + "/fixture-entry?case=" + entry)))
    req.httpMethod = "POST"
    req.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (entryData, response) = try await mediaFixtureData(for: req, fixture: fixture)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    if entry == "attachment" {
      let fixtureState = try XCTUnwrap(JSONSerialization.jsonObject(with: entryData) as? [String: Any])
      XCTAssertEqual(fixtureState["mediaType"] as? String, "application/octet-stream")
    }
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    func tap(_ element: XCUIElement, _ description: String) {
      XCTAssertTrue(element.waitForExistence(timeout: 30), "Missing \(description): \(app.debugDescription)")
      guard element.exists else { return }
      element.tap()
    }
    if entry == "link" {
      tap(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Ordinary video link")).firstMatch, "ordinary video link")
    } else if entry == "attachment" {
      tap(app.buttons["play-resource-video.mp4"], "video attachment")
    } else if entry == "document" {
      tap(app.buttons["download-document.md"], "document download")
      tap(app.buttons["preview-document.md"], "document preview")
      tap(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Document video")).firstMatch, "document video link")
    }
    let frame = app.descendants(matching: .any)["media-player-first-frame"].firstMatch
    XCTAssertTrue(frame.waitForExistence(timeout: 45), "No first frame for \(entry): \(app.debugDescription)")
    XCTAssertTrue(app.descendants(matching: .any)["media-player-mode"].firstMatch.label.contains("streaming"))
    shot("playback-entry-\(entry)-first-frame")
    app.buttons["media-player-close"].tap()
  }

  @MainActor func testMediaPlaybackLinkEntryNative() async throws { try await exerciseMediaEntry("link") }
  @MainActor func testMediaPlaybackAttachmentEntryNative() async throws { try await exerciseMediaEntry("attachment") }
  @MainActor func testMediaPlaybackDocumentEntryNative() async throws { try await exerciseMediaEntry("document") }

  @MainActor func testMediaPlaybackPortraitNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    var req = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + "/fixture-entry?case=link")))
    req.httpMethod = "POST"
    req.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (_, response) = try await mediaFixtureData(for: req, fixture: fixture)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let link = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Ordinary video link")).firstMatch
    XCTAssertTrue(link.waitForExistence(timeout: 30)); link.tap()
    let frame = app.descendants(matching: .any)["media-player-first-frame"].firstMatch
    XCTAssertTrue(frame.waitForExistence(timeout: 45), app.debugDescription)
    XCTAssertTrue(app.buttons["media-player-exit-fullscreen"].waitForExistence(timeout: 20))
    let surface = app.descendants(matching: .any)["media-player"].firstMatch
    XCTAssertTrue(surface.waitForExistence(timeout: 10))
    let surfaceFrame = surface.frame, screenFrame = app.frame
    XCTAssertGreaterThan(surfaceFrame.height, screenFrame.height * 0.55, "Portrait fullscreen surface must use available height")
    let image = try XCTUnwrap(surface.screenshot().image.cgImage)
    let pixels = try XCTUnwrap(image.dataProvider?.data as Data?)
    let distinct = Set(stride(from: 0, to: pixels.count, by: max(1, pixels.count / 4096)).map { pixels[$0] })
    XCTAssertGreaterThan(distinct.count, 8, "Portrait surface screenshot should contain visible synthetic video")
    let time = app.descendants(matching: .any)["media-player-time"].firstMatch
    let before = time.label
    sleep(4)
    XCTAssertNotEqual(time.label, before, "Portrait playback must advance")
    print("PORTRAIT_SURFACE frame=\(surfaceFrame) screen=\(screenFrame) distinctSampleBytes=\(distinct.count)")
    shot("playback-portrait-fullscreen")
    app.buttons["media-player-close"].tap()
  }

  @MainActor func testMediaPlaybackContextExitNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    func counts() async throws -> [String: Int] {
      var request = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + "/counts")))
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, _) = try await mediaFixtureData(for: request, fixture: fixture)
      return try JSONDecoder().decode([String: Int].self, from: data)
    }
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = app.buttons["message-media-video"]
    XCTAssertTrue(video.waitForExistence(timeout: 30)); video.tap()
    let frame = app.descendants(matching: .any)["media-player-first-frame"].firstMatch
    XCTAssertTrue(frame.waitForExistence(timeout: 45))
    let before = try await counts()
    app.buttons["open-sidebar"].tap()
    let clearSearch = app.buttons["清除搜索"]
    if clearSearch.waitForExistence(timeout: 2) { clearSearch.tap() }
    let other = app.buttons["recent-thread-playback-other-thread"]
    XCTAssertTrue(other.waitForExistence(timeout: 20), app.debugDescription)
    guard other.exists else { return }
    other.tap()
    XCTAssertTrue(app.staticTexts["PLAYBACK_OTHER_CONTEXT"].waitForExistence(timeout: 20), app.debugDescription)
    sleep(3)
    let after = try await counts()
    XCTAssertGreaterThan(try XCTUnwrap(after["cancellations"]), try XCTUnwrap(before["cancellations"]))
    XCTAssertEqual(after["activeMediaResponses"], 0)
    XCTAssertFalse(app.descendants(matching: .any)["media-player"].firstMatch.exists)
    shot("playback-context-stopped")
  }

  @MainActor func testMediaPlaybackFullDownloadShareNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let data = Data(raw.utf8)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    let expectedHash = try XCTUnwrap(metadata["expectedMediaSha256"] as? String)
    let expectedSize = try XCTUnwrap(metadata["expectedMediaSize"] as? Int)
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = app.buttons["message-media-video"]
    XCTAssertTrue(video.waitForExistence(timeout: 30)); video.tap()
    XCTAssertTrue(app.descendants(matching: .any)["media-player-first-frame"].firstMatch.waitForExistence(timeout: 45))
    let download = app.buttons["media-player-download"]
    XCTAssertTrue(download.waitForExistence(timeout: 10)); download.tap()
    let share = app.buttons["media-player-share"]
    XCTAssertTrue(share.waitForExistence(timeout: 120), app.debugDescription)
    XCTAssertFalse(app.descendants(matching: .any)["media-player-download-error"].firstMatch.exists)
    var request = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + "/fixture-requests")))
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (responseData, _) = try await mediaFixtureData(for: request, fixture: fixture)
    let rows = try XCTUnwrap(JSONSerialization.jsonObject(with: responseData) as? [[String: Any]])
    XCTAssertTrue(rows.contains { ($0["status"] as? Int) == 200 && ($0["range"] is NSNull) && ($0["bytes"] as? Int) == expectedSize && ($0["sha256"] as? String) == expectedHash }, "Expected complete 200 response with matching SHA-256")
    share.tap()
    let activity = app.otherElements["ActivityListView"]
    let copy = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "拷贝", "Copy")).firstMatch
    XCTAssertTrue(activity.waitForExistence(timeout: 20) || copy.waitForExistence(timeout: 5), "Native share sheet missing: \(app.debugDescription)")
    shot("playback-complete-share-sheet")
  }

  @MainActor private func exerciseMediaLease(_ mode: String) async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MEDIA_PLAYBACK_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    func request(_ path: String, method: String = "GET") async throws -> Data {
      var req = URLRequest(url: try XCTUnwrap(URL(string: fixture.endpoint + path)))
      req.httpMethod = method
      req.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await mediaFixtureData(for: req, fixture: fixture)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return data
    }
    func rows() async throws -> [[String: Any]] {
      let data = try await request("/fixture-requests")
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
    }
    func counts() async throws -> [String: Int] {
      try JSONDecoder().decode([String: Int].self, from: await request("/counts"))
    }
    _ = try await request("/fixture-lease?mode=" + mode, method: "POST")
    try mediaFixtureConnect(fixture)
    openDesktopTestConversation(fixture)
    let video = app.buttons["message-media-video"]
    XCTAssertTrue(video.waitForExistence(timeout: 30)); video.tap()
    let frame = app.descendants(matching: .any)["media-player-first-frame"].firstMatch
    XCTAssertTrue(frame.waitForExistence(timeout: 45), app.debugDescription)
    let time = app.descendants(matching: .any)["media-player-time"].firstMatch
    let initialTime = time.label
    sleep(16)
    if mode == "short" {
      XCTAssertNotEqual(time.label, initialTime, "Playback must advance through the shortened lease")
    }
    let before = try await counts()
    let slider = app.sliders["media-player-seek"]
    let error = app.descendants(matching: .any)["media-player-error"].firstMatch
    if mode == "short" || !error.exists {
      XCTAssertTrue(slider.waitForExistence(timeout: 10)); slider.adjust(toNormalizedSliderPosition: 0.7)
    }
    if mode == "short" {
      let deadline = Date().addingTimeInterval(30)
      while Date() < deadline {
        let current = try await counts()
        if (current["seekRanges"] ?? 0) > (before["seekRanges"] ?? 0) { break }
        sleep(1)
      }
      let after = try await counts()
      XCTAssertGreaterThan(try XCTUnwrap(after["seekRanges"]), try XCTUnwrap(before["seekRanges"]))
      let state = app.descendants(matching: .any)["media-player-state"].firstMatch
      let playing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "playing"), object: state)
      XCTAssertEqual(XCTWaiter.wait(for: [playing], timeout: 30), .completed, app.debugDescription)
      let afterSeek = time.label
      sleep(4)
      XCTAssertNotEqual(time.label, afterSeek, "Playback must continue after renewal and uncached seek")
      shot("playback-lease-renewed")
    } else {
      XCTAssertTrue(error.waitForExistence(timeout: 45), "Renewal failure must surface: \(app.debugDescription)")
      shot("playback-lease-\(mode)-failed")
    }
    let readRows = try await rows()
    let ready = readRows.filter { ($0["path"] as? String) == "/v1/attachments/resolve" && ($0["method"] as? String) == "GET" && ($0["phase"] as? String) == "ready" }
    let ids = Set(ready.compactMap { $0["readId"] as? String })
    if mode == "short" {
      XCTAssertGreaterThanOrEqual(ids.count, 2, "Expected at least two distinct ready read IDs")
      let firstId = try XCTUnwrap(ready.first?["readId"] as? String)
      let deleteIndex = try XCTUnwrap(readRows.firstIndex { ($0["method"] as? String) == "DELETE" && ($0["readId"] as? String) == firstId && ($0["status"] as? Int) == 200 })
      let secondIndex = try XCTUnwrap(readRows.firstIndex { ($0["path"] as? String) == "/v1/attachments/resolve" && ($0["method"] as? String) == "GET" && ($0["readId"] as? String) != nil && ($0["readId"] as? String) != firstId })
      XCTAssertLessThan(deleteIndex, secondIndex, "Old snapshot must be released before replacement preparation")
    } else {
      XCTAssertTrue(readRows.contains { ($0["injected"] as? String) == mode })
    }
    let beforeClose = try await counts()
    XCTAssertEqual(beforeClose["fullRequests"], 0, "Renewal must not fall back to full download")
    app.buttons["media-player-close"].tap()
    sleep(3)
    let afterClose = try await counts()
    XCTAssertEqual(afterClose["activeMediaResponses"], 0)
    if mode == "short" {
      let finalRows = try await rows()
      for id in ids {
        XCTAssertTrue(finalRows.contains { ($0["method"] as? String) == "DELETE" && ($0["readId"] as? String) == id && ($0["status"] as? Int) == 200 }, "Missing successful cancellation of \(id)")
      }
    }
    XCTAssertFalse(app.descendants(matching: .any)["media-player"].firstMatch.exists)
  }

  @MainActor func testMediaPlaybackLeaseNative() async throws { try await exerciseMediaLease("short") }
  @MainActor func testMediaPlaybackLeaseForbiddenNative() async throws { try await exerciseMediaLease("renew-forbidden") }
  @MainActor func testMediaPlaybackLeaseVersionNative() async throws { try await exerciseMediaLease("renew-version") }
