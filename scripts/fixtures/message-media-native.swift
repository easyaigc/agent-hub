  @MainActor func testMessageMediaSyntheticNative() async throws {
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["MESSAGE_MEDIA_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(raw.utf8))
    func counts() async throws -> [String: Int] {
      var request = URLRequest(url: URL(string: fixture.endpoint + "/counts")!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try JSONDecoder().decode([String: Int].self, from: data)
    }
    connectLive(fixture, supportsCreation: false)
    let before = try await counts()
    XCTAssertEqual(before["resolve"], 0); XCTAssertEqual(before["download"], 0)
    openDesktopTestConversation(fixture)
    let list = app.scrollViews["message-list"]
    func card(_ kind: String, _ label: String) -> XCUIElement {
      app.buttons.matching(identifier: "message-media-" + kind).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }
    func visible(_ el: XCUIElement) {
      for _ in 0..<10 where !el.isHittable { list.swipeUp() }
      XCTAssertTrue(el.waitForExistence(timeout: 20), app.debugDescription)
      XCTAssertTrue(el.isHittable, app.debugDescription)
    }
    let image = card("image", "图片"), video = card("video", "视频"), missing = card("image", "缺失")
    XCTAssertTrue(image.waitForExistence(timeout: 30), app.debugDescription)
    XCTAssertTrue(video.exists); XCTAssertTrue(missing.exists)
    XCTAssertEqual(app.buttons.matching(identifier: "message-media-image").count, 2)
    XCTAssertEqual(app.buttons.matching(identifier: "message-media-video").count, 1)
    let noFetch = try await counts()
    XCTAssertEqual(noFetch["resolve"], 0); XCTAssertEqual(noFetch["download"], 0)
    shot("media-cards-before-open")
    let done = app.buttons.matching(NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label == '关闭' OR label == 'Close' OR label == 'Done' OR label == '完成'")).firstMatch
    let preview = app.otherElements["QLPreviewControllerView"]
    func closePreview() {
      if done.exists && done.isHittable { done.tap() }
      else { preview.swipeDown() }
      XCTAssertFalse(preview.waitForExistence(timeout: 3) && preview.isHittable, app.debugDescription)
    }
    visible(image); image.tap()
    XCTAssertTrue(preview.waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertTrue(preview.descendants(matching: .image).firstMatch.waitForExistence(timeout: 15), app.debugDescription)
    shot("media-image-quicklook")
    let afterImage = try await counts()
    XCTAssertEqual(afterImage["imageResolve"], 1); XCTAssertEqual(afterImage["imageDownload"], 1)
    closePreview()
    XCTAssertTrue(app.images["message-media-thumbnail"].waitForExistence(timeout: 15), app.debugDescription)
    shot("media-image-thumbnail")
    visible(video); video.tap()
    XCTAssertTrue(preview.waitForExistence(timeout: 20), app.debugDescription)
    shot("media-video-quicklook")
    preview.tap()
    let play = app.buttons.matching(NSPredicate(format: "label == '播放' OR label == 'Play'")).firstMatch
    if play.waitForExistence(timeout: 5) && play.isHittable { play.tap(); sleep(1); shot("media-video-playback") }
    let afterVideo = try await counts()
    XCTAssertEqual(afterVideo["videoResolve"], 1); XCTAssertEqual(afterVideo["videoDownload"], 1)
    closePreview()
    visible(missing); missing.tap()
    let alert = app.alerts["无法打开链接"]
    XCTAssertTrue(alert.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(alert.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "fixture missing original error")).firstMatch.exists, app.debugDescription)
    shot("media-missing-error")
    alert.buttons["好"].tap()
    visible(video); video.tap()
    XCTAssertTrue(preview.waitForExistence(timeout: 20), app.debugDescription)
    shot("media-recovered-preview")
    closePreview()
    let final = try await counts()
    XCTAssertEqual(final["missingResolve"], 1)
    XCTAssertEqual(final["videoResolve"], 2)
  }
