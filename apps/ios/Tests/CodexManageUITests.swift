import XCTest
import CryptoKit
import Network
import Vision
import Darwin

extension CodexManageUITests {
  @MainActor private func controlledFileShare(openIn: Bool = false) -> XCUIApplication {
    if ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_SOURCE_BUNDLE_ID"] == "com.easyai.agenthub.externalfilesource.acceptance" {
      let source = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.externalfilesource.acceptance")
      source.launchChineseTest()
      XCTAssertEqual(source.state, .runningForeground)
      let action = source.buttons[openIn ? "fixture-open-file" : "fixture-share-file"]
      XCTAssertTrue(action.waitForExistence(timeout: 10))
      action.tap()
      return source
    }
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-share-file").tap()
    return app
  }

  @MainActor func testQuickAttachmentOpenIn() throws {
    guard ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_BUNDLE_ID"] == "com.easyai.agenthub.externalattachments.acceptance" else {
      throw XCTSkip("Requires the uniquely named external file acceptance App")
    }
    let sharingApp = controlledFileShare(openIn: true)
    shot("external-file-open-in-menu", fullScreen: true)
    let expand = sharingApp.cells.matching(NSPredicate(format: "label IN %@", ["查看更多", "Show More"])).firstMatch
    if expand.exists {
      let frame = expand.frame
      XCTAssertTrue(sharingApp.frame.contains(CGPoint(x: frame.midX, y: frame.midY)))
      // The compact system popover reports a clipped remote accessibility frame on iOS 27.
      sharingApp.coordinate(withNormalizedOffset: .zero)
        .withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
    }
    let target = sharingApp.staticTexts["Agent Hub External DEV"].firstMatch
    let more = sharingApp.cells.matching(NSPredicate(format: "label IN %@", ["更多", "More"])).firstMatch
    let row = sharingApp.scrollViews.containing(.cell, identifier: "shareCell").firstMatch
    for _ in 0..<8 where !target.isHittable && !more.isHittable {
      if row.exists, row.frame.intersects(sharingApp.frame) {
        let frame = row.frame.intersection(sharingApp.frame)
        let origin = sharingApp.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: frame.maxX - 20, dy: frame.midY))
          .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: frame.minX + 20, dy: frame.midY)))
      } else { sharingApp.swipeUp() }
    }
    if !target.isHittable, more.isHittable { more.tap() }
    for _ in 0..<5 where !target.isHittable {
      if sharingApp.tables["tableView"].exists { sharingApp.tables["tableView"].swipeUp() }
      else { sharingApp.swipeUp() }
    }
    XCTAssertTrue(target.waitForExistence(timeout: 15), sharingApp.debugDescription)
    XCTAssertTrue(target.isHittable, sharingApp.debugDescription)
    XCTAssertEqual(sharingApp.state, .runningForeground)
    shot("external-file-open-in-target", fullScreen: true)
    target.tap()
    let add = app.buttons["external-attachment-add"]
    XCTAssertTrue(add.waitForExistence(timeout: 25), app.debugDescription)
    XCTAssertEqual(app.state, .runningForeground)
    XCTAssertTrue(app.staticTexts["qa-wechat.zip"].exists)
    add.tap()
    XCTAssertTrue(add.waitForNonExistence(timeout: 20), app.debugDescription)
    XCTAssertTrue(app.staticTexts["qa-wechat.zip"].firstMatch.waitForExistence(timeout: 15))
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.exists)
    XCTAssertTrue(app.buttons["attachment-menu"].isHittable)
    let rendered = expectation(description: "System file-open transition settles before full-screen capture")
    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { rendered.fulfill() }
    wait(for: [rendered], timeout: 5)
    shot("external-file-open-in-draft", fullScreen: true)
  }

  @MainActor func testQuickAttachmentFilePaste() throws {
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-file-providers").tap()
    XCTAssertTrue(app.staticTexts["file-providers-passed"].waitForExistence(timeout: 25), app.debugDescription)
    for (index, representation) in ["url", "data"].enumerated() {
      if representation == "url", ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_SOURCE_BUNDLE_ID"] == "com.easyai.agenthub.externalfilesource.acceptance" {
        quickAttachmentClosePanel()
        let source = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.externalfilesource.acceptance")
        source.launchChineseTest()
        XCTAssertTrue(source.buttons["fixture-copy-file"].waitForExistence(timeout: 10))
        source.buttons["fixture-copy-file"].tap()
        XCTAssertTrue(source.staticTexts["copied"].waitForExistence(timeout: 10))
        app.activate()
        quickAttachmentPanel()
      } else {
        quickAttachmentControl("quick-attachment-test-copy-file-" + representation).tap()
        XCTAssertTrue(app.staticTexts["file-" + representation + "-copied"].waitForExistence(timeout: 10))
      }
      let paste = quickAttachmentControl("attachment-paste")
      XCTAssertTrue(paste.isEnabled, app.debugDescription)
      paste.tap()
      XCTAssertTrue(app.staticTexts["qa-wechat.zip"].waitForExistence(timeout: 20), app.debugDescription)
      XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.exists)
      XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "qa-wechat.zip")).count, index + 1)
      shot("external-file-paste-" + representation)
      quickAttachmentPanel()
    }
    quickAttachmentControl("quick-attachment-test-zip-bytes").tap()
    XCTAssertTrue(app.staticTexts["zip-bytes-passed"].waitForExistence(timeout: 15), app.debugDescription)
    quickAttachmentControl("quick-attachment-test-copy").tap()
    quickAttachmentControl("attachment-paste").tap()
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 20), app.debugDescription)
    shot("external-file-image-paste-regression")
  }

  @MainActor func testQuickAttachmentExternalShareAndRecovery() throws {
    guard ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_BUNDLE_ID"] == "com.easyai.agenthub.externalattachments.acceptance" else {
      throw XCTSkip("Requires the uniquely named external file acceptance extension")
    }
    let sharingApp = controlledFileShare()
    let action = sharingApp.cells.matching(NSPredicate(format: "label IN %@", ["添加到 Agent Hub External DEV", "Add to Agent Hub External DEV"])).firstMatch
    for _ in 0..<6 where !action.isHittable { sharingApp.swipeUp() }
    XCTAssertTrue(action.waitForExistence(timeout: 15), sharingApp.debugDescription)
    XCTAssertTrue(action.isHittable, sharingApp.debugDescription)
    shot("external-file-system-action", fullScreen: true)
    XCTAssertEqual(sharingApp.state, .runningForeground, "The controlled source App must own the foreground before input")
    action.tap()
    let save = sharingApp.buttons["external-action-save"]
    let loaded = save.waitForExistence(timeout: 15)
    shot("external-file-action-opened", fullScreen: true)
    XCTAssertTrue(loaded, sharingApp.debugDescription)
    save.tap()
    let done = sharingApp.buttons.matching(NSPredicate(format: "label IN %@", ["完成", "Done"])).firstMatch
    XCTAssertTrue(done.waitForExistence(timeout: 20), sharingApp.debugDescription)
    shot("external-file-action-saved", fullScreen: true)
    done.tap()
    if ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_SOURCE_BUNDLE_ID"] != nil { sharingApp.terminate() }
    app.activate()
    XCTAssertTrue(app.buttons["external-attachment-add"].waitForExistence(timeout: 20), app.debugDescription)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing", "-quick-attachments-acceptance"]
    app.launchChineseTest()
    app.activate()
    let add = app.buttons["external-attachment-add"]
    XCTAssertTrue(add.waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertTrue(app.staticTexts["qa-wechat.zip"].exists)
    shot("external-file-inbox-after-relaunch")
    add.tap()
    XCTAssertTrue(add.waitForNonExistence(timeout: 20), app.debugDescription)
    XCTAssertTrue(app.staticTexts["qa-wechat.zip"].exists, app.debugDescription)
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.exists)
    app.terminate(); app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["qa-wechat.zip"].waitForExistence(timeout: 20))
    XCTAssertFalse(app.buttons["external-attachment-add"].exists)
    XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "qa-wechat.zip")).count, 1)
    shot("external-file-draft-durable-no-duplicate")
  }

  @MainActor private func quickAttachmentControl(_ id: String) -> XCUIElement {
    XCTAssertEqual(app.state, .runningForeground, "The independent attachment App must own the foreground before input")
    let button = app.buttons[id]
    func fullyVisible() -> Bool {
      guard button.exists, button.isHittable else { return false }
      let frame = button.frame
      var viewport = app.collectionViews.firstMatch.exists ? app.collectionViews.firstMatch.frame : app.frame
      let bar = app.navigationBars.firstMatch
      if bar.exists {
        let top = max(viewport.minY, bar.frame.maxY)
        viewport = CGRect(x: viewport.minX, y: top, width: viewport.width, height: max(0, viewport.maxY - top))
      }
      let footer = app.buttons["attachment-add-selected"]
      if footer.exists, id != "attachment-add-selected", footer.frame.minY > viewport.minY {
        viewport.size.height = min(viewport.height, max(0, footer.frame.minY - 12 - viewport.minY))
      }
      let visible = frame.intersection(viewport).intersection(app.frame)
      return visible.height >= min(44, frame.height) * 0.9 && visible.width >= min(44, frame.width) * 0.9
    }
    for step in 0..<12 {
      if fullyVisible() { break }
      let above = button.exists ? button.frame.maxY < app.frame.midY : step >= 4
      if app.collectionViews.firstMatch.exists {
        if above { app.collectionViews.firstMatch.swipeDown() } else { app.collectionViews.firstMatch.swipeUp() }
      } else if app.tables.firstMatch.exists {
        if above { app.tables.firstMatch.swipeDown() } else { app.tables.firstMatch.swipeUp() }
      } else { if above { app.swipeDown() } else { app.swipeUp() } }
    }
    XCTAssertTrue(button.waitForExistence(timeout: 10), app.debugDescription)
    XCTAssertTrue(fullyVisible(), app.debugDescription)
    return button
  }
  @MainActor private func quickAttachmentPanel() {
    XCTAssertEqual(app.state, .runningForeground, "The independent attachment App must own the foreground before input")
    XCTAssertTrue(app.buttons["attachment-menu"].waitForExistence(timeout: 15))
    app.buttons["attachment-menu"].tap()
    XCTAssertTrue(app.navigationBars["添加附件"].waitForExistence(timeout: 10), app.debugDescription)
    quickAttachmentAllowPermission()
    let bar = app.navigationBars["添加附件"]
    if bar.frame.minY > app.frame.height * 0.3 {
      bar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0))
        .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)))
    }
  }
  @MainActor private func quickAttachmentClosePanel() {
    app.navigationBars["添加附件"].buttons["关闭"].tap()
    XCTAssertTrue(app.buttons["attachment-menu"].waitForExistence(timeout: 5))
  }
  @MainActor private func quickAttachmentAllowPermission(deletingOwnTestPhotos: Bool = false) {
    let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    for container in [app!, system] {
      let alert = container.alerts.firstMatch
      if alert.waitForExistence(timeout: 2) {
        if deletingOwnTestPhotos {
          let remove = alert.buttons.matching(NSPredicate(format: "label IN %@", ["删除", "Delete", "删除照片", "Delete Photos"])).firstMatch
          if remove.exists { remove.tap(); continue }
        }
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@ OR label CONTAINS %@", ["允许", "Allow", "好", "OK", "Allow Full Access"], "所有照片")).firstMatch
        if allow.exists { allow.tap() }
        else {
          let full = alert.buttons.matching(NSPredicate(format: "label CONTAINS %@", "完全访问")).firstMatch
          if full.exists { full.tap() }
        }
      }
    }
  }
  @MainActor func testQuickAttachmentLocalDraftAndRecovery() async throws {
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-boundaries").tap()
    XCTAssertTrue(app.staticTexts["local-boundaries-passed"].waitForExistence(timeout: 20), app.debugDescription)
    quickAttachmentControl("quick-attachment-test-files").tap()
    XCTAssertTrue(app.staticTexts["files-ready"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.switches["recent-attachment-reminder"].exists)
    quickAttachmentControl("quick-attachment-test-files").tap()
    XCTAssertTrue(app.staticTexts["baseline-ready"].waitForExistence(timeout: 15), app.debugDescription)
    quickAttachmentControl("quick-attachment-test-photo").tap()
    XCTAssertTrue(app.staticTexts["photo-added"].waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(app.staticTexts["owned-photo-suggestion"].waitForExistence(timeout: 15), app.debugDescription)
    quickAttachmentClosePanel()
    let add = app.buttons["recent-attachment-add"]
    XCTAssertTrue(add.waitForExistence(timeout: 15), app.debugDescription)
    shot("quick-attachment-recent-old-photo")
    add.tap()
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "上传附件")).firstMatch.exists)
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-recovery").tap()
    XCTAssertTrue(app.staticTexts["recovery-prepared"].waitForExistence(timeout: 15))
    quickAttachmentClosePanel()
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing", "-quick-attachments-acceptance"]
    app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["recovered-local.txt"].waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertGreaterThanOrEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "已添加 · 待发送")).count, 2)
    shot("quick-attachment-recovered-local-only")
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-cleanup").tap()
    quickAttachmentAllowPermission(deletingOwnTestPhotos: true)
    _ = quickAttachmentControl("quick-attachment-test-files")
    XCTAssertTrue(app.staticTexts["photos-cleaned"].waitForExistence(timeout: 15), app.debugDescription)
    quickAttachmentClosePanel()
  }

  @MainActor private func setComposerPlanMode(_ enabled: Bool) {
    let toggle = app.buttons["plan-mode-toggle"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 10), app.debugDescription)
    let expected = enabled ? "已开启" : "已关闭"
    if toggle.value as? String != expected { toggle.tap() }
    XCTAssertEqual(toggle.value as? String, expected, app.debugDescription)
  }

  @MainActor func testQuickAttachmentRecentCarouselAndPlanToggle() throws {
    app.resetAuthorizationStatus(for: .photos)
    app.terminate()
    app.launchChineseTest()
    let toggle = app.buttons["plan-mode-toggle"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 15))
    XCTAssertEqual(toggle.value as? String, "已关闭")
    assertComposerToolbarFits()
    setComposerPlanMode(true)
    assertComposerToolbarFits()
    setComposerPlanMode(false)
    quickAttachmentPanel()
    XCTAssertFalse(app.switches["recent-attachment-reminder"].exists)
    XCTAssertFalse(app.buttons["recent-attachments-toggle"].exists)
    XCTAssertFalse(app.buttons["select-plan-mode"].exists)
    quickAttachmentControl("quick-attachment-test-photo-strip").tap()
    XCTAssertTrue(app.staticTexts["photo-strip-ready"].waitForExistence(timeout: 30), app.debugDescription)
    let owned = app.staticTexts["quick-attachment-owned-photos"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      owned.label.components(separatedBy: "|").count >= 6
    }, object: owned)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed)
    let ids = owned.label.components(separatedBy: "|")
    XCTAssertGreaterThanOrEqual(ids.count, 6)
    let first = app.buttons["recent-photo-" + ids[0]]
    for _ in 0..<8 where !first.isHittable { app.swipeUp() }
    XCTAssertTrue(first.isHittable)
    let strip = app.scrollViews["recent-attachments-strip"]
    XCTAssertTrue(strip.exists)
    func visiblePhotos() -> [String] {
      ids.filter {
        let button = app.buttons["recent-photo-" + $0]
        guard button.exists else { return false }
        let frame = button.frame, visible = frame.intersection(strip.frame).intersection(app.frame)
        return visible.width >= frame.width * 0.9 && visible.height >= frame.height * 0.9 && button.isHittable
      }
    }
    let initiallyVisible = visiblePhotos()
    XCTAssertGreaterThanOrEqual(initiallyVisible.count, 3, app.debugDescription)
    shot("quick-attachment-default-photo-carousel")
    strip.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).press(forDuration: 0.1,
      thenDragTo: strip.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.5)),
      withVelocity: .slow, thenHoldForDuration: 0.2)
    let laterVisible = visiblePhotos()
    XCTAssertNotEqual(initiallyVisible, laterVisible, app.debugDescription)
    shot("quick-attachment-scrolled-photo-carousel")
    let newlyVisible = try XCTUnwrap(laterVisible.first { !initiallyVisible.contains($0) })
    XCTAssertTrue(app.buttons["recent-photo-" + newlyVisible].isHittable)
    for _ in 0..<8 where !first.isHittable { strip.swipeRight() }
    XCTAssertTrue(first.isHittable)
    first.tap()
    XCTAssertTrue(app.buttons["attachment-preview-select"].waitForExistence(timeout: 15))
    app.buttons["attachment-preview-select"].tap()
    app.buttons["attachment-preview-add-selected"].tap()
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 15))
    setComposerPlanMode(true)
    shot("quick-attachment-plan-toggle-after-permission")
    setComposerPlanMode(false)
    quickAttachmentPanel()
    XCTAssertFalse(app.buttons["recent-attachments-toggle"].exists)
    quickAttachmentControl("quick-attachment-test-cleanup").tap()
    quickAttachmentAllowPermission(deletingOwnTestPhotos: true)
    _ = quickAttachmentControl("quick-attachment-test-files")
    XCTAssertTrue(app.staticTexts["photos-cleaned"].waitForExistence(timeout: 15))
    quickAttachmentClosePanel()
  }

  @MainActor private func quickAttachmentGridSelection(_ id: String) -> XCUIElement {
    XCTAssertEqual(app.state, .runningForeground)
    let bar = app.navigationBars["选择图片"]
    XCTAssertTrue(bar.waitForExistence(timeout: 10))
    let circle = app.buttons["select-photo-" + id]
    let origin = app.coordinate(withNormalizedOffset: .zero)
    for step in 0..<10 {
      let top = bar.frame.maxY + 6
      let bottom = app.buttons["attachment-add-selected"].frame.minY - 16
      let visible = CGRect(x: 0, y: top, width: app.frame.width, height: max(0, bottom - top))
      if circle.exists {
        let frame = circle.frame
        if !frame.isEmpty, visible.contains(CGPoint(x: frame.midX, y: frame.midY)),
          frame.intersection(visible).height >= min(44, frame.height) * 0.9 { return circle }
      }
      let above = circle.exists ? circle.frame.midY < top : step < 2
      let start = origin.withOffset(CGVector(dx: app.frame.midX, dy: above ? top + 35 : bottom - 35))
      let end = origin.withOffset(CGVector(dx: app.frame.midX, dy: above ? bottom - 35 : top + 35))
      start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
    }
    XCTFail("The exact owned photo must be visible above the fixed footer: " + id)
    return circle
  }

  @MainActor private func quickAttachmentPreparePreviewPhotos() throws -> [String] {
    quickAttachmentControl("quick-attachment-test-photo-strip").tap()
    // Status and ownership rows are at the top of the DEBUG controls; the
    // native List virtualizes them after scrolling down to the prepare button.
    for _ in 0..<5 where !app.staticTexts["photo-strip-ready"].isHittable {
      app.collectionViews.firstMatch.swipeDown()
    }
    XCTAssertTrue(app.staticTexts["photo-strip-ready"].waitForExistence(timeout: 40), app.debugDescription)
    let owned = app.staticTexts["quick-attachment-owned-photos"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      owned.label.components(separatedBy: "|").count >= 6
    }, object: owned)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 25), .completed)
    let ids = owned.label.components(separatedBy: "|")
    XCTAssertGreaterThanOrEqual(ids.count, 6)
    return ids
  }

  @MainActor func testQuickAttachmentImagePreviewCleanup() throws {
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-cleanup").tap()
    quickAttachmentAllowPermission(deletingOwnTestPhotos: true)
    for _ in 0..<5 where !app.staticTexts["photos-cleaned"].isHittable { app.collectionViews.firstMatch.swipeDown() }
    XCTAssertTrue(app.staticTexts["photos-cleaned"].waitForExistence(timeout: 20))
    quickAttachmentClosePanel()
  }

  @MainActor func testQuickAttachmentImagePreviewSelection() throws {
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-preview-metadata").tap()
    XCTAssertTrue(app.staticTexts["preview-metadata-passed"].waitForExistence(timeout: 15))
    let ids = try quickAttachmentPreparePreviewPhotos()
    quickAttachmentControl("recent-photo-" + ids[0]).tap()
    let choose = app.buttons["attachment-preview-select"]
    XCTAssertTrue(choose.waitForExistence(timeout: 15))
    XCTAssertEqual(choose.value as? String, "0")
    choose.tap()
    XCTAssertEqual(choose.value as? String, "1")
    app.buttons["attachment-preview-back"].tap()
    XCTAssertEqual(app.buttons["select-photo-" + ids[0]].value as? String, "1")
    quickAttachmentControl("select-photo-" + ids[1]).tap()
    quickAttachmentControl("attachment-photo-library").tap()
    XCTAssertTrue(app.scrollViews["attachment-photo-grid"].waitForExistence(timeout: 10))
    XCTAssertEqual(app.buttons["select-photo-" + ids[1]].value as? String, "2")
    app.buttons["recent-photo-" + ids[2]].tap()
    XCTAssertTrue(choose.waitForExistence(timeout: 10)); choose.tap()
    shot("image-preview-select-in-fullscreen")
    app.buttons["attachment-preview-back"].tap()
    shot("image-preview-grid-selection-preserved")
    app.buttons["attachment-add-selected"].tap()
    let added = app.staticTexts.matching(NSPredicate(format: "label == %@", "已添加 · 待发送"))
    let three = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in added.count == 3 }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [three], timeout: 25), .completed)
    let open = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "preview-draft-attachment-")).firstMatch
    XCTAssertTrue(open.waitForExistence(timeout: 10)); open.tap()
    let zoom = app.staticTexts["attachment-preview-zoom-value"]
    XCTAssertTrue(zoom.waitForExistence(timeout: 15))
    XCTAssertTrue(app.images["图片预览"].waitForExistence(timeout: 20))
    app.images["图片预览"].doubleTap()
    let enlarged = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in zoom.label != "100%" }, object: zoom)
    XCTAssertEqual(XCTWaiter.wait(for: [enlarged], timeout: 10), .completed)
    shot("image-preview-draft-double-tap-zoom")
    app.images["图片预览"].doubleTap()
    let reset = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in zoom.label == "100%" }, object: zoom)
    XCTAssertEqual(XCTWaiter.wait(for: [reset], timeout: 10), .completed)
    let before = app.staticTexts["attachment-preview-current"].value as? String
    app.images["图片预览"].swipeLeft()
    let paged = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      (self.app.staticTexts["attachment-preview-current"].value as? String) != before
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [paged], timeout: 10), .completed)
    app.images["图片预览"].pinch(withScale: 1.7, velocity: 1)
    let pinched = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in zoom.label != "100%" }, object: zoom)
    XCTAssertEqual(XCTWaiter.wait(for: [pinched], timeout: 10), .completed)
    shot("image-preview-draft-pinch-and-remove")
    let original = try XCTUnwrap(before)
    app.buttons["preview-page-" + original].tap()
    XCTAssertEqual(app.staticTexts["attachment-preview-current"].value as? String, original)
    XCTAssertEqual(zoom.label, "100%", "Thumbnail navigation resets the previous page's zoom")
    app.buttons["attachment-preview-remove"].tap()
    app.buttons["attachment-preview-finish"].tap()
    let two = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in added.count == 2 }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [two], timeout: 15), .completed)
    app.terminate(); app.launchArguments.append("-restore-testing"); app.launchChineseTest()
    XCTAssertTrue(added.firstMatch.waitForExistence(timeout: 15))
    XCTAssertEqual(added.count, 2)
    quickAttachmentPanel()
    quickAttachmentControl("attachment-photo-library").tap()
    let unadded = ids.filter { app.buttons["select-photo-" + $0].isEnabled }
    XCTAssertGreaterThanOrEqual(unadded.count, 4)
    for (index, id) in unadded.prefix(3).enumerated() {
      let circle = quickAttachmentGridSelection(id)
      circle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertEqual(circle.value as? String, String(index + 1))
    }
    let extra = quickAttachmentGridSelection(unadded[3])
    extra.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.staticTexts["最多添加 5 个附件，请先移除部分附件。"].waitForExistence(timeout: 5))
    shot("image-preview-remaining-slots-limit")
    app.buttons["attachment-photo-library-back"].tap()
    quickAttachmentClosePanel()
    XCTAssertEqual(added.count, 2, "Closing selection must preserve the existing draft")
    quickAttachmentPanel()
    XCTAssertFalse(app.buttons["attachment-add-selected"].isEnabled)
    quickAttachmentControl("quick-attachment-test-cleanup").tap()
    quickAttachmentAllowPermission(deletingOwnTestPhotos: true)
    for _ in 0..<5 where !app.staticTexts["photos-cleaned"].isHittable { app.collectionViews.firstMatch.swipeDown() }
    XCTAssertTrue(app.staticTexts["photos-cleaned"].waitForExistence(timeout: 15))
    quickAttachmentClosePanel()
  }

  @MainActor func testQuickAttachmentCameraAndPaste() async throws {
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-copy").tap()
    XCTAssertTrue(app.staticTexts["clipboard-ready"].waitForExistence(timeout: 10))
    quickAttachmentControl("attachment-paste").tap()
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 15), app.debugDescription)
    quickAttachmentPanel()
    quickAttachmentControl("attachment-camera").tap()
    quickAttachmentAllowPermission()
    let shutter = app.buttons.matching(NSPredicate(format: "label IN %@ OR identifier IN %@", ["拍照", "Take Picture", "Shutter", "快门"], ["PhotoCapture", "TakePicture"])).firstMatch
    XCTAssertTrue(shutter.waitForExistence(timeout: 20), app.debugDescription)
    shot("quick-attachment-native-camera")
    shutter.tap()
    let use = app.buttons.matching(NSPredicate(format: "label IN %@", ["使用照片", "Use Photo"])).firstMatch
    XCTAssertTrue(use.waitForExistence(timeout: 15), app.debugDescription)
    use.tap()
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", "已添加 · 待发送"))
      .element(boundBy: 1).waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertTrue(app.staticTexts["photo.jpg"].waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertGreaterThanOrEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "已添加 · 待发送")).count, 2)
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "上传附件")).firstMatch.exists)
    shot("quick-attachment-camera-and-paste-local")
  }

  @MainActor func testQuickAttachmentPhotoPickerTransfer() throws {
    #if !targetEnvironment(simulator)
    throw XCTSkip("Uses only the dedicated simulator's controlled photo library")
    #else
    quickAttachmentPanel()
    quickAttachmentControl("选择图片").tap()
    if app.scrollViews["attachment-photo-grid"].waitForExistence(timeout: 3) {
      let select = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "select-photo-")).firstMatch
      XCTAssertTrue(select.waitForExistence(timeout: 10)); select.tap()
      app.buttons["attachment-add-selected"].tap()
      XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 20))
      shot("quick-attachment-photo-grid-import")
      return
    }
    XCTAssertTrue(app.buttons["照片"].firstMatch.waitForExistence(timeout: 15), app.debugDescription)
    let photo = app.images["PXGGridLayout-Info"].firstMatch
    XCTAssertTrue(photo.waitForExistence(timeout: 10), app.debugDescription)
    photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let add = app.buttons.matching(NSPredicate(format: "identifier == 'Add' OR label IN %@", ["添加", "Add", "完成", "Done"])).firstMatch
    XCTAssertTrue(add.waitForExistence(timeout: 10), app.debugDescription)
    XCTAssertTrue(add.isEnabled); add.tap()
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 20), app.debugDescription)
    shot("quick-attachment-system-photo-import")
    #endif
  }

  @MainActor private func quickAttachmentSelectFile(_ name: String) {
    let browse = app.buttons.matching(NSPredicate(format: "label IN %@", ["Browse", "浏览"])).firstMatch
    if browse.waitForExistence(timeout: 3) { browse.tap() }
    let phone = app.staticTexts.matching(NSPredicate(format: "label IN %@", ["On My iPhone", "我的 iPhone", "我的iPhone"])).firstMatch
    if phone.waitForExistence(timeout: 3) { phone.tap() }
    let folders: [String]
    switch ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_BUNDLE_ID"] {
    case "com.easyai.agenthub.attachmentsplan.acceptance": folders = ["Agent Hub Photos Plan DEV"]
    case "com.easyai.agenthub.imagepreview.acceptance": folders = ["Agent Hub Image Preview DEV"]
    case "com.easyai.agenthub.externalattachments.acceptance": folders = ["Agent Hub External DEV"]
    default: folders = ["Agent Hub Attachments DEV"]
    }
    let folder = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", folders)).firstMatch
    if folder.waitForExistence(timeout: 5) { folder.tap() }
    let file = app.collectionViews.cells.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
    XCTAssertTrue(file.waitForExistence(timeout: 15), app.debugDescription); file.tap()
    let open = app.buttons.matching(NSPredicate(format: "label IN %@", ["Open", "打开"])).firstMatch
    if open.waitForExistence(timeout: 2) { open.tap() }
  }

  @MainActor private func quickAttachmentPrepareLiveDraft(pasteImage: Bool = true, pasteFile: Bool = false, photoPreview: Bool = false) throws -> LiveFixture {
    struct Fixture: Decodable { let connection: LiveFixture; let pairingUri: String }
    let fixture = try JSONDecoder().decode(Fixture.self, from: Data(XCTUnwrap(
      ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_FIXTURE"]).utf8))
    allowAcceptanceLocalNetworkPermission()
    app.buttons["client-switch"].tap()
    let addClient = app.buttons["add-client"]
    for _ in 0..<6 where !addClient.isHittable { app.swipeUp() }
    XCTAssertTrue(addClient.waitForExistence(timeout: 15)); addClient.tap()
    selectConnectionMethod("扫码连接", agent: "codex")
    let description = app.textFields["codex-qr-description"]
    XCTAssertTrue(description.waitForExistence(timeout: 15)); description.tap(); description.typeText(fixture.pairingUri)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.buttons["codex-connect-qr"].tap()
    XCTAssertTrue(description.waitForNonExistence(timeout: 90), app.debugDescription)
    dismissSystemPasswordOffer()
    if !app.buttons["new-chat"].exists { app.buttons["open-sidebar"].tap() }
    chooseDesktopProject(nil)
    if app.buttons["generation-settings"].exists {
      app.buttons["generation-settings"].tap()
      let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
      XCTAssertTrue(effort.waitForExistence(timeout: 10)); effort.tap(); app.buttons["极高"].tap()
      app.buttons["generation-done"].tap()
    }
    quickAttachmentPanel()
    quickAttachmentControl("quick-attachment-test-files").tap()
    XCTAssertTrue(app.staticTexts["files-ready"].waitForExistence(timeout: 10))
    if photoPreview {
      let ids = try quickAttachmentPreparePreviewPhotos()
      quickAttachmentControl("recent-photo-" + ids[0]).tap()
      XCTAssertTrue(app.buttons["attachment-preview-select"].waitForExistence(timeout: 15))
      app.buttons["attachment-preview-select"].tap()
      shot("image-preview-live-selected-before-add")
      app.buttons["attachment-preview-add-selected"].tap()
      XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 20))
      quickAttachmentPanel()
    }
    if pasteImage || pasteFile {
      quickAttachmentControl("quick-attachment-test-copy").tap()
      quickAttachmentControl("attachment-paste").tap()
      XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 15), app.debugDescription)
      quickAttachmentPanel()
    }
    if pasteFile {
      quickAttachmentControl("quick-attachment-test-copy-document").tap()
      XCTAssertTrue(app.staticTexts["document-copied"].waitForExistence(timeout: 10))
      quickAttachmentControl("attachment-paste").tap()
    } else {
      quickAttachmentControl("选择文件").tap()
      quickAttachmentSelectFile(pasteImage || photoPreview ? "qa-input" : "qa-attachment")
    }
    if !pasteImage && !pasteFile && !photoPreview {
      XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.waitForExistence(timeout: 20), app.debugDescription)
      quickAttachmentPanel()
      quickAttachmentControl("选择文件").tap()
      quickAttachmentSelectFile("qa-input")
    }
    XCTAssertTrue(app.staticTexts["qa-input.txt"].waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", "已添加 · 待发送"))
      .element(boundBy: 1).waitForExistence(timeout: 20), app.debugDescription)
    return fixture.connection
  }

  @MainActor func testQuickAttachmentPairedLocalPreparationDEV() throws {
    _ = try quickAttachmentPrepareLiveDraft()
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.exists)
    shot("quick-attachment-paired-local-only")
  }

  @MainActor func testQuickAttachmentSendOnDemandDEV() async throws {
    try await quickAttachmentSendOnDemandDEV(pasteFile: false)
  }
  @MainActor func testQuickAttachmentFilePasteSendDEV() async throws {
    try await quickAttachmentSendOnDemandDEV(pasteFile: true)
  }
  @MainActor func testQuickAttachmentImagePreviewSendDEV() async throws {
    try await quickAttachmentSendOnDemandDEV(pasteFile: false, photoPreview: true)
  }
  @MainActor private func quickAttachmentSendOnDemandDEV(pasteFile: Bool, photoPreview: Bool = false) async throws {
    let connection = try quickAttachmentPrepareLiveDraft(pasteImage: false, pasteFile: pasteFile, photoPreview: photoPreview)
    focusLiveComposer(requireSoftwareKeyboard: !photoPreview)
    let prompt = "读取图片和文本附件，分别报告其中的校验码。只输出这两个校验码，不执行其他操作。"
    composer.typeText(prompt)
    XCTAssertTrue((composer.value as? String)?.contains(prompt) == true)
    XCTAssertTrue(app.staticTexts["已添加 · 待发送"].firstMatch.exists)
    shot("quick-attachment-live-before-send")
    print("QUICK_ATTACHMENT_SEND_AUTHORIZED " + connection.nonce); fflush(stdout)
    // Gives the passive Mac observer a prepare-only sampling interval. A late
    // marker fails the observer gate; it never authorizes an automatic replay.
    try await Task.sleep(for: .seconds(2))
    sendLiveComposer(attachmentNonce: connection.nonce)
    let code = try XCTUnwrap(ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_CODE"])
    let reply = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", code)).firstMatch
    XCTAssertTrue(reply.waitForExistence(timeout: 180), app.debugDescription)
    shot("quick-attachment-live-model-read")
  }
}

extension CodexManageUITests {
  @MainActor func testSlowNetworkFirstConversationBeforeCatalog() async throws {
    let base = URL(string: "http://127.0.0.1:8897")!
    let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("reset"))
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    XCTAssertTrue(app.staticTexts["SLOW_NETWORK_HISTORY"].waitForExistence(timeout: 30), app.debugDescription)
    let (bytes, _) = try await URLSession.shared.data(from: base.appendingPathComponent("stats"))
    let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    print("SLOW_NETWORK_FIRST_HISTORY_MS", stats["firstHistoryDelayMS"] ?? "missing")
    XCTAssertLessThan(try XCTUnwrap(stats["firstHistoryDelayMS"] as? Double), 7000,
      "Current conversation must not wait for ten serial catalog pages")
    XCTAssertEqual(stats["maxHistoryInFlight"] as? Int, 1)
    shot("slow-network-first-conversation-before-full-catalog")
  }

  @MainActor func testSlowNetworkHistoryTimeoutKeepsHealthyConnection() async throws {
    let base = URL(string: "http://127.0.0.1:8897")!
    let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("reset").appending(queryItems: [URLQueryItem(name: "mode", value: "timeout")]))
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    XCTAssertTrue(app.staticTexts["SLOW_NETWORK_HISTORY"].waitForExistence(timeout: 20))
    focusLiveComposer(); composer.typeText("UNSENT_SLOW_NETWORK_DRAFT")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("stall"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    let retry = app.buttons["conversation-sync-retry"]
    XCTAssertTrue(retry.waitForExistence(timeout: 48), app.debugDescription)
    XCTAssertTrue(app.staticTexts["读取对话超时，已有消息和草稿已保留。"].exists)
    XCTAssertFalse(app.staticTexts["正在重新连接… 草稿保存在此对话。"].exists)
    app.buttons["client-switch"].tap()
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].label.contains("已连接"), app.debugDescription)
    app.buttons["完成"].tap()
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("recover"))
    if retry.exists && retry.isEnabled { retry.tap() }
    XCTAssertTrue(retry.waitForNonExistence(timeout: 15))
    XCTAssertEqual(composer.value as? String, "UNSENT_SLOW_NETWORK_DRAFT")
    let (bytes, _) = try await URLSession.shared.data(from: base.appendingPathComponent("stats"))
    let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    XCTAssertEqual(stats["stalls"] as? Int, 1)
    XCTAssertEqual(stats["maxHistoryInFlight"] as? Int, 1)
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("drop"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    var dropped = false
    for _ in 0..<30 {
      let (bytes, _) = try await URLSession.shared.data(from: base.appendingPathComponent("stats"))
      let stats = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
      if stats?["drops"] as? Int == 1 { dropped = true; break }
      try await Task.sleep(for: .milliseconds(250))
    }
    XCTAssertTrue(dropped, "Exercise an actual severed response before the all-channel outage")
    XCTAssertTrue(retry.waitForNonExistence(timeout: 15))
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("offline"))
    app.buttons["client-switch"].tap()
    let row = app.buttons["client-" + fixture.hostId]
    let disconnected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "NOT (label CONTAINS %@)", "已连接"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [disconnected], timeout: 15), .completed,
      "Losing all channels must still display reconnection")
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("recover"))
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "已连接"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 20), .completed)
    app.buttons["完成"].tap()
    XCTAssertEqual(composer.value as? String, "UNSENT_SLOW_NETWORK_DRAFT")
    shot("slow-network-timeout-recovered-with-original-draft")
  }
}


extension CodexManageUITests {
  @MainActor private func connectWeakNetworkWindows() async throws -> (URL, LiveFixture, XCUIElement) {
    let control = URL(string: ProcessInfo.processInfo.environment["WEAK_NETWORK_CONTROL_URL"] ?? "http://127.0.0.1:8897")!
    let (bytes, _) = try await URLSession.shared.data(from: control.appendingPathComponent("fixture"))
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: bytes)
    struct ReadTarget: Decodable { let threadTitle: String; let expectedText: String }
    let target = try JSONDecoder().decode(ReadTarget.self, from: bytes)
    var connectionStarted = ProcessInfo.processInfo.systemUptime
    connectLive(fixture, supportsCreation: false, onConnecting: { connectionStarted = ProcessInfo.processInfo.systemUptime })
    let search = openDesktopSidebarSearch()
    // Desktop titles may include newlines. Type only the first line so an
    // embedded Return cannot submit the search and lose its native focus.
    let query = String((target.threadTitle.split(separator: "\n").first ?? "").prefix(64))
    XCTAssertFalse(query.isEmpty)
    search.tap(); search.typeText(query)
    let row = app.buttons["recent-thread-" + fixture.threadId]
    XCTAssertTrue(row.waitForExistence(timeout: 30), app.debugDescription)
    let start = ProcessInfo.processInfo.systemUptime
    row.tap()
    let actual = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", target.expectedText)).firstMatch
    XCTAssertTrue(actual.waitForExistence(timeout: 45), "The exact Windows thread must render its real upstream message")
    print("WEAK_NETWORK_REAL_WINDOWS_CONNECT_TO_VISIBLE_MS", (ProcessInfo.processInfo.systemUptime - connectionStarted) * 1000)
    print("WEAK_NETWORK_REAL_WINDOWS_VISIBLE_MS", (ProcessInfo.processInfo.systemUptime - start) * 1000)
    XCTAssertFalse(app.buttons["conversation-sync-retry"].exists)
    return (control, fixture, actual)
  }

  @MainActor func testWeakNetworkRealWindowsFirstHistory() async throws {
    let (control, _, _) = try await connectWeakNetworkWindows()
    let (bytes, _) = try await URLSession.shared.data(from: control.appendingPathComponent("stats"))
    let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    XCTAssertEqual(stats["upstreamWrites"] as? Int, 0)
    XCTAssertEqual(stats["maxHistoryInFlight"] as? Int, 1)
    let requests = try XCTUnwrap(stats["requests"] as? [[String: Any]])
    let catalog = try XCTUnwrap(requests.first { $0["path"] as? String == "/v1/workspace" })
    let history = try XCTUnwrap(requests.first { ["/v1/history", "/v1/events"].contains($0["path"] as? String ?? "") })
    let catalogClosed = try XCTUnwrap(catalog["closedAt"] as? Double)
    let historyStarted = try XCTUnwrap(history["startedAt"] as? Double)
    XCTAssertLessThan(historyStarted - catalogClosed, 2000,
      "A verified initial workspace must start history without a second serial catalog read")
    print("WEAK_NETWORK_REAL_WINDOWS_HTTP_STATS", String(data: bytes, encoding: .utf8)!)
    shot("weak-network-real-windows-first-history")
  }

  @MainActor func testWeakNetworkRealWindowsTimeoutRecovery() async throws {
    let (control, fixture, _) = try await connectWeakNetworkWindows()
    let fault = control.appendingPathComponent("fault")
    let draft = "UNSENT_WEAK_NETWORK_WINDOWS_DRAFT"
    focusLiveComposer()
    // Let each native key event settle before the next one. Bulk XCTest input
    // can reorder a shifted character on iOS 27 before any network fault.
    for character in draft { composer.typeText(String(character)) }
    XCTAssertEqual(composer.value as? String, draft, "Verify native keyboard input before introducing any network fault")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    _ = try await URLSession.shared.data(from: fault.appendingPathComponent("stall"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    let retry = app.buttons["conversation-sync-retry"]
    // A concurrent authentic event snapshot can satisfy synchronization before
    // the stalled read expires. Verify the actual native deadline instead of
    // requiring an obsolete error banner to remain visible after that success.
    var expiredReadMS: Double?
    // The refresh can join an existing slow read before the next request
    // consumes the fault. Start the deadline assertion on that exact request.
    var stallStarted = false
    for _ in 0..<120 {
      let (bytes, _) = try await URLSession.shared.data(from: control.appendingPathComponent("stats"))
      let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
      if stats["stalls"] as? Int == 1 { stallStarted = true; break }
      try await Task.sleep(for: .milliseconds(500))
    }
    XCTAssertTrue(stallStarted, "Require an actual stalled read, rather than a coalesced refresh tap")
    for _ in 0..<90 {
      let (bytes, _) = try await URLSession.shared.data(from: control.appendingPathComponent("stats"))
      let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
      let requests = stats["requests"] as? [[String: Any]] ?? []
      if let expired = requests.first(where: {
        $0["injectedStall"] as? Bool == true && $0["outcome"] as? String == "client-closed"
          && (($0["closedAt"] as? Double ?? 0) - ($0["startedAt"] as? Double ?? 0)) >= 34000
      }) {
        expiredReadMS = (expired["closedAt"] as? Double ?? 0) - (expired["startedAt"] as? Double ?? 0)
        break
      }
      try await Task.sleep(for: .milliseconds(500))
    }
    XCTAssertGreaterThanOrEqual(try XCTUnwrap(expiredReadMS), 34000)
    XCTAssertLessThan(try XCTUnwrap(expiredReadMS), 42000)
    print("WEAK_NETWORK_REAL_WINDOWS_EXPIRED_READ_MS", try XCTUnwrap(expiredReadMS))
    if retry.exists { XCTAssertTrue(app.staticTexts["读取对话超时，已有消息和草稿已保留。"].exists) }
    XCTAssertFalse(app.staticTexts["正在重新连接… 草稿保存在此对话。"].exists)
    app.buttons["client-switch"].tap()
    let row = app.buttons["client-" + fixture.hostId]
    XCTAssertTrue(row.label.contains("已连接"), app.debugDescription)
    app.buttons["完成"].tap()
    _ = try await URLSession.shared.data(from: fault.appendingPathComponent("recover"))
    if retry.exists && retry.isEnabled { retry.tap() }
    XCTAssertTrue(retry.waitForNonExistence(timeout: 30))
    _ = try await URLSession.shared.data(from: fault.appendingPathComponent("drop"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    var dropped = false
    for _ in 0..<70 {
      let (bytes, _) = try await URLSession.shared.data(from: control.appendingPathComponent("stats"))
      let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
      if stats["drops"] as? Int == 1 { dropped = true; break }
      try await Task.sleep(for: .milliseconds(500))
    }
    XCTAssertTrue(dropped, "Require the actual severed response before testing complete loss")
    XCTAssertTrue(retry.waitForNonExistence(timeout: 30))
    _ = try await URLSession.shared.data(from: fault.appendingPathComponent("offline"))
    app.buttons["client-switch"].tap()
    let disconnected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "NOT (label CONTAINS %@)", "已连接"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [disconnected], timeout: 35), .completed)
    _ = try await URLSession.shared.data(from: fault.appendingPathComponent("recover"))
    let reconnected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "已连接"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [reconnected], timeout: 35), .completed)
    app.buttons["完成"].tap()
    XCTAssertEqual(composer.value as? String, draft)
    let (bytes, _) = try await URLSession.shared.data(from: control.appendingPathComponent("stats"))
    let stats = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    XCTAssertEqual(stats["stalls"] as? Int, 1)
    XCTAssertEqual(stats["drops"] as? Int, 1)
    XCTAssertEqual(stats["maxHistoryInFlight"] as? Int, 1)
    XCTAssertEqual(stats["upstreamWrites"] as? Int, 0)
    shot("weak-network-real-windows-recovered-original-draft")
  }

  @MainActor func testPhysicalCellularWindowsDiscoveryDEV() async throws {
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    app.buttons["client-switch"].tap()
    app.buttons["add-client"].tap()
    selectConnectionMethod("自动发现", agent: "codex")
    let windows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "codex-discovered-", "LAPTOP-F0OKUIVU")).firstMatch
    XCTAssertTrue(windows.waitForExistence(timeout: 45), app.debugDescription)
    print("PHYSICAL_WINDOWS_DISCOVERY_ID", windows.identifier)
    shot("physical-windows-discovery-before-claim")
  }

  /// Real-device only. Uses the normal app, authentic discovery/claim, and the
  /// original Windows Host. No fixture, seeded history, or business submission.
  @MainActor func testPhysicalCellularWindowsReadDEV() async throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["CELLULAR_WINDOWS_READ_JSON"])
    struct Configuration: Decodable { let hostId: String; let threadId: String; let threadTitle: String; let expectedText: String }
    let config = try JSONDecoder().decode(Configuration.self, from: Data(encoded.utf8))
    XCTAssertFalse(config.hostId.isEmpty); XCTAssertFalse(config.expectedText.isEmpty)
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    app.buttons["client-switch"].tap()
    if !app.buttons["client-" + config.hostId].exists {
      app.buttons["add-client"].tap()
      selectConnectionMethod("自动发现", agent: "codex")
      let discovered = app.buttons["codex-discovered-" + config.hostId]
      XCTAssertTrue(discovered.waitForExistence(timeout: 40), "The real Windows Host must be discovered")
      let started = ProcessInfo.processInfo.systemUptime
      discovered.tap()
      XCTAssertTrue(discovered.waitForNonExistence(timeout: 30), "Authenticated connection must finish")
      print("CELLULAR_WINDOWS_AUTHENTICATED_CONNECT_MS", (ProcessInfo.processInfo.systemUptime - started) * 1000)
    } else {
      app.buttons["client-" + config.hostId].tap()
    }
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    let search = openDesktopSidebarSearch()
    search.tap(); search.typeText(config.threadTitle); search.typeText("\n")
    let thread = app.buttons["recent-thread-" + config.threadId]
    XCTAssertTrue(thread.waitForExistence(timeout: 30))
    let started = ProcessInfo.processInfo.systemUptime
    thread.tap()
    let text = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", config.expectedText)).firstMatch
    XCTAssertTrue(text.waitForExistence(timeout: 30), "The Windows conversation must render its actual message")
    print("CELLULAR_WINDOWS_VISIBLE_HISTORY_MS", (ProcessInfo.processInfo.systemUptime - started) * 1000)
    XCTAssertFalse(app.buttons["conversation-sync-retry"].exists)
    let draft = "UNSENT_CELLULAR_VERIFICATION_DRAFT"
    focusLiveComposer(); composer.typeText(draft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    shot("physical-cellular-windows-actual-history")
    for sample in 0..<3 {
      app.terminate()
      let start = ProcessInfo.processInfo.systemUptime
      app.activate()
      XCTAssertTrue(text.waitForExistence(timeout: 30))
      // Let a fresh event/history cycle run; cache visibility alone is not a
      // successful reconnection. Saved data and HTTP readback are checked apart.
      try await Task.sleep(for: .seconds(10))
      XCTAssertFalse(app.buttons["conversation-sync-retry"].exists)
      XCTAssertFalse(app.staticTexts["正在重新连接… 草稿保存在此对话。"].exists)
      XCTAssertEqual(composer.value as? String, draft)
      print("CELLULAR_WINDOWS_RESTART_SAMPLE", sample, "VISIBLE_MS", (ProcessInfo.processInfo.systemUptime - start - 10) * 1000)
    }
    shot("physical-cellular-windows-restarted-draft-preserved")
  }
}

final class CodexManageUITests: XCTestCase {
  private var app: XCUIApplication!
  private var physicalNotificationSection = 0
  private var suggestionCleanupTarget: (hostId: String, originalId: String)?
  override func setUpWithError() throws {
    continueAfterFailure = false
    if name.contains("testMarkdownFilePreviewPhysicalDEV") {
      guard let raw = ProcessInfo.processInfo.environment["FILE_PREVIEW_FIXTURE"],
        let fixture = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
        let bundleId = fixture["bundleId"] as? String,
        bundleId.hasPrefix("com.easyai.agenthub.filepreview.") else {
        throw XCTSkip("Requires the independent file preview acceptance App")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: bundleId)
      addUIInterruptionMonitor(withDescription: "File preview acceptance network permission") { alert in
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow"])).firstMatch
        guard allow.exists else { return false }; allow.tap(); return true
      }
      app.launchArguments = ["-ui-testing"]
      app.launchChineseTest()
      return
    }
    if name.contains("testThreadModelSettingsPhysicalDEV") {
      guard let raw = ProcessInfo.processInfo.environment["THREAD_MODEL_SETTINGS_FIXTURE"],
        let fixture = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
        let bundleId = fixture["bundleId"] as? String,
        bundleId.hasPrefix("com.easyai.agenthub.modelsettings.") else {
        throw XCTSkip("Requires the independent model settings acceptance App")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: bundleId)
      addUIInterruptionMonitor(withDescription: "Model settings acceptance network permission") { alert in
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow"])).firstMatch
        guard allow.exists else { return false }; allow.tap(); return true
      }
      app.launchArguments = ["-ui-testing"]
      app.launchChineseTest()
      return
    }
    if name.contains("testMobileReadingPhysicalAttachmentReplyDEV") {
      guard ProcessInfo.processInfo.environment["UNREAD_PHYSICAL_JSON"] != nil else {
        throw XCTSkip("Requires the independent unread attachment acceptance App and real desktop fixture")
      }
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.unread.acceptance")
      app.launchArguments = ["-ui-testing", "-restore-testing"]
      app.launchChineseTest()
      return
    }
    if name.contains("testComputerUseApprovalOriginalDesktopDEV") {
      guard let encoded = ProcessInfo.processInfo.environment["COMPUTER_USE_APPROVAL_FIXTURE_JSON"],
        let value = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any],
        let bundleId = value["bundleId"] as? String,
        bundleId.range(of: #"^com\.easyai\.agenthub\.computeruseapproval\.[a-f0-9]{8}$"#, options: .regularExpression) != nil else {
        throw XCTSkip("Requires the independent Computer Use approval acceptance App")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: bundleId)
      addUIInterruptionMonitor(withDescription: "Computer Use acceptance network permission") { alert in
        guard alert.label.contains("Agent Hub"), alert.label.contains("无线数据") || alert.label.contains("网络") else { return false }
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow"])).firstMatch
        guard allow.exists else { return false }
        allow.tap(); return true
      }
      app.launchArguments = ["-ui-testing"]
      app.launchChineseTest()
      return
    }
    if name.contains("testPhysicalCompletedLoadedLifecycleDEV") {
      guard let encoded = ProcessInfo.processInfo.environment["LOADED_LIFECYCLE_PHYSICAL_JSON"],
        let value = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any],
        value["bundleId"] as? String == "com.easyai.agenthub.loadedlifecycle" else {
        throw XCTSkip("Requires the independent physical loaded-chat acceptance App")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.loadedlifecycle")
      addUIInterruptionMonitor(withDescription: "Loaded-chat acceptance network authorization") { alert in
        guard alert.label.contains("Agent Hub"),
          alert.label.contains("网络") || alert.label.contains("无线数据") else { return false }
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@",
          ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow"])).firstMatch
        guard allow.exists else { return false }
        allow.tap(); return true
      }
      app.launchArguments = ["-ui-testing", "-restore-testing"]
      app.launchChineseTest()
      return
    }
    if name.contains("testStoppedOriginalDesktopReadOnlyDEV") {
      guard ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"] == "com.easyai.agenthub.thermal.acceptance" else {
        throw XCTSkip("Requires the independent paused-state acceptance App with network authorization")
      }
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.thermal.acceptance")
      return
    }
    if name.contains("testQuickAttachment") {
      let bundleId = ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_BUNDLE_ID"] ?? ""
      guard ["com.easyai.agenthub.quickattachments.acceptance", "com.easyai.agenthub.externalattachments.acceptance", "com.easyai.agenthub.attachmentsplan.acceptance", "com.easyai.agenthub.imagepreview.acceptance"].contains(bundleId) else {
        throw XCTSkip("Requires the independent quick attachment acceptance App")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: bundleId)
      app.launchArguments = ["-ui-testing", "-quick-attachments-acceptance"]
      if bundleId == "com.easyai.agenthub.externalattachments.acceptance" {
        app.launchArguments.append("-external-owned-fixtures-reset")
      }
      app.launchEnvironment["QUICK_ATTACHMENT_CODE"] = ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_CODE"]
      addUIInterruptionMonitor(withDescription: "Quick attachment permission") { alert in
        if (alert.label.contains("Agent Hub Attachments DEV") || alert.label.contains("Agent Hub Image Preview DEV")) && (alert.label.contains("delete") || alert.label.contains("删除")) {
          let remove = alert.buttons.matching(NSPredicate(format: "label IN %@", ["Delete", "删除", "Delete Photos", "删除照片"])).firstMatch
          if remove.exists { remove.tap(); return true }
        }
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "Allow", "好", "OK", "允许完全访问", "允许访问所有照片", "Allow Full Access", "无线局域网与蜂窝网络", "无线局域网与蜂窝数据"])).firstMatch
        guard allow.exists else { return false }
        allow.tap(); return true
      }
      app.launchChineseTest()
      return
    }
    if name.contains("testFileInteractionFeedbackDEV") {
      guard ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"] == "com.easyai.agenthub.fileinteraction" else {
        throw XCTSkip("Requires the independent file interaction acceptance App")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.fileinteraction")
      addUIInterruptionMonitor(withDescription: "Independent file App network authorization") { alert in
        guard alert.label.contains("Agent Hub"),
          alert.label.contains("网络") || alert.label.contains("无线数据") else { return false }
        let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow"])).firstMatch
        guard allow.exists else { return false }
        allow.tap(); return true
      }
      app.launchArguments = ["-ui-testing"]
      app.launchChineseTest()
      return
    }
    if name.contains("testAttachmentMetadataTriggerDEV") {
      guard let json = ProcessInfo.processInfo.environment["ATTACHMENT_METADATA_DEV_JSON"],
        let value = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
        value["bundleId"] as? String == "com.easyai.agenthub.remotefile409" else {
        throw XCTSkip("Requires the explicit isolated MacBook metadata acceptance configuration")
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.remotefile409")
      app.launchArguments = ["-ui-testing", "-restore-testing"]
      app.launchChineseTest()
      return
    }
    #if DEBUG
    if name.contains("testPhysicalAgentHubFeatureRestoreExistingClient"),
      ProcessInfo.processInfo.environment["EXISTING_CLIENT_SELECTION_UUID"] == nil {
      throw XCTSkip("Requires an explicit existing client UUID")
    }
    #endif
    if name.contains("testOriginalDesktopReadOnlySuggestionsDEV") {
      guard let encoded = ProcessInfo.processInfo.environment["SUGGESTION_DEV_FIXTURE_JSON"],
        let value = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any] else {
        throw XCTSkip("Requires the dedicated read-only suggestion DEV runner")
      }
      if value["tracePhoneIsolatedBaseline"] != nil {
        let method = "testOriginalDesktopReadOnlySuggestionsDEV"
        guard name.hasSuffix(" " + method + "]") || name.hasSuffix("." + method) || name == method,
          let bundleId = value["bundleId"] as? String,
          bundleId.range(of: #"^com\.easyai\.agenthub\.traceacceptance(?:\.[a-z0-9]+)*$"#, options: .regularExpression) != nil else {
          throw NSError(domain: "TracePhoneUIBaseline", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "UI baseline requires the exact dedicated test and bundle"])
        }
      }
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: value["bundleId"] as? String ?? "com.easyai.agenthub")
      // Launch the newly installed candidate instead of reactivating an older
      // process or retaining a failed, unsubmitted connection form.
      app.terminate()
      app.launchArguments = []
      app.activate()
      return
    }
    if name.contains("testPhysicalReplyWaitRecoveryDEV") {
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub")
      app.launchArguments = []
      app.terminate(); app.activate()
      return
    }
    if name.contains("testPhysicalCellularWindows") {
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.um06local")
      app.launchArguments = []
      app.terminate(); app.activate()
      return
    }
    if name.contains("testPhysicalSlowNetwork") {
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub")
      return
    }
    if name.contains("testPhysicalAgentHubFeature") || name.contains("testPhysicalVPN") || name.contains("testPhysicalAgentHubNotificationRegistration") || name.contains("testPhysicalAgentHubHistoryPagingDEV") || name.contains("testPhysicalEasyWork") || name.contains("testPhysicalSidebar") || name.contains("testPhysicalComputerFileTransfer") {
      XCUIDevice.shared.orientation = .portrait
      app = XCUIApplication(bundleIdentifier: "com.easyai.agenthub")
      app.terminate()
      app.activate()
      if name.contains("testPhysicalAgentHubFeature") { dismissPhysicalPasswordPrompt() }
      return
    }
    if name.contains("testPhysicalHistoryRead") {
      app = XCUIApplication(bundleIdentifier: "com.easyai.codexmanage")
      app.activate()
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
      return
    }
    if name.contains("testCodexM5ReconnectAfterRestoreRealDEV") {
      app = XCUIApplication(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest(); return
    }
    app = XCUIApplication()
    if name.contains("testThermal") { return }
    if name.contains("testWindowsConversationSyncReadOnlyDEV") { return }
    if name.contains("testHistoryContinuousScrollPerformance") || name.contains("testHistoryContinuousScrollOriginalDesktopDEV") || name.contains("testHistoryRemotePrependOriginalDesktopDEV") || name.contains("testHistoryShortListPrependKeepsPosition") || name.contains("testHistoryInstalledAppReadOnlyDEV") { return }
    if name.contains("testRealtimeVoice") { return }
    if name.contains("testEasyWorkRealDEV") || name.contains("testEasyWorkOrdinaryAppDEV") || name.contains("testCodexMachineIdentityLegacySelectionSurvivesRestore") || name.contains("testEasyWorkReceivePerformanceDEV") { return }
    if name.contains("testVoiceExistingTranscriptionDraftDEV") || name.contains("testCodexHostRecordedAudioTranscriptionDEV") && ProcessInfo.processInfo.environment["VOICE_RESTORE_ORIGINAL"] == "1" {
      app.launchArguments = ["-ui-testing", "-restore-testing"]
      app.launchChineseTest()
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
      return
    }
    app.launchArguments = name.contains("testReplyWaitReceiptReadOnlyRecoveryDEV") ? ["-ui-testing", "-restore-testing"] : ["-ui-testing"]
    if name.contains("testHistoryPaging") { app.launchArguments.append("-history-position-probe") }
    if name.contains("testHistoryPagingRemoteSecondLayoutKeepsAnchor") { app.launchArguments.append("-history-prepend-layout-fixture") }
    if name.contains("testSidebarRefreshVisualHitRegionsStayAligned") { app.launchArguments.append("-sidebar-refresh-hold-fixture") }
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10))
  }
  override func tearDownWithError() throws {
    if name.contains("testOriginalDesktopReadOnlySuggestionsDEV"), suggestionCleanupTarget != nil {
      // Keep XCTest cleanup synchronous: an aborted @MainActor test must not
      // wait for an asynchronous teardown block to hop back to that actor.
      let previous = continueAfterFailure
      continueAfterFailure = true
      print("SUGGESTION_DEV_TEARDOWN_BEGIN")
      cleanupReadOnlySuggestionConnection()
      print("SUGGESTION_DEV_TEARDOWN_END", suggestionCleanupTarget == nil ? "restored" : "not-restored")
      continueAfterFailure = previous
    }
    try super.tearDownWithError()
  }
  @MainActor private func openWorkspaceManagement() {
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10)); app.buttons["open-sidebar"].tap()
    app.buttons["open-settings"].tap()
    let entry = app.buttons["settings-workspace-management"]
    XCTAssertTrue(entry.waitForExistence(timeout: 5)); entry.tap()
    XCTAssertTrue(app.segmentedControls["management-kind"].waitForExistence(timeout: 5))
  }
  @MainActor func testLongUserMessageCollapseAndExpand() {
    let toggles = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "user-message-toggle-"))
    XCTAssertEqual(toggles.count, 0, "Short user messages have no disclosure control")
    composer.tap()
    composer.typeText((1...6).map { "Line \($0)" }.joined(separator: "\n"))
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 10))
    XCTAssertEqual(toggles.count, 0, "Exactly six lines remain fully visible")

    let original = String(repeating: "A long user message wraps naturally at the available bubble width. ", count: 7)
    composer.tap(); composer.typeText(original)
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 10))
    let toggle = toggles.firstMatch
    XCTAssertTrue(toggle.waitForExistence(timeout: 5))
    XCTAssertEqual(toggle.label, "展开")
    XCTAssertEqual(toggle.value as? String, "已折叠")
    XCTAssertGreaterThanOrEqual(toggle.frame.height, 44)
    let id = String(toggle.identifier.dropFirst("user-message-toggle-".count))
    let text = app.staticTexts["user-message-text-" + id]
    let collapsedHeight = text.frame.height
    XCTAssertLessThan(collapsedHeight, 160)
    XCTAssertEqual(text.label, original, "Folding preserves the original selectable message")
    shot("user-message-collapsed")
    toggle.tap()
    XCTAssertEqual(toggle.label, "收起")
    XCTAssertEqual(toggle.value as? String, "已展开")
    XCTAssertGreaterThan(text.frame.height, collapsedHeight + 40)
    for _ in 0..<8 where !toggle.isHittable { app.scrollViews["message-list"].swipeUp() }
    shot("user-message-expanded")
    toggle.tap()
    XCTAssertEqual(toggle.label, "展开")
    XCTAssertEqual(text.frame.height, collapsedHeight, accuracy: 1)
    XCTAssertEqual(text.label, original)
    shot("user-message-collapsed-again")
  }
  @MainActor func testOriginalDesktopLongUserMessageDEV() async throws {
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8896")!) else {
      throw XCTSkip("Requires the dedicated real desktop long-message acceptance Host")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    let search = openDesktopSidebarSearch()
    search.tap()
    // History snapshots normalize title newlines; search its unchanged first line.
    search.typeText(String((fixture.threadTitle ?? "").prefix(30)) + "\n")
    let thread = app.buttons["recent-thread-" + fixture.threadId]
    XCTAssertTrue(thread.waitForExistence(timeout: 20)); thread.tap()
    XCTAssertTrue(app.staticTexts["LONG_MESSAGE_" + fixture.nonce].waitForExistence(timeout: 60))
    let toggle = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "user-message-toggle-")).firstMatch
    XCTAssertTrue(toggle.waitForExistence(timeout: 10))
    let id = String(toggle.identifier.dropFirst("user-message-toggle-".count))
    let text = app.staticTexts["user-message-text-" + id]
    let original = text.label
    let collapsedHeight = text.frame.height
    XCTAssertEqual(toggle.label, "展开")
    shot("real-desktop-user-message-collapsed-light")
    toggle.tap()
    XCTAssertEqual(toggle.label, "收起")
    XCTAssertGreaterThan(text.frame.height, collapsedHeight + 40)
    for _ in 0..<12 where !toggle.isHittable { app.scrollViews["message-list"].swipeUp() }
    shot("real-desktop-user-message-expanded")
    toggle.tap()
    XCTAssertEqual(text.frame.height, collapsedHeight, accuracy: 1)
    XCTAssertEqual(text.label, original)
    setChatAppearance("深色")
    XCTAssertEqual(toggle.label, "展开")
    shot("real-desktop-user-message-collapsed-dark")
    setChatAppearance("浅色")
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["LONG_MESSAGE_" + fixture.nonce].waitForExistence(timeout: 60))
    XCTAssertEqual(toggle.label, "展开", "History defaults to a compact preview after relaunch")
  }
  @MainActor func testWorkspaceArchiveOnlyDemoSharedRules() {
    openWorkspaceManagement()
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    let first = app.buttons["management-item-query"], second = app.buttons["management-item-spacing"]
    XCTAssertTrue(first.waitForExistence(timeout: 10)); first.tap(); second.tap()
    XCTAssertTrue(app.buttons["management-submit"].label.contains("2")); shot("archive-shared-two-selected")
    app.buttons["management-submit"].tap(); app.alerts.buttons["取消"].firstMatch.tap()
    XCTAssertTrue(first.exists); XCTAssertTrue(second.exists)
    app.buttons["management-submit"].tap(); shot("archive-shared-confirmation")
    app.alerts.buttons["归档对话"].firstMatch.tap()
    XCTAssertTrue(first.waitForNonExistence(timeout: 10)); XCTAssertTrue(second.waitForNonExistence(timeout: 10))
    XCTAssertTrue(app.buttons["management-item-navigation"].exists); shot("archive-shared-confirmed-results")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    openWorkspaceManagement(); app.segmentedControls["management-kind"].buttons["对话"].tap()
    XCTAssertTrue(first.waitForNonExistence(timeout: 10)); XCTAssertTrue(second.waitForNonExistence(timeout: 10))
    XCTAssertTrue(app.buttons["management-item-navigation"].exists); shot("archive-shared-restart-no-replay")
  }
  @MainActor func testWorkspaceManagementBatchDemo() {
    openWorkspaceManagement()
    app.buttons["management-item-api"].tap(); app.buttons["management-item-design"].tap()
    XCTAssertTrue(app.buttons["management-submit"].label.contains("2")); app.buttons["management-submit"].tap()
    app.alerts.buttons["取消"].firstMatch.tap()
    XCTAssertTrue(app.buttons["management-item-api"].exists)
    app.buttons["management-submit"].tap(); app.alerts.buttons["删除项目"].firstMatch.tap()
    XCTAssertTrue(app.buttons["management-item-api"].waitForNonExistence(timeout: 10)); XCTAssertTrue(app.buttons["management-item-design"].waitForNonExistence(timeout: 10))
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    XCTAssertTrue(app.buttons["management-item-query"].exists, "Deleting a project keeps its chats")
    app.buttons["management-item-query"].tap(); app.buttons["management-item-spacing"].tap()
    shot("bulk-manage-selected-chats")
    app.buttons["management-submit"].tap(); app.alerts.buttons["归档对话"].firstMatch.tap()
    XCTAssertTrue(app.buttons["management-item-query"].waitForNonExistence(timeout: 10)); XCTAssertTrue(app.buttons["management-item-spacing"].waitForNonExistence(timeout: 10))
    XCTAssertTrue(app.buttons["management-item-navigation"].exists)
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    openWorkspaceManagement()
    XCTAssertTrue(app.buttons["management-item-api"].waitForNonExistence(timeout: 10))
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    XCTAssertTrue(app.buttons["management-item-query"].waitForNonExistence(timeout: 10))
    XCTAssertTrue(app.buttons["management-item-navigation"].exists)
  }
  @MainActor func testOriginalDesktopWorkspaceManagementDEV() async throws {
    struct BulkFixture: Decodable { let connection: LiveFixture; let projectIds: [String]; let threadIds: [String]; let search: String; let projectsRemoved: Bool }
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8899")!)
    let fixture = try JSONDecoder().decode(BulkFixture.self, from: data)
    if fixture.connection.sharedOriginalDesktop == true {
      app.terminate(); app.launchArguments = []; app.launchChineseTest()
      let live = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
      if XCTWaiter.wait(for: [live], timeout: 20) != .completed { connectLive(fixture.connection, supportsCreation: false, fromSettings: true) }
    } else { connectLive(fixture.connection, supportsCreation: false, fromSettings: true) }
    openWorkspaceManagement()
    if fixture.projectsRemoved { app.segmentedControls["management-kind"].buttons["对话"].tap() }
    let search = app.searchFields.firstMatch
    XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText(fixture.search)
    if app.keyboards.buttons["搜索"].exists { app.keyboards.buttons["搜索"].tap() }
    else if app.keyboards.buttons["Search"].exists { app.keyboards.buttons["Search"].tap() }
    else if app.keyboards.firstMatch.exists { search.typeText("\n") }
    if !fixture.projectsRemoved {
    app.buttons["management-select-all"].tap()
    XCTAssertTrue(app.buttons["management-submit"].label.contains("2"))
    shot("bulk-real-project-selection")
    app.buttons["management-submit"].tap(); app.alerts.buttons["取消"].firstMatch.tap()
    for id in fixture.projectIds { XCTAssertTrue(app.buttons["management-item-" + id].exists) }
    app.buttons["management-submit"].tap(); app.alerts.buttons["删除项目"].firstMatch.tap()
    for id in fixture.projectIds {
      let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["management-item-" + id])
      XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 60), .completed)
    }
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.staticTexts["正在处理批量操作"])
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    } else { for id in fixture.projectIds { XCTAssertFalse(app.buttons["management-item-" + id].exists) } }
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    shot("bulk-real-before-archive-rows")
    for id in fixture.threadIds { XCTAssertTrue(app.buttons["management-item-" + id].waitForExistence(timeout: 15), "Project removal keeps real chats: " + app.debugDescription) }
    app.buttons["management-select-all"].tap()
    XCTAssertTrue(app.buttons["management-submit"].label.contains("2"))
    shot("bulk-real-chat-selection")
    app.buttons["management-submit"].tap(); app.alerts.buttons["归档对话"].firstMatch.tap()
    for id in fixture.threadIds {
      let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["management-item-" + id])
      XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 90), .completed)
    }
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.staticTexts["正在处理批量操作"])], timeout: 30), .completed)
    shot("bulk-real-completed")
    app.terminate(); app.launchArguments = fixture.connection.sharedOriginalDesktop == true ? [] : ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    openWorkspaceManagement()
    let restoredSearch = app.searchFields.firstMatch
    restoredSearch.tap(); restoredSearch.typeText(fixture.search + "\n")
    for id in fixture.projectIds { XCTAssertFalse(app.buttons["management-item-" + id].exists) }
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    for id in fixture.threadIds { XCTAssertFalse(app.buttons["management-item-" + id].exists) }
    XCTAssertGreaterThanOrEqual(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "成功 2 项")).count, fixture.projectsRemoved ? 1 : 2)
    shot("bulk-real-restarted")
  }

  @MainActor func testCodexMachineIdentityLegacySelectionSurvivesRestore() async throws {
    let base = URL(string: "http://127.0.0.1:8826")!
    func read(_ path: String) async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: base.appendingPathComponent(path))
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    let fixture = try await read("fixture")
    let choices = try XCTUnwrap(fixture["choices"] as? [String])
    let keyA = try XCTUnwrap(fixture["keyA"] as? String)
    let keyB = try XCTUnwrap(fixture["keyB"] as? String)
    let canonical = try XCTUnwrap(fixture["canonicalKey"] as? String)
    XCTAssertEqual(choices.count, 3, "Foreign-machine data must not become a selectable source")
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    let chooser = app.buttons["storage-identity-choices"]
    func expectDraft(_ text: String) {
      let expected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        self.composer.exists && self.composer.value as? String == text
      }, object: composer)
      XCTAssertEqual(XCTWaiter.wait(for: [expected], timeout: 20), .completed)
      XCTAssertTrue(chooser.waitForExistence(timeout: 10), "All retained sources stay accessible after selection")
      let send = app.buttons["send-message"]
      if send.exists { XCTAssertFalse(send.isEnabled) }
      else { XCTAssertTrue(app.buttons["composer-busy"].exists); XCTAssertFalse(app.buttons["composer-busy"].isEnabled) }
    }
    func select(_ key: String, text: String) {
      let index = choices.firstIndex(of: key)!
      XCTAssertTrue(chooser.waitForExistence(timeout: 10))
      chooser.tap()
      let item = app.buttons["storage-identity-choice-\(index)"].firstMatch
      XCTAssertTrue(item.waitForExistence(timeout: 10))
      for _ in 0..<2 {
        var previous = CGRect.null
        let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
          guard item.exists, item.isHittable else { return false }
          let frame = item.frame
          defer { previous = frame }
          return !frame.isEmpty && !frame.isNull && frame == previous
        }, object: item)
        XCTAssertEqual(XCTWaiter.wait(for: [stable], timeout: 10), .completed)
        item.tap()
        if item.waitForNonExistence(timeout: 2) { break }
      }
      XCTAssertTrue(item.waitForNonExistence(timeout: 5), "Native source menu must close after an actual UI selection")
      expectDraft(text)
    }
    func persisted(newAccount: Bool, queriedAfter: Int? = nil) async throws {
      for _ in 0..<40 {
        let value = try await read("verify")
        let intact = ["ready", "selectedA", "allDraftsRetained", "originalIntentRetained", "readerRetained"]
          .allSatisfy { value[$0] as? Bool == true }
        if intact && (value["historyQueries"] as? Int ?? 0) > 0
          && (!newAccount || value["currentAccount"] as? String == "current-account-C")
          && (queriedAfter == nil || (value["receiptQueries"] as? Int ?? 0) > queriedAfter!) {
          XCTAssertEqual(value["commandPosts"] as? Int, 0)
          return
        }
        try await Task.sleep(for: .milliseconds(200))
      }
      XCTFail("Original drafts, selected namespace, reader and immutable intent must persist")
    }
    expectDraft("当前的新草稿")
    shot("legacy-ios-canonical-and-original-records")
    select(keyA, text: "第一份原草稿 A")
    XCTAssertFalse(app.buttons["重试原消息"].exists)
    select(keyB, text: "第二份原草稿 B")
    select(canonical, text: "当前的新草稿")
    select(keyA, text: "第一份原草稿 A")
    shot("legacy-ios-all-sources-accessible")
    try await persisted(newAccount: false)
    _ = try await read("account/new")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    expectDraft("第一份原草稿 A")
    try await persisted(newAccount: true)
    let query = app.buttons["查询原回执"]
    XCTAssertTrue(query.waitForExistence(timeout: 10))
    let beforeQuery = try await read("verify")["receiptQueries"] as? Int ?? 0
    query.tap()
    try await persisted(newAccount: true, queriedAfter: beforeQuery)
    XCTAssertFalse(app.buttons["重试原消息"].exists)
    let unknown = app.alerts["无法完成操作"]
    XCTAssertTrue(unknown.waitForExistence(timeout: 10))
    XCTAssertTrue(unknown.staticTexts.allElementsBoundByIndex.contains { $0.label.contains("原结果未知") })
    unknown.buttons["好"].tap()
    select(keyB, text: "第二份原草稿 B")
    select(canonical, text: "当前的新草稿")
    select(keyA, text: "第一份原草稿 A")
    try await persisted(newAccount: true, queriedAfter: beforeQuery)
    shot("legacy-ios-account-change-relaunch-original-query")
  }
  @MainActor func testEasyWorkRealDEVDeviceGrant() async throws {
    guard let configPath = ProcessInfo.processInfo.environment["EASYWORK_GRANT_EXAMINER_CONFIG"] else { throw XCTSkip("Dedicated EWDAP examiner required") }
    let config = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: configPath))) as? [String: Any])
    let port = try XCTUnwrap(config["observerPort"] as? Int)
    let base = URL(string: "http://127.0.0.1:\(port)")!
    func evidence(_ thread: String = "") async throws -> [String: Any] {
      var url = URLComponents(url: base.appendingPathComponent("evidence"), resolvingAgainstBaseURL: false)!
      url.queryItems = [URLQueryItem(name: "native", value: "1"), URLQueryItem(name: "threadId", value: thread)]
      let (data, response) = try await URLSession.shared.data(from: url.url!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      let value = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
      XCTAssertEqual(value["desktopOwnerAlive"] as? Bool, true)
      return value
    }
    func control(_ name: String, bindingId: String? = nil) async throws {
      var request = URLRequest(url: base.appendingPathComponent("control")); request.httpMethod = "POST"
      request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.setValue(try XCTUnwrap(config["authorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      var body = ["operation": name]; if let bindingId { body["bindingId"] = bindingId }
      request.httpBody = try JSONSerialization.data(withJSONObject: body)
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      XCTAssertEqual((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["accepted"] as? Bool, true)
    }
    try await control("desktop.device-offer-create")
    let before = try await evidence()
    let link = try XCTUnwrap((before["deviceOffer"] as? [String: Any])?["deepLink"] as? String)
    app.launchArguments = ["-ui-testing", "-easywork-local-dev"]
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": link]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap(); app.buttons["EasyWork"].firstMatch.tap()
    selectConnectionMethod("扫码连接", agent: "easywork")
    for _ in 0..<5 where !app.buttons["easywork-pair-lan"].isHittable { app.swipeUp() }
    XCTAssertTrue(app.buttons["easywork-pair-lan"].isHittable); app.buttons["easywork-pair-lan"].tap()
    let pending = app.descendants(matching: .any).matching(identifier: "easywork-connecting").firstMatch
    let waiting = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS '确认此设备'"), object: pending)
    XCTAssertEqual(XCTWaiter.wait(for: [waiting], timeout: 60), .completed, app.debugDescription)
    XCTAssertFalse(app.buttons["登录 EasyAI"].exists)
    shot("native-ios-device-authorization-pending")
    try await control("desktop.device-claim-approve")
    let online = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND label CONTAINS 'EasyWork'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [online], timeout: 90), .completed, app.debugDescription)
    chooseDesktopProject(nil)
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["generation-settings"])
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed)
    app.buttons["generation-settings"].tap()
    app.descendants(matching: .any).matching(identifier: "model-picker").firstMatch.tap()
    let model = app.buttons.matching(NSPredicate(format: "label CONTAINS 'GLM-5.2' AND identifier != 'generation-settings'")).firstMatch
    XCTAssertTrue(model.waitForExistence(timeout: 10)); model.tap()
    app.descendants(matching: .any).matching(identifier: "effort-picker").firstMatch.tap(); app.buttons["高"].firstMatch.tap()
    app.navigationBars.buttons["完成"].tap()
    let marker = "IOS_DEVICE_" + UUID().uuidString.prefix(8)
    let text = "Only reply \(marker). Do not call tools or modify files."
    let beforeIds = Set((before["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
    XCTAssertTrue(composer.waitForExistence(timeout: 20), app.debugDescription); composer.tap(); typeComposerText(text)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let keyboardHidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [keyboardHidden], timeout: 8), .completed)
    let send = app.buttons["send-message"]
    let sendReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: send)
    XCTAssertEqual(XCTWaiter.wait(for: [sendReady], timeout: 15), .completed, app.debugDescription)
    tapAISharingAction(send)
    let answer = app.staticTexts[String(marker)].firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 180), app.debugDescription)
    var proof: [String: Any]?
    var bindingId: String?
    for _ in 0..<30 {
      let observed = try await evidence()
      let writes = (observed["requests"] as? [[String: Any]] ?? []).filter {
        guard let request = $0["request"] as? [String: Any], let id = request["requestId"] as? String else { return false }
        return request["method"] as? String == "turn/start" && !beforeIds.contains(id)
      }
      XCTAssertEqual(writes.count, 1)
      let response = try XCTUnwrap(writes.first?["response"] as? [String: Any])
      let result = try XCTUnwrap(response["result"] as? [String: Any]); let runId = try XCTUnwrap(result["runId"] as? String)
      let executions = (observed["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
      if let run = executions.first(where: { $0["id"] as? String == runId }), (run["run"] as? [String: Any])?["status"] as? String == "completed" {
        XCTAssertFalse((run["providerUsage"] as? [[String: Any]] ?? []).isEmpty)
        proof = ["status": "passed", "phoneAccountLoginRequired": false, "modelUsageVerified": true, "independentDesktopVerified": true, "realModelInputs": 1, "transportId": try XCTUnwrap(writes.first?["transportId"] as? String)]
        bindingId = try XCTUnwrap(writes.first?["bindingId"] as? String)
        break
      }
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertNotNil(proof); shot("native-ios-device-grant-real-reply")
    print("EWDAP_IOS_PROOF:" + String(decoding: try JSONSerialization.data(withJSONObject: proof!, options: [.sortedKeys]), as: UTF8.self))
    try await control("desktop.device-binding-revoke", bindingId: try XCTUnwrap(bindingId))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    let revoked = XCTNSPredicateExpectation(predicate: NSPredicate { [self] _, _ in
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS '撤销设备授权'")).count > 0
        && !app.buttons["send-message"].isEnabled
    }, object: app)
    XCTAssertEqual(XCTWaiter.wait(for: [revoked], timeout: 90), .completed, app.debugDescription)
    shot("native-ios-device-authorization-revoked")
    print("EWDAP_IOS_REVOCATION_PROOF:exact-original-desktop-binding-revoked;native-send-disabled;no-credential-export")
  }

  @MainActor func testPhysicalVPNDiscoveryCodexAndEasyWork() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires a physical iPhone connected to the desktop VPN")
    #else
    let environment = ProcessInfo.processInfo.environment
    let expectedData = Data(try XCTUnwrap(environment["VPN_ACCEPTANCE_EXPECTED"]).utf8)
    let expected = try XCTUnwrap(JSONSerialization.jsonObject(with: expectedData) as? [[String: String]])
    let sourceId = try XCTUnwrap(expected.first(where: { $0["source"] == "true" })?["id"])
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
    app.buttons["client-switch"].tap()
    if environment["VPN_EXPECT_UNPAIRED"] == "1" { XCTAssertFalse(app.buttons["client-" + sourceId].exists, "Initial discovery must not require an existing computer connection") }
    let add = app.buttons["add-client"]
    for _ in 0..<8 where !add.isHittable { app.swipeUp() }
    XCTAssertTrue(add.isHittable); add.tap()
    for agent in ["codex", "easywork"] {
      if agent == "easywork" { app.segmentedControls["agent-type"].buttons["EasyWork"].tap() }
      for host in expected where host["agentId"] == agent {
        let id = try XCTUnwrap(host["id"]), endpoint = try XCTUnwrap(host["endpoint"])
        let endpoints = (host["endpoints"] ?? endpoint).split(separator: ",").map(String.init)
        let row = agent == "codex" ? app.buttons["codex-discovered-" + id] : app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", "easywork-discovered-", id)).firstMatch
        let found = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in row.exists && row.label.contains("VPN") && endpoints.contains(row.value as? String ?? "") }, object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [found], timeout: 90), .completed, "Expected a VPN discovery row for " + agent + ":" + id)
      }
      XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "tailscale")).firstMatch.exists)
      XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "首次扫码")).firstMatch.exists)
      shot("physical-vpn-" + agent + "-discovery", fullScreen: true)
      let tree = XCTAttachment(string: app.debugDescription); tree.name = "physical-vpn-" + agent + "-rows"; tree.lifetime = .keepAlways; self.add(tree)
    }
    app.buttons["cancel-client-form"].tap()
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    if environment["VPN_ACCEPTANCE_CONNECT"] == "1" {
      for agent in ["codex", "easywork"] {
        XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20)); app.buttons["client-switch"].tap()
        let addClient = app.buttons["add-client"]
        for _ in 0..<8 where !addClient.isHittable { app.swipeUp() }
        XCTAssertTrue(addClient.isHittable); addClient.tap()
        app.segmentedControls["agent-type"].buttons[agent == "codex" ? "Codex" : "EasyWork"].tap()
        let host = try XCTUnwrap(expected.first { $0["agentId"] == agent && (agent == "easywork" || $0["source"] == "true") })
        let id = try XCTUnwrap(host["id"])
        let row = agent == "codex" ? app.buttons["codex-discovered-" + id] : app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", "easywork-discovered-", id)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 90)); row.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["cancel-client-form"])], timeout: 90), .completed)
        XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.textFields["codex-qr-description"].exists)
        shot("physical-vpn-" + agent + "-automatic-connection", fullScreen: true)
      }
      app.terminate(); app.launchArguments = []; app.activate()
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
      shot("physical-vpn-automatic-connection-relaunch", fullScreen: true)
    }
    #endif
  }
  @MainActor func testPhysicalVPNDiscoveryAndRealModel() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the existing Agent Hub on the user's physical iPhone and Tailscale")
    #else
    let environment = ProcessInfo.processInfo.environment
    let hostId = try XCTUnwrap(environment["VPN_ACCEPTANCE_HOST_ID"])
    let endpoint = try XCTUnwrap(environment["VPN_ACCEPTANCE_ENDPOINT"])
    let endpoints = (environment["VPN_ACCEPTANCE_ENDPOINTS"] ?? endpoint).split(separator: ",").map(String.init)
    let marker = try XCTUnwrap(environment["VPN_ACCEPTANCE_MARKER"])
    let peerIds = try XCTUnwrap(environment["VPN_ACCEPTANCE_PEER_IDS"]).split(separator: ",").map(String.init)
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
    app.buttons["client-switch"].tap()
    let addClient = app.buttons["add-client"]
    for _ in 0..<8 where !addClient.isHittable { app.swipeUp() }
    XCTAssertTrue(addClient.isHittable); addClient.tap()
    let local = app.buttons["codex-discovered-" + hostId]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      local.exists && endpoints.contains(local.value as? String ?? "") && local.label.contains("VPN")
    }, object: local)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 90), .completed, app.debugDescription)
    for peerId in peerIds {
      let peer = app.buttons["codex-discovered-" + peerId]
      XCTAssertTrue(peer.waitForExistence(timeout: 40), "The real VPN peer must be described independently")
      XCTAssertTrue(peer.label.contains("VPN"), peer.debugDescription)
      let value = peer.value as? String ?? ""
      XCTAssertTrue(value.hasPrefix("https://100.") || value.hasPrefix("https://[fd7a:115c:a1e0:"), value)
    }
    shot("physical-tailscale-automatic-discovery", fullScreen: true)
    local.tap()
    XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 60))
    XCTAssertFalse(app.textFields["client-address"].exists)
    app.buttons["new-chat"].tap()
    let projectless = app.buttons["choose-projectless"]
    XCTAssertTrue(projectless.waitForExistence(timeout: 20)); projectless.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 15)); composer.tap()
    composer.typeText("Reply only \(marker). Do not use tools.")
    app.buttons["composer-dismiss-keyboard"].tap()
    let keyboardHidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [keyboardHidden], timeout: 8), .completed)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    XCTAssertFalse(app.buttons["send-message"].frame.intersects(app.buttons["voice-input"].frame))
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 240), app.debugDescription)
    shot("physical-tailscale-real-model", fullScreen: true)
    app.terminate(); app.launchArguments = []; app.activate()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 40))
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    let recovered = app.buttons["codex-discovered-" + hostId]
    let recoveredVPN = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in recovered.exists && recovered.label.contains("VPN") }, object: recovered)
    XCTAssertEqual(XCTWaiter.wait(for: [recoveredVPN], timeout: 90), .completed)
    shot("physical-tailscale-relaunch-discovery", fullScreen: true)
    app.buttons["cancel-client-form"].tap()
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    #endif
  }
  @MainActor func testPhysicalEasyWorkInspectExistingConnection() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the user's existing Agent Hub on a physical iPhone")
    #else
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 25))
    if app.alerts.firstMatch.exists {
      shot("easywork-physical-initial-error", fullScreen: true)
      let text = XCTAttachment(string: app.debugDescription); text.name = "easywork-physical-initial-state"; text.lifetime = .keepAlways; add(text)
      app.alerts.firstMatch.buttons.firstMatch.tap()
    }
    app.buttons["client-switch"].tap()
    let clients = XCTAttachment(string: app.debugDescription); clients.name = "easywork-physical-client-identifiers"; clients.lifetime = .keepAlways; add(clients)
    let easywork = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS[c] %@", "client-", "EasyWork")).firstMatch
    XCTAssertTrue(easywork.waitForExistence(timeout: 10), "The phone must retain its actual EasyWork connection")
    easywork.tap()
    sleep(12)
    shot("easywork-physical-current-chat", fullScreen: true)
    app.buttons["open-sidebar"].tap()
    let sidebar = XCTAttachment(string: app.debugDescription); sidebar.name = "easywork-physical-thread-identifiers"; sidebar.lifetime = .keepAlways; add(sidebar)
    shot("easywork-physical-real-sidebar", fullScreen: true)
    #endif
  }

  @MainActor func testCodexDualChannelRealDEV() async throws {
    let base = URL(string: "http://127.0.0.1:8797")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    let marker = try XCTUnwrap(metadata["marker"] as? String)
    func control(_ action: String) async throws {
      var request = URLRequest(url: base.appendingPathComponent(action)); request.httpMethod = "POST"
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    func waitRoute(_ route: String) async throws {
      for _ in 0..<90 {
        let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("state"))
        let state = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        if (state["observed"] as? String) == route { return }
        try await Task.sleep(for: .seconds(1))
      }
      XCTFail("Expected an authenticated read on " + route)
    }
    // Start through the legacy, manually saved Serve address, before it has a private certificate.
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    let projectless = app.buttons["choose-projectless"]
    XCTAssertTrue(projectless.waitForExistence(timeout: 20)); projectless.tap()
    app.buttons["generation-settings"].tap()
    let effort = app.sliders["effort-slider"]
    XCTAssertTrue(effort.waitForExistence(timeout: 20))
    effort.adjust(toNormalizedSliderPosition: CGFloat(try XCTUnwrap(fixture.effortPosition)))
    app.buttons["generation-done"].tap()
    focusLiveComposer(); typeComposerText("Reply only \(marker)_FIRST. Do not use any tools."); sendLiveComposer()
    XCTAssertTrue(app.staticTexts[marker + "_FIRST"].waitForExistence(timeout: 180))
    let draft = "UNSENT_DUAL_DRAFT_" + fixture.nonce
    focusLiveComposer(); typeComposerText(draft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    try await control("serve-off"); try await waitRoute("lan")
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertTrue(app.staticTexts[marker + "_FIRST"].exists)
    shot("dual-legacy-serve-to-lan-draft-preserved")
    XCUIDevice.shared.press(.home); try await Task.sleep(for: .seconds(2))
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.staticTexts[marker + "_FIRST"].waitForExistence(timeout: 40))
    XCTAssertEqual(composer.value as? String, draft)
    try await control("serve-on"); try await control("lan-off"); try await waitRoute("serve")
    XCTAssertEqual(composer.value as? String, draft)
    shot("dual-lan-to-serve-draft-preserved")
    XCUIDevice.shared.press(.home); try await Task.sleep(for: .seconds(2))
    app.terminate(); app.launchChineseTest()
    XCTAssertTrue(app.staticTexts[marker + "_FIRST"].waitForExistence(timeout: 40))
    XCTAssertEqual(composer.value as? String, draft)
    try await control("lan-on"); try await control("serve-off-after-write")
    clearComposer(); typeComposerText("Reply only \(marker)_SECOND. Do not use any tools."); sendLiveComposer()
    try await waitRoute("lan")
    XCTAssertTrue(app.staticTexts[marker + "_SECOND"].waitForExistence(timeout: 180))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    focusLiveComposer(); typeComposerText(draft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    try await control("restart")
    try await waitRoute("lan")
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertTrue(app.staticTexts[marker + "_SECOND"].exists)
    shot("dual-host-restart-and-single-input-preserved")
  }

  @MainActor private func prepareOwnAcceptanceNetworkPermission() {
    #if !targetEnvironment(simulator)
    // Recover only this task's independent bundle after XCTest's default
    // interruption handler denied its wireless-data prompt. The existing DEBUG
    // settings entry opens the exact active App, never a name-matched Settings row.
    guard let bundleId = ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"],
      bundleId.hasPrefix("com.easyai.agenthub.") else { XCTFail("Require the verified independent acceptance bundle"); return }
    app.launchArguments = ["-ui-testing", "-voice-open-settings"]
    app.launchChineseTest()
    let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
    XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 15))
    if name.contains("testFileInteractionFeedbackDEV") {
      let localNetwork = settings.switches["本地网络"].firstMatch
      if localNetwork.exists && (localNetwork.value as? String) == "0" { localNetwork.tap() }
      print("FILE_APP_OWN_NETWORK_SETTINGS", settings.debugDescription)
    }
    let wireless = settings.buttons[bundleId + ".wireless"]
    XCTAssertTrue(wireless.waitForExistence(timeout: 10), settings.debugDescription)
    wireless.tap()
    let networkOptions = name.contains("testFileInteractionFeedbackDEV")
      ? ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "WLAN & Cellular", "Wi-Fi & Mobile Data"]
      : ["无线局域网", "WLAN", "Wi-Fi"]
    let wifi = settings.staticTexts.matching(NSPredicate(format: "label IN %@", networkOptions)).firstMatch
    if name.contains("testFileInteractionFeedbackDEV"), !wifi.waitForExistence(timeout: 5), wireless.exists {
      // A first-launch network prompt can consume the first Settings tap.
      wireless.tap()
    }
    XCTAssertTrue(wifi.waitForExistence(timeout: 5)); wifi.tap()
    if name.contains("testFileInteractionFeedbackDEV") {
      XCTAssertTrue(settings.cells["3"].isSelected, settings.debugDescription)
      settings.navigationBars.buttons.firstMatch.tap()
      let localNetwork = settings.switches["本地网络"].firstMatch
      if localNetwork.exists && (localNetwork.value as? String) == "0" { localNetwork.tap() }
      print("FILE_APP_OWN_NETWORK_SETTINGS_AFTER", settings.debugDescription)
    }
    app.terminate(); app.launchArguments = ["-ui-testing"]; app.launchChineseTest()
    #endif
    allowAcceptanceLocalNetworkPermission()
  }

  @MainActor private func allowAcceptanceLocalNetworkPermission() {
    #if !targetEnvironment(simulator)
    let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    for _ in 0..<3 {
      // Avoid indexing an Alert whose modern AX representation is a Sheet.
      let title = system.staticTexts.matching(NSPredicate(format:
        "(label CONTAINS[c] '本地网络' OR label CONTAINS[c] 'local network' OR label CONTAINS '无线数据') AND (label CONTAINS 'Agent Hub' OR label CONTAINS 'CodexManageUITests-Runner')")).firstMatch
      guard title.waitForExistence(timeout: 2) else { return }
      let allow = system.buttons.matching(NSPredicate(format: "label IN %@", ["仅无线局域网", "无线局域网与蜂窝网络", "允许", "Allow"])).firstMatch
      XCTAssertTrue(allow.waitForExistence(timeout: 3)); allow.tap()
    }
    #endif
  }

  @MainActor func testCodexDirectDiscoveryRealDEV() async throws {
    prepareOwnAcceptanceNetworkPermission()
    let base = URL(string: ProcessInfo.processInfo.environment["HOST_KEY_DEV_CONTROL_URL"] ?? "http://127.0.0.1:8797")!
    func control(_ path: String = "", method: String = "GET") async throws -> (Data, URLResponse) {
      var request = URLRequest(url: base.appendingPathComponent(path)); request.httpMethod = method
      if let token = ProcessInfo.processInfo.environment["HOST_KEY_DEV_CONTROL_TOKEN"] { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
      do { return try await URLSession.shared.data(for: request) }
      catch let failure as URLError where method == "GET" && failure.code == .notConnectedToInternet {
        allowAcceptanceLocalNetworkPermission()
        return try await URLSession.shared.data(for: request)
      }
    }
    let fixture: [String: String]
    if let encoded = ProcessInfo.processInfo.environment["HOST_KEY_DEV_FIXTURE"], let data = Data(base64Encoded: encoded) {
      fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    } else {
      let (data, _) = try await control()
      fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    }
    let hostId = try XCTUnwrap(fixture["hostId"]), hostKey = try XCTUnwrap(fixture["hostKey"]), marker = try XCTUnwrap(fixture["marker"])
    app.buttons["client-switch"].tap()
    let add = app.buttons["add-client"]
    for _ in 0..<6 { if add.isHittable { break }; app.swipeUp() }
    add.tap()
    let row = app.buttons["codex-discovered-" + hostId]
    XCTAssertTrue(row.waitForExistence(timeout: 30), "The real Host must appear through Bonjour")
    row.tap()
    let keyField = app.secureTextFields["codex-host-key"]
    XCTAssertTrue(keyField.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertFalse(app.buttons["codex-authorize-connect"].isEnabled)
    if let nonce = fixture["stageNonce"], ProcessInfo.processInfo.environment["HOST_KEY_DEV_FIXTURE"] != nil {
      print("HOST_KEY_DEV_\(nonce)_NO_CLAIM"); fflush(stdout)
    } else {
      let (before, _) = try await control("stats")
      XCTAssertEqual((try JSONSerialization.jsonObject(with: before) as? [String: Int])?["claimPosts"], 0)
    }
    app.buttons["codex-authorize-cancel"].tap()
    XCTAssertFalse(keyField.exists)
    app.buttons["重新查找"].tap()
    selectConnectionMethod("手动连接", agent: "codex")
    XCTAssertTrue(app.textFields["client-address"].waitForExistence(timeout: 10))
    selectConnectionMethod("自动发现", agent: "codex")
    XCTAssertFalse(keyField.exists, "Discovery must not reopen a canceled authorization")
    row.tap()
    XCTAssertTrue(keyField.waitForExistence(timeout: 12))
    keyField.tap(); keyField.typeText("wrong-host-key-with-at-least-32-characters")
    app.buttons["codex-authorize-connect"].tap()
    XCTAssertTrue(app.staticTexts["client-form-error"].waitForExistence(timeout: 20))
    XCTAssertEqual(app.staticTexts["client-form-error"].label, "Host Key 不正确，请在电脑端核对后重试")
    shot("host-key-wrong-key-rejected")
    app.buttons["codex-authorize-cancel"].tap(); row.tap()
    XCTAssertTrue(keyField.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertFalse(app.buttons["codex-authorize-connect"].isEnabled)
    keyField.tap(); keyField.typeText(hostKey)
    app.buttons["codex-authorize-connect"].tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: keyField)
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 90), .completed, app.debugDescription)
    shot("host-key-after-authorized")
    XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 20), app.debugDescription)
    app.buttons["new-chat"].tap()
    let projectless = app.buttons["choose-projectless"]
    XCTAssertTrue(projectless.waitForExistence(timeout: 20)); projectless.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 15)); composer.tap()
    let prompt = "Reply only \(marker). Do not use any tools."
    typeComposerText(prompt)
    XCTAssertEqual(composer.value as? String, prompt, "The physical keyboard must deliver the entire immutable input before sending")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 180), app.debugDescription)
    shot("host-key-authorized-real-model")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 30))
    if let nonce = fixture["stageNonce"], ProcessInfo.processInfo.environment["HOST_KEY_DEV_FIXTURE"] != nil {
      print("HOST_KEY_DEV_\(nonce)_RESTART"); fflush(stdout)
    } else {
      let (_, response) = try await control("restart", method: "POST")
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    let known = app.buttons["codex-discovered-" + hostId]
    XCTAssertTrue(known.waitForExistence(timeout: 40))
    if let previousPort = fixture["originalDiscoveryPort"], ProcessInfo.processInfo.environment["HOST_KEY_DEV_FIXTURE"] != nil {
      let moved = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard let address = known.value as? String else { return false }
        return address.hasPrefix("https://") && !address.contains(":" + previousPort)
      }, object: known)
      XCTAssertEqual(XCTWaiter.wait(for: [moved], timeout: 60), .completed, "Observe the actual restarted Host's new discovery endpoint")
    }
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS '已连接' OR label CONTAINS '未连接'"), object: known)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed)
    known.tap()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 40))
    XCTAssertFalse(app.alerts.firstMatch.exists, app.debugDescription)
    shot("codex-bonjour-host-restart-preserved-task")
  }

  @MainActor func testCodexM5AndLegacyDiscoveryRealDEV() async throws {
    prepareOwnAcceptanceNetworkPermission()
    let base = URL(string: ProcessInfo.processInfo.environment["HOST_KEY_DEV_CONTROL_URL"] ?? "http://127.0.0.1:8797")!
    var request = URLRequest(url: base)
    if let token = ProcessInfo.processInfo.environment["HOST_KEY_DEV_CONTROL_TOKEN"] { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
    let fixture: [String: String]
    if let encoded = ProcessInfo.processInfo.environment["HOST_KEY_DEV_FIXTURE"], let data = Data(base64Encoded: encoded) {
      fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    } else {
      let (data, _) = try await URLSession.shared.data(for: request)
      fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    }
    let remoteId = try XCTUnwrap(fixture["remoteHostId"]), remoteKey = try XCTUnwrap(fixture["remoteHostKey"])
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    let remote = app.buttons["codex-discovered-" + remoteId]
    XCTAssertTrue(remote.waitForExistence(timeout: 90), "The actual M5 must be discovered without an injected address or credential")
    remote.tap()
    let key = app.secureTextFields["codex-host-key"]
    XCTAssertTrue(key.waitForExistence(timeout: 20), app.debugDescription)
    app.buttons["codex-authorize-cancel"].tap()
    XCTAssertFalse(key.exists)
    selectConnectionMethod("手动连接", agent: "codex")
    XCTAssertTrue(app.textFields["client-address"].waitForExistence(timeout: 10))
    selectConnectionMethod("自动发现", agent: "codex")
    XCTAssertFalse(key.exists)
    if let legacyId = fixture["legacyHostId"] {
      let legacy = app.buttons["codex-discovered-" + legacyId]
      XCTAssertTrue(legacy.waitForExistence(timeout: 45)); legacy.tap()
      XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 60))
      XCTAssertFalse(key.exists, "Legacy automatic must connect without the Host Key card")
      shot("legacy-automatic-connected-without-key")
      app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    }
    XCTAssertTrue(remote.waitForExistence(timeout: 60)); remote.tap()
    XCTAssertTrue(key.waitForExistence(timeout: 20), app.debugDescription); key.tap(); key.typeText(remoteKey)
    app.buttons["codex-authorize-connect"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: key)], timeout: 90), .completed)
    XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 30))
    shot("actual-m5-key-authorized")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30)); app.buttons["client-switch"].tap()
    let add = app.buttons["add-client"]
    for _ in 0..<8 { if add.exists && add.isHittable { break }; app.swipeUp() }
    XCTAssertTrue(add.waitForExistence(timeout: 15)); XCTAssertTrue(add.isHittable); add.tap()
    XCTAssertTrue(remote.waitForExistence(timeout: 60)); remote.tap()
    XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 60))
    XCTAssertFalse(key.exists); XCTAssertFalse(app.alerts.firstMatch.exists)
    shot("actual-m5-reconnected-without-key")
  }

  @MainActor func testCodexM5ReconnectAfterRestoreRealDEV() async throws {
    let id = try XCTUnwrap(ProcessInfo.processInfo.environment["HOST_KEY_DEV_REMOTE_HOST_ID"])
    let name = try XCTUnwrap(ProcessInfo.processInfo.environment["HOST_KEY_DEV_REMOTE_NAME"])
    let selector = app.buttons["client-switch"]
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      selector.exists && selector.isHittable && selector.isEnabled && selector.label.contains(name)
        && (selector.value as? String)?.contains("实时连接") == true
    }, object: selector)
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 60), .completed, app.debugDescription)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertFalse(app.secureTextFields["codex-host-key"].exists)
    selector.tap()
    let add = app.buttons["add-client"]
    for _ in 0..<8 { if add.exists && add.isHittable { break }; app.swipeUp() }
    XCTAssertTrue(add.waitForExistence(timeout: 15)); XCTAssertTrue(add.isHittable); add.tap()
    let remote = app.buttons["codex-discovered-" + id]
    XCTAssertTrue(remote.waitForExistence(timeout: 60)); remote.tap()
    XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 60))
    XCTAssertFalse(app.secureTextFields["codex-host-key"].exists)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    shot("actual-m5-reconnected-without-key")
  }

  @MainActor func testVPNDiscoveryUnknownPeersRealDEV() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8797")!)
    let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    let uri = try XCTUnwrap(fixture["uri"]), marker = try XCTUnwrap(fixture["marker"])
    let peers = try XCTUnwrap(fixture["peerIds"]).split(separator: ",").map(String.init)
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    selectConnectionMethod("扫码连接", agent: "codex")
    let qr = app.textFields["codex-qr-description"]
    XCTAssertTrue(qr.waitForExistence(timeout: 15)); qr.tap(); qr.typeText(uri)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.buttons["codex-connect-qr"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: qr)], timeout: 90), .completed)
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    for id in peers {
      let peer = app.buttons["codex-discovered-" + id]
      let discovered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        peer.exists && peer.label.contains("VPN") && peer.label.contains("点选连接")
      }, object: peer)
      XCTAssertEqual(XCTWaiter.wait(for: [discovered], timeout: 90), .completed, "An unsaved real computer must appear through VPN discovery")
    }
    shot("vpn-real-unsaved-peer-discovery")
    app.buttons["cancel-client-form"].tap(); app.buttons["完成"].tap()
    app.buttons["new-chat"].tap(); app.buttons["choose-projectless"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 15)); composer.tap()
    composer.typeText("Reply only \(marker). Do not use tools.")
    app.buttons["composer-dismiss-keyboard"].tap()
    let keyboardHidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [keyboardHidden], timeout: 8), .completed)
    let send = app.buttons["send-message"]
    XCTAssertTrue(send.isEnabled)
    XCTAssertFalse(send.frame.intersects(app.buttons["voice-input"].frame))
    send.tap()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 180), app.debugDescription)
    shot("vpn-real-desktop-model")
  }

  @MainActor func testPhysicalEasyWorkHistorySwitching() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the user's existing Agent Hub on a physical iPhone")
    #else
    let environment = ProcessInfo.processInfo.environment
    let clientId = try XCTUnwrap(environment["EASYWORK_IPHONE_CLIENT_ID"])
    let longId = try XCTUnwrap(environment["EASYWORK_IPHONE_LONG_THREAD_ID"])
    let shortId = try XCTUnwrap(environment["EASYWORK_IPHONE_SHORT_THREAD_ID"])
    let longTitle = try XCTUnwrap(environment["EASYWORK_IPHONE_LONG_TITLE"])
    let shortTitle = try XCTUnwrap(environment["EASYWORK_IPHONE_SHORT_TITLE"])
    switchClient(clientId)
    for index in 0..<10 {
      let isLong = index.isMultiple(of: 2)
      let search = openDesktopSidebarSearch()
      if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
      search.tap(); search.typeText((isLong ? longTitle : shortTitle) + "\n")
      let row = app.buttons["recent-thread-" + (isLong ? longId : shortId)]
      XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
      let messages = app.scrollViews["message-list"]
      XCTAssertTrue(messages.waitForExistence(timeout: 30))
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        self.app.alerts.firstMatch.exists || self.app.buttons["copy-reply"].firstMatch.exists
      }, object: app)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
      if app.alerts.firstMatch.exists {
        shot("easywork-physical-switch-failure-\(index)", fullScreen: true)
        let failure = XCTAttachment(string: app.debugDescription); failure.name = "easywork-physical-switch-failure"; failure.lifetime = .keepAlways; add(failure)
      }
      XCTAssertFalse(app.alerts.firstMatch.exists, "Switching real histories must not exceed the transport message limit")
      XCTAssertFalse(app.staticTexts["此对话暂时没有可显示的文字消息。"].exists, "The independently verified non-empty chat must render its messages")
      let state = app.buttons["client-switch"].value as? String ?? ""
      XCTAssertFalse(state.contains("离线"), "A readable live EasyWork thread must keep its connection")
      shot("easywork-physical-switch-\(index)", fullScreen: true)
    }
    #endif
  }
  @MainActor func testEasyWorkRealDEVPromptSuggestion() async throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["PROMPT_SUGGESTION_FIXTURE_JSON"])
    let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    let threadId = try XCTUnwrap(fixture["threadId"] as? String)
    let expected = try XCTUnwrap(fixture["text"] as? String)
    app.launchArguments = ["-ui-testing", "-easywork-local-dev"]
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": try XCTUnwrap(fixture["pairingUri"] as? String)]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    app.buttons["EasyWork"].firstMatch.tap(); selectConnectionMethod("扫码连接", agent: "easywork")
    let pair = app.buttons["easywork-pair-lan"]
    XCTAssertTrue(pair.waitForExistence(timeout: 10)); pair.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["new-chat"])], timeout: 120), .completed)
    if !app.buttons["recent-thread-" + threadId].isHittable { app.buttons["open-sidebar"].tap() }
    let row = app.buttons["recent-thread-" + threadId]
    XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
    let adopt = app.buttons["suggestion-adopt"]
    XCTAssertTrue(adopt.waitForExistence(timeout: 60), "Read the real EasyWork runtime prediction")
    expectSuggestionDraft("")
    shot("easywork-runtime-suggestion-preview")
    adopt.tap()
    expectSuggestionDraft(expected)
    XCTAssertFalse(app.buttons["suggestion-adopt"].exists)
    shot("easywork-runtime-suggestion-adopted")
    let undo = app.buttons["suggestion-undo"]
    XCTAssertTrue(undo.waitForExistence(timeout: 10)); undo.tap()
    expectSuggestionDraft("")
    XCTAssertTrue(adopt.waitForExistence(timeout: 15))
    shot("easywork-runtime-suggestion-undo")
    print("EASYWORK_RUNTIME_SUGGESTION_PASSED", threadId)
  }

  @MainActor func testEasyWorkReceivePerformanceDEV() async throws {
    let configPath = ProcessInfo.processInfo.environment["EASYWORK_RECEIVE_HOST_CONFIG"]
    let config = try configPath.map { try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: $0))) as? [String: Any]) }
    let observerPort = config?["observerPort"] as? Int ?? 8809
    func control(_ operation: String) async throws {
      var request = URLRequest(url: URL(string: "http://127.0.0.1:\(observerPort)/control")!)
      request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
      request.setValue(try XCTUnwrap(config?["authorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      XCTAssertEqual((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["accepted"] as? Bool, true)
    }
    func evidence() async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:\(observerPort)/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    if config != nil { try await control("desktop.device-offer-create") }
    let initial = try await evidence()
    let lan = try XCTUnwrap(initial["lan"] as? [String: Any])
    let offer = try XCTUnwrap(initial["relayOffer"] as? [String: Any])
    let nonce = try XCTUnwrap(config?["receiveNonce"] as? String ?? initial["receiveNonce"] as? String)
    app.launchArguments = ["-ui-testing", "-easywork-local-dev"]
    if initial["nativeQueue"] as? Bool == true { app.launchArguments.append("-native-queue-dev") }
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": try XCTUnwrap(config == nil ? lan["pairingUri"] as? String : (initial["deviceOffer"] as? [String: Any])?["deepLink"] as? String),
      "EASYWORK_DEV_RELAY_URL": try XCTUnwrap(config.map { "http://127.0.0.1:\($0["relayPort"] as! Int)" } ?? initial["relayURL"] as? String),
      "EASYWORK_DEV_RELAY_CODE": try XCTUnwrap(offer["pairingCode"] as? String),
      "EASYWORK_NATIVE_QUEUE_NONCE": nonce]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 25))
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    app.buttons["EasyWork"].firstMatch.tap(); selectConnectionMethod("扫码连接", agent: "easywork")
    XCTAssertTrue(app.buttons["easywork-pair-lan"].waitForExistence(timeout: 10)); app.buttons["easywork-pair-lan"].tap()
    if config != nil {
      let pending = app.descendants(matching: .any).matching(identifier: "easywork-connecting").firstMatch
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS '确认此设备'"), object: pending)], timeout: 60), .completed)
      XCTAssertFalse(app.buttons["登录 EasyAI"].exists)
      shot("native-receive-device-authorization-pending")
      try await control("desktop.device-claim-approve")
    }
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["new-chat"])], timeout: 120), .completed)
    if config == nil {
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    app.buttons["EasyWork"].firstMatch.tap(); selectConnectionMethod("通过中继连接", agent: "easywork")
    for _ in 0..<3 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
    XCTAssertTrue(app.buttons["easywork-dev-login"].waitForExistence(timeout: 10)); app.buttons["easywork-dev-login"].tap()
    let relayState = try await evidence()
    let relayOffer = try XCTUnwrap(relayState["relayOffer"] as? [String: Any])
    replaceText(app.secureTextFields["easywork-relay-code"], with: try XCTUnwrap(relayOffer["pairingCode"] as? String))
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.swipeUp(); app.buttons["easywork-pair-relay"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])], timeout: 120), .completed)
    }
    let queueRoutes = initial["queueRoutes"] as? [String] ?? ["lan", "relay"]
    for (route, preference) in [("lan", "仅直连"), ("relay", "仅中继")] where queueRoutes.contains(route) {
      app.buttons["client-switch"].tap()
      let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
      XCTAssertTrue(edit.waitForExistence(timeout: 15)); edit.tap()
      app.descendants(matching: .any)["connection-preference"].firstMatch.tap()
      XCTAssertTrue(app.buttons[preference].firstMatch.waitForExistence(timeout: 10)); app.buttons[preference].firstMatch.tap()
      app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
      chooseDesktopProject(nil)
      let generation = app.buttons["generation-settings"]
      XCTAssertTrue(generation.waitForExistence(timeout: 25)); generation.tap()
      let catalogueState = try await evidence()
      let catalogueRequests = catalogueState["requests"] as? [[String: Any]] ?? []
      let catalogue = catalogueRequests.last { ($0["request"] as? [String: Any])?["method"] as? String == "model/list" }
      let models = ((catalogue?["response"] as? [String: Any])?["result"] as? [String: Any])?["models"] as? [[String: Any]] ?? []
      let model = try XCTUnwrap(models.first { $0["isDefault"] as? Bool == true })
      let available = model["supportedEfforts"] as? [String] ?? []
      let effort = available.contains("xhigh") ? "xhigh" : available.last
      if let effort {
        let labels = ["none": "无", "minimal": "最小", "low": "低", "medium": "中", "high": "高", "xhigh": "极高", "max": "最高", "ultra": "超高"]
        let picker = app.descendants(matching: .any)["effort-picker"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 15)); picker.tap()
        let choice = app.buttons[labels[effort] ?? effort]
        XCTAssertTrue(choice.waitForExistence(timeout: 10)); choice.tap()
      }
      print("EASYWORK_RECEIVE_REASONING", route, effort ?? "not-supported")
      app.buttons["generation-done"].tap()
      if initial["nativeQueue"] as? Bool == true {
        func controlQueue(_ operation: String, threadId: String, messageId: String? = nil) async throws {
          var request = URLRequest(url: URL(string: "http://127.0.0.1:8809/control")!); request.httpMethod = "POST"
          request.setValue("application/json", forHTTPHeaderField: "Content-Type")
          var command: [String: Any] = ["operation": operation, "threadId": threadId]
          if let messageId { command["messageId"] = messageId }
          request.httpBody = try JSONSerialization.data(withJSONObject: command)
          let (data, response) = try await URLSession.shared.data(for: request)
          XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
          let id = try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Any])?["id"] as? String)
          for _ in 0..<40 {
            if let result = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) {
              XCTAssertEqual(result["accepted"] as? Bool, true, String(describing: result)); return
            }
            try await Task.sleep(for: .milliseconds(500))
          }
          XCTFail("Native queue control timed out")
        }
        let marker = "EASYWORK_NATIVE_QUEUE_" + route + "_" + nonce
        focusLiveComposer()
        composer.typeText("专用原生队列验收。不调用工具。连续完整生成从 0001 到 0200 的编号行，每行写出编号及这句文字：正在核对手机和电脑的原生队列同步。必须逐行生成，不省略、不使用代码块、不提前结束。全部完成后单独一行输出 " + marker + "_ORIGIN。")
        sendLiveComposer()
        XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 120), app.debugDescription)
        approveEasyWorkFixtureIfNeeded()
        let batch = app.buttons["native-queue-dev-batch"]
        XCTAssertTrue(batch.exists && batch.isEnabled)
        batch.tap()
        var queuedRequests: [[String: Any]] = []
        for _ in 0..<40 {
          let protocolRequests = (try await evidence())["requests"] as? [[String: Any]] ?? []
          queuedRequests = protocolRequests.compactMap { $0["request"] as? [String: Any] }.filter { request in
            guard request["method"] as? String == "turn/start", let params = request["params"] as? [String: Any], params["mode"] as? String == "queue" else { return false }
            return (params["parts"] as? [[String: Any]] ?? []).contains { (($0["data"] as? [String: Any])?["text"] as? String ?? "").contains(marker) }
          }
          if queuedRequests.count == 3 { break }
          try await Task.sleep(for: .milliseconds(500))
        }
        XCTAssertEqual(queuedRequests.count, 3)
        let params = queuedRequests.compactMap { $0["params"] as? [String: Any] }
        let ids = try params.map { try XCTUnwrap($0["clientRequestId"] as? String) }
        let threadId = try XCTUnwrap(params.first?["threadId"] as? String)
        for id in ids {
          let item = app.buttons[(initial["clientOwnedQueue"] as? Bool == true ? "cancel-queued-" : "execute-queued-") + id]
          XCTAssertTrue(item.exists || item.waitForExistence(timeout: 20))
        }
        try await controlQueue("desktop.queue-capture", threadId: threadId)
        let views = ((try await evidence())["independentDesktop"] as? [String: Any])?["queueViews"] as? [[String: Any]] ?? []
        let rows = try XCTUnwrap(views.last?["items"] as? [[String: Any]])
        XCTAssertEqual(rows.compactMap { $0["messageId"] as? String }, ids)
        shot("native-queue-easywork-" + route + "-both-screens")
        if initial["clientOwnedQueue"] as? Bool == true {
          try await controlQueue("desktop.queue-cancel", threadId: threadId, messageId: ids[2])
          XCTAssertTrue(app.buttons["cancel-queued-" + ids[2]].waitForNonExistence(timeout: 25))
          let cancel = app.buttons["cancel-queued-" + ids[1]]
          XCTAssertTrue(cancel.isEnabled); cancel.tap()
          XCTAssertTrue(cancel.waitForNonExistence(timeout: 25))
          XCTAssertEqual(app.staticTexts["queued-number-" + ids[0]].label, "1")
          let done = app.staticTexts.matching(NSPredicate(format: "label == %@", marker + "_FIFO")).firstMatch
          let deadline = Date().addingTimeInterval(300)
          while !done.exists && Date() < deadline { approveEasyWorkFixtureIfNeeded(); try await Task.sleep(for: .seconds(1)) }
          XCTAssertTrue(done.exists, app.debugDescription)
          XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 30))
          XCTAssertFalse(app.buttons["cancel-queued-" + ids[0]].exists)
          shot("client-owned-queue-easywork-" + route + "-consumed")
          print("EASYWORK_CLIENT_QUEUE_PASSED", route, threadId)
          continue
        }
        let promote = app.buttons["execute-queued-" + ids[1]]
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: promote)], timeout: 60), .completed, app.debugDescription)
        promote.tap()
        let promoted = app.buttons["execute-queued-" + ids[1]].waitForNonExistence(timeout: 40)
        print("EASYWORK_QUEUE_ACTION", route, app.staticTexts["agent-dev-target"].value as? String ?? "")
        XCTAssertTrue(promoted, app.debugDescription)
        try await controlQueue("desktop.queue-cancel", threadId: threadId, messageId: ids[2])
        XCTAssertTrue(app.buttons["cancel-queued-" + ids[2]].waitForNonExistence(timeout: 25))
        XCTAssertEqual(app.staticTexts["queued-number-" + ids[0]].label, "1")
        shot("native-queue-easywork-" + route + "-phone-promote-desktop-cancel")
        let done = app.staticTexts.matching(NSPredicate(format: "label == %@", marker + "_FIFO")).firstMatch
        let deadline = Date().addingTimeInterval(300)
        while !done.exists && Date() < deadline { approveEasyWorkFixtureIfNeeded(); try await Task.sleep(for: .seconds(1)) }
        XCTAssertTrue(done.exists, app.debugDescription)
        XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 30))
        XCTAssertTrue(app.buttons["execute-queued-" + ids[0]].waitForNonExistence(timeout: 15))
        print("EASYWORK_NATIVE_QUEUE_PASSED", route, threadId)
        continue
      }
      let start = "EASYWORK_RECEIVE_\(route)_\(nonce)_BEGIN"
      let end = "EASYWORK_RECEIVE_\(route)_\(nonce)_DONE"
      focusLiveComposer()
      composer.typeText("专用接收性能验收。先单独原样输出 \(start)。随后用终端工具执行 sleep 2，不读写文件。最后分20行说明网络性能，每行约40字，最后一行单独原样输出 \(end)。不要增加其他结语。")
      print("EASYWORK_RECEIVE_UI", route, "send", Date().timeIntervalSince1970)
      sendLiveComposer()
      let first = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", start)).firstMatch
      let firstDeadline = Date().addingTimeInterval(240)
      while !first.exists && Date() < firstDeadline {
        approveEasyWorkFixtureIfNeeded()
        try await Task.sleep(for: .seconds(1))
      }
      XCTAssertTrue(first.exists)
      print("EASYWORK_RECEIVE_UI", route, "first", Date().timeIntervalSince1970)
      let final = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", end)).firstMatch
      let deadline = Date().addingTimeInterval(300)
      while !final.exists && Date() < deadline {
        approveEasyWorkFixtureIfNeeded()
        try await Task.sleep(for: .seconds(1))
      }
      XCTAssertTrue(final.exists)
      print("EASYWORK_RECEIVE_UI", route, "final", Date().timeIntervalSince1970)
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])], timeout: 60), .completed)
      let state = try await evidence()
      let requests = state["requests"] as? [[String: Any]] ?? []
      XCTAssertTrue(requests.contains { entry in
        let request = entry["request"] as? [String: Any]
        let parts = (request?["params"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
        return entry["transportId"] as? String == (route == "lan" ? "lan-wss" : "relay-http") && request?["method"] as? String == "turn/start" && String(describing: parts).contains(start)
      })
      shot("native-receive-" + route + "-real-completed")
      print("EASYWORK_RECEIVE_SCENE_PASSED", route)
    }
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
    let lastRoute = queueRoutes.contains("relay") ? "relay" : "lan"
    let restoreMarker = initial["nativeQueue"] as? Bool == true
      ? "EASYWORK_NATIVE_QUEUE_" + lastRoute + "_" + nonce + "_FIFO"
      : "EASYWORK_RECEIVE_relay_\(nonce)_DONE"
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", restoreMarker)).firstMatch.waitForExistence(timeout: 60))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    if initial["nativeQueue"] as? Bool == true {
      XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'execute-queued-' ")).firstMatch.exists)
      shot("native-queue-easywork-restored")
      print("EASYWORK_NATIVE_QUEUE_RESTORED", lastRoute)
    }
  }

  @MainActor func testEasyWorkRealDEV() async throws {
    func sendDraft() {
      if app.buttons["composer-dismiss-keyboard"].exists {
        app.buttons["composer-dismiss-keyboard"].tap()
      } else if app.keyboards.firstMatch.exists {
        app.scrollViews["message-list"].swipeDown()
      }
      let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
      XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 8), .completed, app.debugDescription)
      let send = app.buttons["send-message"]
      XCTAssertTrue(send.isEnabled)
      XCTAssertFalse(send.frame.intersects(app.buttons["voice-input"].frame))
      print("EASYWORK_SEND_FRAMES", send.frame, app.buttons["voice-input"].frame)
      send.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertFalse(app.staticTexts["dictation-transcript"].exists, "Sending must not activate dictation")
    }
    func passed(_ name: String, _ proof: [String: Any] = [:]) throws {
      let record: [String: Any] = ["scenario": name, "outcome": "passed", "proof": proof]
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]), as: UTF8.self))
    }
    let evidenceURL = URL(string: "http://127.0.0.1:8798/evidence")!
    func evidence() async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: evidenceURL)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func control(_ operation: String, threadId: String? = nil) async throws {
      let current = try await evidence()
      var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/" + (operation.hasSuffix(".restart") ? "restart" : "host"))!)
      request.httpMethod = "POST"
      request.setValue(try XCTUnwrap(current["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      var command: [String: Any] = ["operation": operation]; if let threadId { command["threadId"] = threadId }
      request.httpBody = try JSONSerialization.data(withJSONObject: command)
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      let result = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
      if let id = result["id"] as? String {
        for _ in 0..<60 {
          let observed = try await evidence()
          if let done = (observed["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(done["accepted"] as? Bool, true, String(describing: done)); return }
          try await Task.sleep(for: .seconds(1))
        }
        XCTFail("Host control was not acknowledged: " + operation)
      }
    }
    func preference(_ value: String) {
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      app.buttons["client-switch"].tap()
      let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
      XCTAssertTrue(edit.waitForExistence(timeout: 5)); edit.tap()
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      let picker = app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch
      for _ in 0..<5 {
        if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
        if app.buttons[value].firstMatch.exists { break }
        picker.tap()
        if app.buttons[value].firstMatch.waitForExistence(timeout: 2) { break }
      }
      XCTAssertTrue(app.buttons[value].firstMatch.exists, app.debugDescription); app.buttons[value].firstMatch.tap()
      app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
    }
    func duplicate(_ kind: String) async throws -> [String: Any] {
      app.buttons["attachment-menu"].tap(); app.buttons["agent-dev-duplicate-" + kind].tap()
      let result = app.staticTexts["agent-dev-operation-evidence"]
      let done = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH %@", kind + ":passed:"), object: result)
      XCTAssertEqual(XCTWaiter.wait(for: [done], timeout: 90), .completed, app.debugDescription)
      let ids = String(result.label.dropFirst((kind + ":passed:").count)).components(separatedBy: "|")
      XCTAssertEqual(ids.count, 2); XCTAssertEqual(Set(ids).count, 1)
      shot("native-dual-path-duplicate-" + kind)
      return try await evidence()
    }
    func refreshRuntime(after previous: [String: Any]) async throws {
      let seen = Set((previous["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
      app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
      for _ in 0..<90 {
        let current = try await evidence()
        let requests = current["requests"] as? [[String: Any]] ?? []
        if requests.contains(where: { $0["hostEpoch"] as? String == current["hostEpoch"] as? String && !seen.contains(($0["request"] as? [String: Any])?["requestId"] as? String ?? "") && ($0["request"] as? [String: Any])?["method"] as? String == "runtime/context" && ($0["response"] as? [String: Any])?["ok"] as? Bool == true }) { return }
        try await Task.sleep(for: .seconds(1))
      }
      XCTFail("The native app did not reconnect to the actual Host")
    }
    func completed(_ marker: String, timeout: TimeInterval = 240) async throws -> [String: Any] {
      let deadline = Date().addingTimeInterval(timeout)
      while Date() < deadline {
        approveEasyWorkFixtureIfNeeded()
        let data = try await evidence()
        if let desktop = data["independentDesktop"] as? [String: Any], let snapshots = desktop["snapshots"] as? [[String: Any]], let runs = desktop["executions"] as? [[String: Any]], runs.contains(where: { ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) {
          for snapshot in snapshots {
            for message in snapshot["messages"] as? [[String: Any]] ?? [] where message["role"] as? String == "assistant" {
              let text = String(decoding: try JSONSerialization.data(withJSONObject: message), as: UTF8.self)
              if text.contains(marker), runs.contains(where: { $0["id"] as? String == message["sourceRunId"] as? String && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }), message["status"] as? String == "complete" { return data }
            }
          }
        }
        try await Task.sleep(for: .seconds(1))
      }
      shot("native-real-dev-failure")
      XCTFail("Real desktop assistant did not complete: " + marker + "\n" + app.debugDescription)
      throw NSError(domain: "RealDEV", code: 1)
    }
    let initial = try await evidence()
    if initial["phase"] as? String == "connection" {
      app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
      for route in ["仅直连", "仅中继"] {
        let before = try await evidence()
        let seen = Set((before["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
        if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
        preference(route)
        var connected = false
        for _ in 0..<60 {
          let state = try await evidence()
          if (state["requests"] as? [[String: Any]] ?? []).contains(where: { !seen.contains(($0["request"] as? [String: Any])?["requestId"] as? String ?? "") && ($0["request"] as? [String: Any])?["method"] as? String == "runtime/context" && ($0["response"] as? [String: Any])?["ok"] as? Bool == true }) { connected = true; break }
          try await Task.sleep(for: .seconds(1))
        }
        shot("native-restored-connection-" + route)
        XCTAssertTrue(connected, app.debugDescription)
        try passed("restored-connection-" + route)
      }
      let beforeRestart = try await evidence()
      let runsBefore = Set(((beforeRestart["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String })
      try await control("host.restart")
      for _ in 0..<90 {
        let state = try await evidence()
        if state["hostEpoch"] as? String != beforeRestart["hostEpoch"] as? String, state["status"] as? String == "ready" { break }
        try await Task.sleep(for: .seconds(1))
      }
      try await refreshRuntime(after: beforeRestart)
      let afterRestart = try await evidence()
      XCTAssertNotEqual(afterRestart["hostEpoch"] as? String, beforeRestart["hostEpoch"] as? String)
      XCTAssertEqual(Set(((afterRestart["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }), runsBefore)
      shot("native-restored-host-restart-same-app")
      try passed("restored-host-restart-same-app-no-execution")
      preference("自动")
      let beforeLoss = try await evidence()
      try await control("lan.disable")
      try await refreshRuntime(after: beforeLoss)
      let recovered = try await evidence()
      let oldIds = Set((beforeLoss["requests"] as? [[String: Any]] ?? []).compactMap { ($0["request"] as? [String: Any])?["requestId"] as? String })
      XCTAssertTrue((recovered["requests"] as? [[String: Any]] ?? []).contains { $0["transportId"] as? String == "relay-http" && ($0["request"] as? [String: Any])?["method"] as? String == "runtime/context" && !oldIds.contains(($0["request"] as? [String: Any])?["requestId"] as? String ?? "") })
      if app.buttons["查询状态"].exists { app.buttons["查询状态"].tap() }
      let pendingCleared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["查询状态"])
      XCTAssertEqual(XCTWaiter.wait(for: [pendingCleared], timeout: 60), .completed)
      shot("native-restored-auto-fallback")
      try passed("automatic-lan-loss-relay-and-pending-reconciliation")
      try await control("lan.enable")
      return
    }
    if initial["simpleConnection"] as? Bool == true { try await runEasyWorkSimpleConnection(initial); return }
    if initial["phase"] as? String == "features" { try await runEasyWorkFeatures(initial); return }
    if initial["phase"] as? String == "observability" { try await runEasyWorkObservability(initial); return }
    if initial["phase"] as? String == "permissions" { try await runEasyWorkPermissions(initial); return }
    if initial["phase"] as? String == "reliability" { try await runEasyWorkReliability(initial); return }
    if initial["phase"] as? String == "isolation" { try await runEasyWorkIsolation(initial); return }
    let resume = initial["resumeLan"] as? [String: Any]
    let runSuffix = String((initial["controlAuthorization"] as? String ?? UUID().uuidString).prefix(8))
    let relayMarker = "RELAY_REPLIED_" + runSuffix
    let questionMarker = "EASYWORK_QUESTION_COMPLETED_" + runSuffix
    let switchMarker = "SWITCH_COMPLETED_" + runSuffix
    let lan = try XCTUnwrap(initial["lan"] as? [String: Any])
    let offer = try XCTUnwrap(initial["relayOffer"] as? [String: Any])
    let uri = try XCTUnwrap(lan["pairingUri"] as? String)
    let relayURL = try XCTUnwrap(initial["relayURL"] as? String)
    let relayCode = try XCTUnwrap(offer["pairingCode"] as? String)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-easywork-local-dev"] + (resume == nil ? [] : ["-restore-testing"])
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": uri, "EASYWORK_DEV_RELAY_URL": relayURL, "EASYWORK_DEV_RELAY_CODE": relayCode]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 15))
    var codes: [String]
    if let resume {
      codes = try XCTUnwrap(resume["codes"] as? [String])
      let restoredThread = try XCTUnwrap(resume["threadId"] as? String)
      app.buttons["open-sidebar"].tap()
      let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@", "recent-thread-" + restoredThread, "thread-" + restoredThread)).firstMatch
      XCTAssertTrue(row.waitForExistence(timeout: 20), app.debugDescription)
      row.tap()
      app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
      // Cancel only this isolated profile's previous failed/paused DEV run via the real UI.
      if app.buttons["stop-turn"].waitForExistence(timeout: 4) { app.buttons["stop-turn"].tap() }
    } else {
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    app.buttons["EasyWork"].firstMatch.tap()
    selectConnectionMethod("扫码连接", agent: "easywork")
    XCTAssertTrue(app.buttons["easywork-pair-lan"].waitForExistence(timeout: 5))
    shot("native-lan-pairing")
    app.buttons["easywork-pair-lan"].tap()
    let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND label CONTAINS 'EasyWork'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 120), .completed, app.debugDescription)
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["new-chat"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 45), .completed, app.debugDescription)
    let pairedState = try await evidence()
    XCTAssertTrue((pairedState["requests"] as? [[String: Any]] ?? []).contains { $0["transportId"] as? String == "lan-wss" && ($0["request"] as? [String: Any])?["method"] as? String == "runtime/context" && ($0["response"] as? [String: Any])?["ok"] as? Bool == true })
    chooseDesktopProject(nil)
    app.buttons["attachment-menu"].tap(); app.buttons["easywork-dev-inputs"].tap()
    codes = app.staticTexts["easywork-dev-input-evidence"].label.components(separatedBy: "|")
    XCTAssertEqual(codes.count, 2)
    XCTAssertTrue(codes[0].hasPrefix("IMG_"))
    print("EASYWORK_REAL_INPUTS:" + codes.joined(separator: "|"))
    composer.tap()
    typeComposerText("EASYWORK_NATIVE_LAN: Read both attachments. Visually read the verification code in the PNG and use a file-reading tool to read the TXT code. Use a filesystem tool to create generated-native.txt with both codes in the current workspace. Reply with both codes and a Markdown file link [generated-native.txt](generated-native.txt).")
    sendDraft()
    XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 35), app.debugDescription)
    shot("native-lan-real-tool-progress")
    }
    print("EASYWORK_REAL_INPUTS:" + codes.joined(separator: "|"))
    _ = try await completed(codes[0], timeout: 480)
    let lanSettled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [lanSettled], timeout: 30), .completed)
    let files = app.buttons.matching(identifier: "preview-generated-native.txt")
    XCTAssertTrue(files.firstMatch.waitForExistence(timeout: 30), app.debugDescription)
    if app.buttons["跳转最新消息"].exists { app.buttons["跳转最新消息"].tap() }
    let file = try XCTUnwrap(files.allElementsBoundByIndex.last)
    for _ in 0..<10 where !file.isHittable { app.scrollViews["message-list"].swipeUp() }
    XCTAssertTrue(file.isHittable, app.debugDescription)
    print("EASYWORK_DOWNLOAD_FRAME", file.frame)
    file.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let closeFilePreview = app.buttons.matching(NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@", ["关闭", "Close", "Done", "完成"])).firstMatch
    XCTAssertTrue(closeFilePreview.waitForExistence(timeout: 45), app.debugDescription)
    shot("direct-file-card-preview")
    closeFilePreview.tap()
    shot("native-generated-file-verified")
    let desktop = try await completed(codes[0])
    let observations = try XCTUnwrap(desktop["independentDesktop"] as? [String: Any])
    let executions = try XCTUnwrap(observations["executions"] as? [[String: Any]])
    XCTAssertTrue(executions.contains { ($0["providerUsage"] as? [Any])?.isEmpty == false })
    let serialized = String(decoding: try JSONSerialization.data(withJSONObject: observations), as: UTF8.self)
    XCTAssertTrue(codes.allSatisfy { serialized.contains($0) }, "Desktop result must contain the image-only random code")
    try passed("lan-pair-history-create-mixed-tools-download", ["codes": codes, "executions": executions.map { $0["id"] ?? "" }, "modelEvidenceReused": resume != nil])
    // Add Relay to the same full target and keep one EasyWork client row.
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    app.buttons["EasyWork"].firstMatch.tap()
    selectConnectionMethod("通过中继连接", agent: "easywork")
    for _ in 0..<3 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
    app.buttons["easywork-dev-login"].tap()
    let currentRelay = try await evidence()
    let liveOffer = try XCTUnwrap(currentRelay["relayOffer"] as? [String: Any])
    replaceText(app.secureTextFields["easywork-relay-code"], with: try XCTUnwrap(liveOffer["pairingCode"] as? String))
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.swipeUp()
    app.buttons["easywork-pair-relay"].tap()
    let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 90), .completed, app.debugDescription)
    // Force Relay with the public connection preference.
    app.buttons["client-switch"].tap()
    let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
    XCTAssertTrue(edit.waitForExistence(timeout: 5)); edit.tap()
    app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch.tap(); app.buttons["仅中继"].firstMatch.tap()
    app.buttons["cancel-client-form"].tap()
    app.navigationBars.buttons["完成"].tap()
    let relayConnected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS '中继'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [relayConnected], timeout: 45), .completed, app.debugDescription)
    let relayed = try await evidence()
    XCTAssertTrue((relayed["requests"] as? [[String: Any]] ?? []).contains { $0["transportId"] as? String == "relay-http" && ($0["request"] as? [String: Any])?["method"] as? String == "runtime/context" })
    composer.tap(); typeComposerText("EASYWORK_NATIVE_RELAY: Use a shell tool to read generated-native.txt and reply with both stored codes and \(relayMarker).")
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    XCTAssertFalse(app.buttons["send-message"].frame.intersects(app.buttons["voice-input"].frame), "Send and voice actions must have distinct native frames")
    shot("native-relay-before-send")
    sendDraft()
    _ = try await completed(relayMarker)
    try passed("relay-pair-history-reply-tools")
    shot("native-relay-real-reply")
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 30), .completed)
    // Stop a real model run through the same management operation.
    composer.tap(); typeComposerText("EASYWORK_NATIVE_STOP: Write 1000 numbered sentences, each different.")
    sendDraft()
    XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 30)); app.buttons["stop-turn"].tap()
    let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 45), .completed)
    let stoppedEvidence = try await evidence()
    let stoppedRequests = try XCTUnwrap(stoppedEvidence["requests"] as? [[String: Any]])
    let stopRecord = try XCTUnwrap(stoppedRequests.last { ($0["request"] as? [String: Any])?["method"] as? String == "turn/cancel" })
    XCTAssertEqual(stopRecord["transportId"] as? String, "relay-http")
    let stopResponse = try XCTUnwrap(stopRecord["response"] as? [String: Any])
    XCTAssertEqual((stopResponse["result"] as? [String: Any])?["cancelled"] as? Bool, true)
    try passed("relay-stop-real-run", ["response": stopResponse])
    shot("native-relay-stop")
    // A real runtime question uses the generic approval/question view.
    app.buttons["new-chat"].tap(); app.buttons["choose-projectless"].tap()
    composer.tap(); typeComposerText("EASYWORK_NATIVE_QUESTION: 必须先调用 question 工具询问我：header 是交付格式，question 是请选择交付格式，提供简洁版和详细版两个选项。收到答案后只回复 \(questionMarker)。")
    sendDraft()
    XCTAssertTrue(app.staticTexts["请选择交付格式"].waitForExistence(timeout: 180), app.debugDescription)
    let answerOption = app.buttons["question-option-交付格式-简洁版"]
    XCTAssertTrue(answerOption.isHittable)
    answerOption.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let selectedAnswer = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '已选择'"), object: answerOption)
    XCTAssertEqual(XCTWaiter.wait(for: [selectedAnswer], timeout: 8), .completed, app.debugDescription)
    XCTAssertTrue(app.buttons["answer-question"].isEnabled); app.buttons["answer-question"].tap()
    _ = try await completed(questionMarker)
    try passed("relay-runtime-question")
    shot("native-question-completed")
    let duplicateCreate = try await duplicate("create")
    let creates = (duplicateCreate["requests"] as? [[String: Any]] ?? []).filter { ($0["request"] as? [String: Any])?["method"] as? String == "thread/create" }
    let createOperation = try XCTUnwrap((creates.last?["request"] as? [String: Any])?["requestId"] as? String)
    let createCopies = creates.filter { ($0["request"] as? [String: Any])?["requestId"] as? String == createOperation }
    XCTAssertEqual(createCopies.count, 2)
    XCTAssertEqual(Set(createCopies.compactMap { $0["transportId"] as? String }), Set(["lan-wss", "relay-http"]))
    try passed("cross-path-concurrent-create-once", ["operationId": createOperation])
    let beforeRuns = ((try await evidence())["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
    _ = try await duplicate("reply")
    let duplicated = ((try await evidence())["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
    XCTAssertEqual(Set(duplicated.compactMap { $0["id"] as? String }), Set(beforeRuns.compactMap { $0["id"] as? String }))
    try passed("cross-path-duplicate-turn-once")
    let beforeRelay = try await evidence()
    try await control("relay.restart")
    try await refreshRuntime(after: beforeRelay)
    try passed("relay-restart-native-reconnect")
    let beforeHost = try await evidence()
    try await control("host.restart")
    for _ in 0..<120 {
      let current = try await evidence()
      if current["hostEpoch"] as? String != beforeHost["hostEpoch"] as? String, current["status"] as? String == "ready" { break }
      try await Task.sleep(for: .seconds(1))
    }
    try await refreshRuntime(after: beforeHost)
    _ = try await duplicate("reply")
    let restarted = ((try await evidence())["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
    XCTAssertEqual(Set(restarted.compactMap { $0["id"] as? String }), Set(beforeRuns.compactMap { $0["id"] as? String }))
    try passed("host-restart-durable-receipt-no-repeat")
    preference("自动")
    let local = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS '直连'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [local], timeout: 45), .completed, app.debugDescription)
    try await control("lan.switch-next-turn")
    composer.tap(); typeComposerText("EASYWORK_SWITCH_DURING_SEND: Use a terminal tool to append exactly one line SWITCH_ONCE to switch-once.txt. Reply \(switchMarker).")
    sendDraft()
    var switched = try await completed(switchMarker, timeout: 600)
    let fault = try XCTUnwrap((switched["faults"] as? [[String: Any]])?.last { $0["operation"] as? String == "lan.switch-next-turn" })
    let switchOperation = try XCTUnwrap(fault["requestId"] as? String)
    var relayReceipt: [String: Any]?
    for _ in 0..<90 {
      switched = try await evidence()
      relayReceipt = (switched["requests"] as? [[String: Any]] ?? []).last { record in
        let request = record["request"] as? [String: Any] ?? [:]
        return record["transportId"] as? String == "relay-http" && request["method"] as? String == "command/status" && (request["params"] as? [String: Any])?["commandId"] as? String == switchOperation && (record["response"] as? [String: Any])?["ok"] as? Bool == true
      }
      if relayReceipt != nil { break }
      if app.buttons["查询状态"].exists && app.buttons["查询状态"].isEnabled { app.buttons["查询状态"].tap() }
      try await Task.sleep(for: .seconds(1))
    }
    let restoredReceipt = try XCTUnwrap(((relayReceipt?["response"] as? [String: Any])?["result"] as? [String: Any]))
    XCTAssertEqual(restoredReceipt["state"] as? String, "completed")
    let copies = (switched["requests"] as? [[String: Any]] ?? []).filter { ($0["request"] as? [String: Any])?["method"] as? String == "turn/start" && ($0["request"] as? [String: Any])?["requestId"] as? String == switchOperation }
    let runIds = Set(copies.compactMap { ($0["response"] as? [String: Any])?["result"] as? [String: Any] }.compactMap { $0["runId"] as? String })
    XCTAssertEqual(runIds.count, 1)
    XCTAssertEqual(((restoredReceipt["response"] as? [String: Any])?["result"] as? [String: Any])?["runId"] as? String, runIds.first)
    let switchReceiptSettled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["查询状态"])
    XCTAssertEqual(XCTWaiter.wait(for: [switchReceiptSettled], timeout: 45), .completed)
    try passed("switch-during-send-no-repeat", ["operationId": switchOperation])
    shot("native-switch-during-send")
    try await control("lan.enable")
    // Restart the real native app and restore its encrypted cache/configuration.
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 15))
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", questionMarker)).firstMatch.waitForExistence(timeout: 60), app.debugDescription)
    shot("native-restart-restored")
    try passed("native-app-restart-encrypted-cache")
  }

  @MainActor private func runEasyWorkSimpleConnection(_ initial: [String: Any]) async throws {
    func evidence() async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func passed(_ scenario: String) throws {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": scenario, "outcome": "passed", "proof": ["desktopIndependentlyChecked": true]], options: [.sortedKeys]), as: UTF8.self))
    }
    func waitForConnection() {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND label CONTAINS 'EasyWork'"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 90), .completed, app.debugDescription)
    }
    func setPreference(_ value: String) {
      app.buttons["client-switch"].tap()
      let editor = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
      XCTAssertTrue(editor.waitForExistence(timeout: 10), app.debugDescription)
      editor.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let preference = app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch
      XCTAssertTrue(preference.waitForExistence(timeout: 10), app.debugDescription)
      preference.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
      let choice = app.buttons[value].firstMatch
      XCTAssertTrue(choice.waitForExistence(timeout: 10), app.debugDescription)
      choice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
    }
    func sendAndVerify(_ marker: String, route: String) async throws {
      let editable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
      XCTAssertEqual(XCTWaiter.wait(for: [editable], timeout: 45), .completed, app.debugDescription)
      composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      replaceText(composer, with: "不要调用工具，只回复 " + marker)
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      else if app.keyboards.firstMatch.exists { app.scrollViews["message-list"].swipeDown() }
      let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
      XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 8), .completed)
      let send = app.buttons["send-message"]
      let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: send)
      XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 60), .completed, app.debugDescription)
      XCTAssertTrue(send.isEnabled); send.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      for _ in 0..<240 {
        let observed = try await evidence()
        let independent = observed["independentDesktop"] as? [String: Any] ?? [:]
        let executions = independent["executions"] as? [[String: Any]] ?? []
        let snapshots = independent["snapshots"] as? [[String: Any]] ?? []
        let matching = snapshots.flatMap { $0["messages"] as? [[String: Any]] ?? [] }.filter { ($0["role"] as? String) == "assistant" && String(describing: $0).contains(marker) && ($0["status"] as? String) == "complete" }
        if let message = matching.first, let run = executions.first(where: { $0["id"] as? String == message["sourceRunId"] as? String && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) {
          XCTAssertFalse((run["providerUsage"] as? [Any] ?? []).isEmpty)
          let requests = observed["requests"] as? [[String: Any]] ?? []
          let starts = requests.filter { $0["transportId"] as? String == route && ($0["request"] as? [String: Any])?["method"] as? String == "turn/start" && String(describing: ($0["request"] as? [String: Any])?["params"] ?? "").contains(marker) }
          XCTAssertEqual(starts.count, 1)
          XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 20))
          return
        }
        try await Task.sleep(for: .seconds(1))
      }
      XCTFail("Native input did not complete in the original desktop runtime")
    }
    let lan = try XCTUnwrap(initial["lan"] as? [String: Any])
    let endpoints = try XCTUnwrap(lan["endpoints"] as? [String])
    let port = try XCTUnwrap(URL(string: endpoints[0])?.port)
    let suffix = String(UUID().uuidString.prefix(8))
    let reused = (initial["simpleResume"] as? [String: Any])?["scenarios"] as? [[String: Any]] ?? []
    for scenario in reused {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: scenario, options: [.sortedKeys]), as: UTF8.self))
    }
    app.terminate(); app.launchArguments = ["-ui-testing", "-easywork-local-dev"]
    if !reused.isEmpty { app.launchArguments.append("-restore-testing") }
    app.launchEnvironment = ["EASYWORK_DEV_RELAY_URL": try XCTUnwrap(initial["relayURL"] as? String), "EASYWORK_DEV_LAN_OFFER": try XCTUnwrap(lan["pairingUri"] as? String)]
    app.launchChineseTest(); XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    if reused.isEmpty {
    app.buttons["client-switch"].tap()
    for _ in 0..<4 where !app.buttons["add-client"].isHittable { app.swipeUp() }
    XCTAssertTrue(app.buttons["add-client"].isHittable, app.debugDescription)
    app.buttons["add-client"].tap()
    let easyWorkChoice = app.buttons["EasyWork"].firstMatch
    XCTAssertTrue(easyWorkChoice.waitForExistence(timeout: 10), app.debugDescription)
    easyWorkChoice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.textFields["easywork-name"].waitForExistence(timeout: 10), app.debugDescription)
    let discovered = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'easywork-discovered-' AND value CONTAINS %@", ":" + String(port))).firstMatch
    XCTAssertTrue(discovered.waitForExistence(timeout: 30), app.debugDescription)
    shot("native-simple-lan-discovery")
    XCTAssertTrue(discovered.isHittable, app.debugDescription)
    discovered.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.textFields["easywork-qr-description"].waitForExistence(timeout: 5), "Unknown desktop must lead to the QR form")
    for _ in 0..<4 where !app.buttons["easywork-pair-lan"].isHittable { app.swipeUp() }
    XCTAssertTrue(app.buttons["easywork-pair-lan"].isHittable, app.debugDescription)
    app.buttons["easywork-pair-lan"].tap()
    shot("native-simple-lan-qr-connected")
    waitForConnection()
    chooseDesktopProject(nil)
    app.buttons["generation-settings"].tap()
    app.descendants(matching: .any).matching(identifier: "model-picker").firstMatch.tap()
    app.buttons.matching(NSPredicate(format: "label CONTAINS 'GLM-5.2'")).firstMatch.tap()
    app.descendants(matching: .any).matching(identifier: "effort-picker").firstMatch.tap(); app.buttons["高"].firstMatch.tap()
    app.navigationBars.buttons["完成"].tap()
    try await sendAndVerify("SIMPLE_LAN_" + suffix, route: "lan-wss")
    try passed("lan-qr-identity-real-model")
    }
    if reused.count < 2 {
    app.buttons["client-switch"].tap()
    for _ in 0..<4 where !app.buttons["add-client"].isHittable { app.swipeUp() }
    XCTAssertTrue(app.buttons["add-client"].isHittable, app.debugDescription)
    app.buttons["add-client"].tap()
    let easyWorkChoice = app.buttons["EasyWork"].firstMatch
    XCTAssertTrue(easyWorkChoice.waitForExistence(timeout: 10), app.debugDescription)
    easyWorkChoice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.textFields["easywork-name"].waitForExistence(timeout: 10), app.debugDescription)
    selectConnectionMethod("通过中继连接", agent: "easywork")
    shot("native-simple-relay-password")
    for _ in 0..<4 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
    app.buttons["easywork-dev-login"].tap()
    for _ in 0..<5 where !app.secureTextFields["easywork-relay-code"].isHittable { app.swipeUp() }
    replaceText(app.secureTextFields["easywork-relay-code"], with: try XCTUnwrap(initial["controlAuthorization"] as? String))
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.swipeUp(); app.buttons["easywork-pair-relay"].tap()
    waitForConnection()
    setPreference("仅中继")
    try await sendAndVerify("SIMPLE_RELAY_" + suffix, route: "relay-http")
    try passed("relay-fixed-password-real-model")
    }
    if reused.count < 3 {
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS '中继'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 60), .completed, app.debugDescription)
    try await sendAndVerify("SIMPLE_RESTORED_" + suffix, route: "relay-http")
    try passed("relay-app-restart-without-password")
    }
    let before = try await evidence()
    var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/restart")!)
    request.httpMethod = "POST"; request.setValue(try XCTUnwrap(initial["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
    request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": "host.restart"])
    let (_, response) = try await URLSession.shared.data(for: request); XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    for _ in 0..<90 { if try await evidence()["hostEpoch"] as? String != before["hostEpoch"] as? String { break }; try await Task.sleep(for: .seconds(1)) }
    setPreference("仅直连")
    try await sendAndVerify("SIMPLE_HOST_RESTORED_" + suffix, route: "lan-wss")
    try passed("lan-host-restart-without-pairing")
    shot("native-simple-connections-completed")
  }

  @MainActor private func runEasyWorkPermissions(_ initial: [String: Any]) async throws {
    let suffix = String((initial["controlAuthorization"] as? String ?? UUID().uuidString).prefix(8))
    func evidence() async throws -> [String: Any] {
      let (bytes, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    func control(_ operation: String) async throws {
      var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/host")!)
      request.httpMethod = "POST"; request.setValue(try XCTUnwrap(initial["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      let id = try XCTUnwrap((JSONSerialization.jsonObject(with: bytes) as? [String: Any])?["id"] as? String)
      for _ in 0..<60 {
        if let done = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(done["accepted"] as? Bool, true); return }
        try await Task.sleep(for: .seconds(1))
      }
      XCTFail("Unacknowledged Host control " + operation)
    }
    func preference(_ value: String) {
      app.buttons["client-switch"].tap()
      let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
      XCTAssertTrue(edit.waitForExistence(timeout: 5)); edit.tap()
      app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch.tap(); app.buttons[value].firstMatch.tap()
      app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
      let route = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", value == "仅直连" ? "直连" : "中继"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [route], timeout: 45), .completed, app.debugDescription)
    }
    func newThread(_ project: String? = nil) {
      app.buttons["new-chat"].tap(); app.buttons[project.map { "choose-project-" + $0 } ?? "choose-projectless"].tap()
    }
    func send(_ prompt: String) {
      // Wait for the public settings menu and previous send to release native input.
      let editable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
      XCTAssertEqual(XCTWaiter.wait(for: [editable], timeout: 45), .completed, app.debugDescription)
      composer.tap()
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      typeComposerText(prompt)
      XCTAssertEqual(composer.value as? String, prompt)
      // End permission input availability precondition.
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
      XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 8), .completed)
      let action = app.buttons["send-message"]; XCTAssertTrue(action.isEnabled, app.debugDescription)
      action.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    func completed(_ marker: String, approvals: Bool = true) async throws -> [String: Any] {
      for _ in 0..<600 {
        if approvals { approveEasyWorkFixtureIfNeeded() }
        let data = try await evidence()
        let desktop = data["independentDesktop"] as? [String: Any] ?? [:]
        let runs = desktop["executions"] as? [[String: Any]] ?? []
        for snapshot in desktop["snapshots"] as? [[String: Any]] ?? [] {
          for message in snapshot["messages"] as? [[String: Any]] ?? [] where message["role"] as? String == "assistant" && message["status"] as? String == "complete" {
            let text = String(decoding: try JSONSerialization.data(withJSONObject: message), as: UTF8.self)
            if text.contains(marker), runs.contains(where: { $0["id"] as? String == message["sourceRunId"] as? String && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) {
              let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
              XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 30), .completed)
              return data
            }
          }
        }
        try await Task.sleep(for: .seconds(1))
      }
      shot("native-permissions-failure"); XCTFail("Canonical model did not complete " + marker)
      throw NSError(domain: "RealDEV", code: 2)
    }
    func passed(_ scenario: String, _ proof: [String: Any] = [:]) throws {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": scenario, "outcome": "passed", "proof": proof], options: [.sortedKeys]), as: UTF8.self))
    }
    app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 15))
    var codes: [String]
    if let reused = initial["reusePermissions"] as? [String: Any], reused["prefixOnly"] as? Bool != true {
      codes = try XCTUnwrap(reused["codes"] as? [String]); XCTAssertEqual(codes.count, 2)
      preference("仅中继")
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      app.buttons["open-sidebar"].tap()
      let thread = try XCTUnwrap(reused["threadId"] as? String)
      let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@", "recent-thread-" + thread, "thread-" + thread)).firstMatch
      XCTAssertTrue(row.waitForExistence(timeout: 10)); row.tap()
      for scene in reused["scenarios"] as? [[String: Any]] ?? [] {
        try passed(try XCTUnwrap(scene["scenario"] as? String), ["reused": true, "fingerprint": reused["fingerprint"] ?? "", "directory": reused["directory"] ?? ""])
      }
    } else {
    let projects = try XCTUnwrap(initial["projects"] as? [[String: Any]])
    XCTAssertGreaterThanOrEqual(projects.count, 2)
    let project = try XCTUnwrap(projects[0]["id"] as? String)
    // Restore only the completed project/reply/stop prefix, without rerunning its model.
    if let reused = initial["reusePermissions"] as? [String: Any], reused["prefixOnly"] as? Bool == true {
      for scene in reused["scenarios"] as? [[String: Any]] ?? [] {
        var proof = scene["proof"] as? [String: Any] ?? [:]
        if let savedProject = proof["projectId"] as? String { XCTAssertEqual(savedProject, project) }
        proof["reused"] = true; proof["fingerprint"] = reused["fingerprint"]; proof["directory"] = reused["directory"]
        try passed(try XCTUnwrap(scene["scenario"] as? String), proof)
      }
    } else {
    // Begin original project/reply/stop behavior.
    preference("仅直连"); newThread(project)
    let lanMarker = "LAN_PROJECT_REPLY_" + suffix
    send("请在当前工作目录用终端等待 20 秒，确认命令结束后，只回复 \(lanMarker)。这是我在已授权工作区发起的操作。")
    let progress = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tool-' AND label BEGINSWITH '正在'")).firstMatch
    XCTAssertTrue(progress.waitForExistence(timeout: 90), app.debugDescription); shot("native-lan-real-tool-running")
    let created = try await completed(lanMarker)
    let snapshots = (created["independentDesktop"] as? [String: Any])?["snapshots"] as? [[String: Any]] ?? []
    XCTAssertTrue(snapshots.contains { (($0["snapshot"] as? [String: Any])?["thread"] as? [String: Any])?["projectId"] as? String == project })
    let reply = "LAN_REPLIED_" + suffix; send("请只回复 " + reply); _ = try await completed(reply)
    try passed("lan-project-create-reply-tool-progress", ["projectId": project]); shot("native-lan-project-reply")
    send("LAN_STOP: 输出1000句不同的句子。"); XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 30)); app.buttons["stop-turn"].tap()
    let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 45), .completed)
    let stop = ((try await evidence())["requests"] as? [[String: Any]])?.last { ($0["request"] as? [String: Any])?["method"] as? String == "turn/cancel" }
    XCTAssertEqual(stop?["transportId"] as? String, "lan-wss"); XCTAssertEqual(((stop?["response"] as? [String: Any])?["result"] as? [String: Any])?["cancelled"] as? Bool, true)
    try passed("lan-stop-real-run")
    }
    // End original project/reply/stop behavior.
    for route in ["lan", "relay", "race"] {
      preference(route == "lan" ? "仅直连" : "仅中继"); newThread()
      let settings = app.buttons["permission-settings"]
      let configurable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: settings)
      XCTAssertEqual(XCTWaiter.wait(for: [configurable], timeout: 45), .completed)
      settings.tap()
      app.descendants(matching: .any).matching(identifier: "effort-picker").firstMatch.tap()
      let establishedReasoning = app.buttons["高"].firstMatch
      XCTAssertTrue(establishedReasoning.waitForExistence(timeout: 15)); establishedReasoning.tap()
      let confirmEveryOperation = app.buttons["permission-confirm_every_operation"]
      XCTAssertTrue(confirmEveryOperation.waitForExistence(timeout: 15)); confirmEveryOperation.tap()
      app.buttons["generation-done"].tap()
      try await control("permissions.confirm-next-turn")
      let marker = "PERMISSION_" + route + "_" + suffix
      send("必须使用终端工具向 permission-\(route).txt 追加且仅追加一行 PERMISSION_ONCE。不能绕过审批。完成后只回复 \(marker)。")
      XCTAssertTrue(app.buttons["approval-approve"].waitForExistence(timeout: 420), app.debugDescription)
      XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'canonicalArgsHash' OR label CONTAINS 'requestHash' ")).firstMatch.exists)
      shot("native-permission-" + route)
      if route == "race" { try await control("desktop.approval-race-next-response") }
      app.buttons["approval-approve"].tap()
      let done = try await completed(marker, approvals: true)
      let requests = done["requests"] as? [[String: Any]] ?? []
      let approval = try XCTUnwrap(requests.last { ($0["request"] as? [String: Any])?["method"] as? String == "approval/respond" })
      XCTAssertEqual(approval["transportId"] as? String, route == "lan" ? "lan-wss" : "relay-http")
      XCTAssertEqual(((approval["response"] as? [String: Any])?["result"] as? [String: Any])?["accepted"] as? Bool, true)
      if route == "race" { XCTAssertTrue((done["faults"] as? [[String: Any]] ?? []).contains { $0["operation"] as? String == "desktop.approval-race" }) }
      try passed(route == "race" ? "desktop-phone-approval-race-once" : route + "-permission-approval")
    }
    preference("仅直连"); newThread()
    let permissionSettings = app.buttons["permission-settings"]
    let configurable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: permissionSettings)
    XCTAssertEqual(XCTWaiter.wait(for: [configurable], timeout: 45), .completed)
    permissionSettings.tap()
    let normalPermissions = app.buttons["permission-confirm_high_risk"]
    XCTAssertTrue(normalPermissions.waitForExistence(timeout: 15)); normalPermissions.tap()
    app.buttons["generation-done"].tap()
    let questionMarker = "MULTI_ANSWERED_" + suffix
    send("必须先调用 question 工具：header 交付内容，question 请选择交付内容，选项文本和文件，multiple 为 true，custom 为 false。我回复后，只回复 \(questionMarker)。")
    XCTAssertTrue(app.staticTexts["请选择交付内容"].waitForExistence(timeout: 420), app.debugDescription)
    for label in ["文本", "文件"] {
      let option = app.buttons["question-option-交付内容-" + label]
      XCTAssertTrue(option.isHittable); option.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertEqual(option.value as? String, "已选择")
    }
    XCTAssertTrue(app.buttons["answer-question"].isEnabled); app.buttons["answer-question"].tap()
    let question = try await completed(questionMarker)
    let answer = (question["requests"] as? [[String: Any]])?.last { ($0["request"] as? [String: Any])?["method"] as? String == "serverRequest/resolve" }
    let params = (answer?["request"] as? [String: Any])?["params"] as? [String: Any]
    let values = ((params?["answer"] as? [String: Any])?["questions"] as? [[String: Any]])?.first
    XCTAssertEqual(Set(values?["selectedOptions"] as? [String] ?? []), Set(["文本", "文件"])); XCTAssertNil(values?["customAnswer"])
    try passed("lan-question-multiple-no-custom")
    preference("仅中继"); newThread(project)
    app.buttons["attachment-menu"].tap(); app.buttons["easywork-dev-inputs"].tap()
    codes = app.staticTexts["easywork-dev-input-evidence"].label.components(separatedBy: "|")
    print("EASYWORK_REAL_RELAY_INPUTS:" + codes.joined(separator: "|"))
    send("RELAY_MIXED: Read both attachments. Visually read the PNG code and use a file-reading tool for the TXT code. Write generated-relay.txt with both codes using a filesystem tool, then reply with both codes and [generated-relay.txt](generated-relay.txt).")
    }
    print("EASYWORK_REAL_RELAY_INPUTS:" + codes.joined(separator: "|"))
    _ = try await completed(codes[0])
    let downloads = app.buttons.matching(identifier: "preview-generated-relay.txt")
    XCTAssertTrue(downloads.firstMatch.waitForExistence(timeout: 30), app.debugDescription)
    let download = try XCTUnwrap(downloads.allElementsBoundByIndex.last)
    for _ in 0..<10 where !download.isHittable { app.scrollViews["message-list"].swipeUp() }
    download.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let closeFilePreview = app.buttons.matching(NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@", ["关闭", "Close", "Done", "完成"])).firstMatch
    XCTAssertTrue(closeFilePreview.waitForExistence(timeout: 45), app.debugDescription)
    shot("direct-file-card-preview")
    closeFilePreview.tap()
    try passed("relay-project-create-mixed-tools-download", ["codes": codes, "modelEvidenceReused": initial["reusePermissions"] != nil && (initial["reusePermissions"] as? [String: Any])?["prefixOnly"] as? Bool != true]); shot("native-relay-mixed-download")
  }

  @MainActor private func runEasyWorkReliability(_ initial: [String: Any]) async throws {
    let suffix = String((initial["controlAuthorization"] as? String ?? UUID().uuidString).prefix(8))
    let reusedScenes = (initial["reuseReliability"] as? [String: Any])?["scenarios"] as? [[String: Any]] ?? []
    func evidence() async throws -> [String: Any] {
      let (bytes, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    func control(_ operation: String) async throws {
      let state = try await evidence()
      let destination = operation.hasSuffix(".restart") || operation == "relay.offline" ? "restart" : "host"
      var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/" + destination)!)
      request.httpMethod = "POST"; request.setValue(try XCTUnwrap(state["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      if let id = (try JSONSerialization.jsonObject(with: bytes) as? [String: Any])?["id"] as? String {
        for _ in 0..<60 {
          if let done = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(done["accepted"] as? Bool, true); return }
          try await Task.sleep(for: .seconds(1))
        }
        XCTFail("Unacknowledged reliability control " + operation)
      }
    }
    func refresh() {
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    }
    func launch() {
      app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    }
    func newThread() {
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      // Dismiss a native menu through the visible window, not a scrolled content coordinate.
      if app.buttons["刷新历史"].exists {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.65)).tap()
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["刷新历史"])
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed)
      }
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: app.buttons["new-chat"])
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed)
      app.buttons["new-chat"].tap()
      XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10)); app.buttons["choose-projectless"].tap()
      // A failed earlier acceptance can leave its draft in this projectless slot.
      // Explicitly edit the isolated test input before starting a fresh scenario.
      if app.buttons["send-message"].isEnabled, let stale = composer.value as? String, !stale.isEmpty {
        composer.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.98)).tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        typeComposerText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: stale.count))
      }
      let empty = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == false"), object: app.buttons["send-message"])
      XCTAssertEqual(XCTWaiter.wait(for: [empty], timeout: 10), .completed)
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    }
    func retryUnsentDraft(_ marker: String) {
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      guard app.buttons["send-message"].exists, app.buttons["send-message"].isEnabled,
        (composer.value as? String ?? "").contains(marker) else { return }
      app.buttons["send-message"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    let lanOffer = try XCTUnwrap((initial["lan"] as? [String: Any])?["pairingUri"] as? String)
    let relayOffer = try XCTUnwrap(initial["relayOffer"] as? [String: Any])
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": lanOffer,
      "EASYWORK_DEV_RELAY_URL": try XCTUnwrap(initial["relayURL"] as? String),
      "EASYWORK_DEV_RELAY_CODE": try XCTUnwrap(relayOffer["pairingCode"] as? String)]
    launch()
    app.buttons["client-switch"].tap()
    // Repair only a test configuration reset by the failed XCTest runner.
    if !app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch.exists {
      app.buttons["add-client"].tap(); app.buttons["EasyWork"].firstMatch.tap()
      selectConnectionMethod("扫码连接", agent: "easywork")
      XCTAssertTrue(app.buttons["easywork-pair-lan"].waitForExistence(timeout: 10)); app.buttons["easywork-pair-lan"].tap()
      let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND label CONTAINS 'EasyWork'"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 90), .completed, app.debugDescription)
      app.buttons["client-switch"].tap()
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch.tap()
      selectConnectionMethod("通过中继连接", agent: "easywork")
      for _ in 0..<6 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
      XCTAssertTrue(app.buttons["easywork-dev-login"].isHittable); app.buttons["easywork-dev-login"].tap()
      for _ in 0..<6 where !app.textFields["easywork-relay-url"].isHittable { app.swipeDown() }
      replaceText(app.textFields["easywork-relay-url"], with: try XCTUnwrap(initial["relayURL"] as? String))
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
      for _ in 0..<6 where !app.secureTextFields["easywork-relay-code"].isHittable { app.swipeUp() }
      replaceText(app.secureTextFields["easywork-relay-code"], with: try XCTUnwrap(relayOffer["pairingCode"] as? String))
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
      app.swipeUp(); app.buttons["easywork-pair-relay"].tap()
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])], timeout: 90), .completed, app.debugDescription)
      app.buttons["client-switch"].tap()
    }
    // End public pairing setup; restart and receipt assertions remain unchanged.
    let edit = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch
    XCTAssertTrue(edit.waitForExistence(timeout: 5)); edit.tap()
    app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch.tap(); app.buttons["仅中继"].tap()
    app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
    let online = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS '中继'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [online], timeout: 60), .completed)
    for scene in reusedScenes {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: scene, options: [.sortedKeys]), as: UTF8.self))
    }
    for kind in ["app", "relay", "host"] {
      if reusedScenes.contains(where: { $0["scenario"] as? String == kind + "-restart-inflight-durable-receipt" }) { continue }
      newThread()
      let marker = "RELIABILITY_" + kind + "_" + suffix
      try await control(kind == "host" ? "host.crash-next-turn" : "response.delay-next-turn")
      composer.tap()
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      typeComposerText("RELIABILITY: Use a terminal tool to run sleep 12, then append exactly one line RELIABILITY_ONCE to reliability-\(kind)-\(suffix).txt. After completion reply \(marker).")
      app.buttons["composer-dismiss-keyboard"].tap()
      let send = app.buttons["send-message"]; XCTAssertTrue(send.isEnabled); send.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      var operation: String?
      var runId: String?
      var beforeRestart = initial
      for _ in 0..<90 {
        let state = try await evidence()
        let faults = state["faults"] as? [[String: Any]] ?? []
        let requests = state["requests"] as? [[String: Any]] ?? []
        let record = requests.last(where: { record in
          let request = record["request"] as? [String: Any] ?? [:]
          let params = request["params"] as? [String: Any] ?? [:]
          let parts = params["parts"] as? [[String: Any]] ?? []
          return parts.contains(where: { (($0["data"] as? [String: Any])?["text"] as? String ?? "").contains(marker) })
        })
        if let record,
          let id = (record["request"] as? [String: Any])?["requestId"] as? String,
          faults.contains(where: { $0["requestId"] as? String == id }) {
          operation = id; runId = ((record["response"] as? [String: Any])?["result"] as? [String: Any])?["runId"] as? String
          beforeRestart = state; break
        }
        try await Task.sleep(for: .seconds(1))
      }
      // BEGIN missing submission diagnostics; all completion assertions follow.
      if operation == nil || runId == nil { shot("native-reliability-unsubmitted-" + kind); print("EASYWORK_RELIABILITY_UNSUBMITTED_UI:" + app.debugDescription) }
      // END missing submission diagnostics.
      let id = try XCTUnwrap(operation); let canonicalRun = try XCTUnwrap(runId)
      if kind == "app" { app.terminate(); launch() }
      else if kind == "relay" { try await control("relay.restart") }
      else {
        try await control("host.restart")
        for _ in 0..<120 {
          if (try await evidence())["hostEpoch"] as? String != beforeRestart["hostEpoch"] as? String { break }
          try await Task.sleep(for: .seconds(1))
        }
      }
      for _ in 0..<100 {
        if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
        if app.buttons["查询状态"].exists && app.buttons["查询状态"].isEnabled { app.buttons["查询状态"].tap() }
        let state = try await evidence()
        let requests = state["requests"] as? [[String: Any]] ?? []
        if requests.contains(where: { ($0["request"] as? [String: Any])?["method"] as? String == "command/status" && (($0["request"] as? [String: Any])?["params"] as? [String: Any])?["commandId"] as? String == id && ($0["response"] as? [String: Any])?["ok"] as? Bool == true }) { break }
        try await Task.sleep(for: .seconds(1))
        if !app.buttons["查询状态"].exists { refresh() }
      }
      if kind != "host" {
        var done = false
        for _ in 0..<240 {
          approveEasyWorkFixtureIfNeeded()
          let state = try await evidence()
          let desktop = state["independentDesktop"] as? [String: Any] ?? [:]
          if (desktop["executions"] as? [[String: Any]] ?? []).contains(where: { $0["id"] as? String == canonicalRun && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) { done = true; break }
          try await Task.sleep(for: .seconds(1))
        }
        XCTAssertTrue(done, "Interrupted response must retain the actual canonical execution")
      }
      let state = try await evidence()
      let requests = state["requests"] as? [[String: Any]] ?? []
      let status = try XCTUnwrap(requests.last(where: { ($0["request"] as? [String: Any])?["method"] as? String == "command/status" && (($0["request"] as? [String: Any])?["params"] as? [String: Any])?["commandId"] as? String == id }))
      let receipt = (status["response"] as? [String: Any])?["result"] as? [String: Any]
      XCTAssertEqual(receipt?["state"] as? String, "completed")
      XCTAssertEqual(((receipt?["response"] as? [String: Any])?["result"] as? [String: Any])?["runId"] as? String, canonicalRun)
      let desktop = state["independentDesktop"] as? [String: Any] ?? [:]
      let matching = (desktop["executions"] as? [[String: Any]] ?? []).filter { $0["id"] as? String == canonicalRun }
      XCTAssertEqual(matching.count, 1)
      if kind == "host" {
        XCTAssertTrue(["paused", "cancelled", "completed", "interrupted"].contains((matching.first?["run"] as? [String: Any])?["status"] as? String ?? ""), "A crashed execution must have an explicit canonical state")
      }
      XCTAssertFalse(app.buttons["查询状态"].exists, app.debugDescription)
      shot("native-inflight-restart-" + kind)
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": kind + "-restart-inflight-durable-receipt", "outcome": "passed", "proof": ["operationId": id, "runId": canonicalRun, "fileName": "reliability-\(kind)-\(suffix).txt"]], options: [.sortedKeys]), as: UTF8.self))
    }
    for kind in ["app", "relay", "host"] {
      if reusedScenes.contains(where: { $0["scenario"] as? String == kind + "-restart-inflight-create-once" }) { continue }
      let beforeCreate = try await evidence()
      let oldThreads = Set(((beforeCreate["independentDesktop"] as? [String: Any])?["snapshots"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String })
      newThread()
      let fileName = "reliability-create-\(kind)-\(suffix).txt"
      let marker = "CREATION_RECOVERED_" + kind + "_" + suffix
      try await control(kind == "host" ? "host.crash-next-create" : "response.delay-next-create")
      composer.tap()
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      typeComposerText("Use a terminal tool to create \(fileName) containing exactly one line RELIABILITY_ONCE. The file does not exist. After one successful write, do not write again. Then reply \(marker).")
      app.buttons["composer-dismiss-keyboard"].tap()
      app.buttons["send-message"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      var creationId: String?
      var createdThread: String?
      for _ in 0..<90 {
        let state = try await evidence()
        let fault = (state["faults"] as? [[String: Any]] ?? []).last { $0["operation"] as? String == (kind == "host" ? "host.crash-next-create" : "response.delay-next-create") }
        if let id = fault?["requestId"] as? String,
          let record = (state["requests"] as? [[String: Any]])?.last(where: { ($0["request"] as? [String: Any])?["requestId"] as? String == id }) {
          creationId = id
          createdThread = (((record["response"] as? [String: Any])?["result"] as? [String: Any])?["thread"] as? [String: Any])?["id"] as? String
          break
        }
        retryUnsentDraft(marker)
        try await Task.sleep(for: .seconds(1))
      }
      let operation = try XCTUnwrap(creationId); let thread = try XCTUnwrap(createdThread)
      if kind == "app" { app.terminate(); launch() }
      else { try await control(kind == "host" ? "host.restart" : "relay.restart") }
      var canonicalRun: String?
      var finishedCreation = false
      for _ in 0..<300 {
        approveEasyWorkFixtureIfNeeded()
        let state = try await evidence()
        let records = state["requests"] as? [[String: Any]] ?? []
        let start = records.last { record in
          let request = record["request"] as? [String: Any] ?? [:]
          let params = request["params"] as? [String: Any] ?? [:]
          return request["method"] as? String == "turn/start" && (params["parts"] as? [[String: Any]] ?? []).contains { (($0["data"] as? [String: Any])?["text"] as? String ?? "").contains(marker) }
        }
        if let start {
          XCTAssertEqual(((start["request"] as? [String: Any])?["params"] as? [String: Any])?["threadId"] as? String, thread)
          canonicalRun = ((start["response"] as? [String: Any])?["result"] as? [String: Any])?["runId"] as? String
        }
        if let run = canonicalRun, ((state["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []).contains(where: { $0["id"] as? String == run && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) { finishedCreation = true; break }
        if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
        if canonicalRun == nil, app.buttons["send-message"].exists, app.buttons["send-message"].isEnabled, (composer.value as? String ?? "").contains(marker) {
          if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
          app.buttons["send-message"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        try await Task.sleep(for: .seconds(1))
      }
      XCTAssertTrue(finishedCreation, "Restart must recover the original creation and first execution")
      let proof = try await evidence()
      let createdIds = Set((proof["requests"] as? [[String: Any]] ?? []).filter { ($0["request"] as? [String: Any])?["requestId"] as? String == operation }.compactMap { ((($0["response"] as? [String: Any])?["result"] as? [String: Any])?["thread"] as? [String: Any])?["id"] as? String })
      XCTAssertEqual(createdIds, Set([thread]))
      let newThreads = Set(((proof["independentDesktop"] as? [String: Any])?["snapshots"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }).subtracting(oldThreads)
      XCTAssertEqual(newThreads, Set([thread]))
      let desktop = proof["independentDesktop"] as? [String: Any] ?? [:]
      let actualRuns = (desktop["executions"] as? [[String: Any]] ?? []).filter { ($0["run"] as? [String: Any])?["threadId"] as? String == thread }
      XCTAssertEqual(actualRuns.count, 1, "Creation recovery must execute the first message once")
      let snapshot = try XCTUnwrap((desktop["snapshots"] as? [[String: Any]] ?? []).first { $0["id"] as? String == thread })
      XCTAssertEqual((snapshot["messages"] as? [[String: Any]] ?? []).filter { $0["role"] as? String == "user" }.count, 1)
      shot("native-inflight-create-restart-" + kind)
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": kind + "-restart-inflight-create-once", "outcome": "passed", "proof": ["operationId": operation, "threadId": thread, "runId": try XCTUnwrap(canonicalRun), "fileName": fileName]], options: [.sortedKeys]), as: UTF8.self))
    }
  }

  @MainActor private func runEasyWorkIsolation(_ initial: [String: Any]) async throws {
    let suffix = String((initial["controlAuthorization"] as? String ?? UUID().uuidString).prefix(8))
    func evidence() async throws -> [String: Any] {
      let (bytes, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    func control(_ operation: String) async throws {
      let state = try await evidence()
      var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/" + (operation.hasSuffix(".restart") || operation == "relay.offline" ? "restart" : "host"))!)
      request.httpMethod = "POST"; request.setValue(try XCTUnwrap(state["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      if let id = (try JSONSerialization.jsonObject(with: bytes) as? [String: Any])?["id"] as? String {
        for _ in 0..<120 {
          if let done = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(done["accepted"] as? Bool, true, String(describing: done)); return }
          try await Task.sleep(for: .seconds(1))
        }
        XCTFail("Unacknowledged isolation control " + operation)
      }
    }
    func target() -> [String] { (app.staticTexts["agent-dev-target"].value as? String ?? "").components(separatedBy: "|") }
    func clearAlert() { if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() } }
    func dismissMenu() {
      if app.buttons["刷新历史"].exists {
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.65)).tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["刷新历史"])
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed)
      }
    }
    func refresh() { clearAlert(); dismissMenu(); app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap(); dismissMenu() }
    func preference(_ label: String, wait: Bool = true) {
      clearAlert(); dismissMenu(); app.buttons["client-switch"].tap()
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch.tap()
      app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch.tap(); app.buttons[label].tap()
      app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
      if wait {
        let route = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", label == "仅直连" ? "直连" : "中继"), object: app.buttons["client-switch"])
        XCTAssertEqual(XCTWaiter.wait(for: [route], timeout: 60), .completed)
      }
    }
    func select(_ thread: String) {
      clearAlert(); if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      dismissMenu()
      app.buttons["open-sidebar"].tap()
      let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@", "recent-thread-" + thread, "thread-" + thread)).firstMatch
      XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription); row.tap()
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "|" + thread), object: app.staticTexts["agent-dev-target"])
      XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 15), .completed, app.debugDescription)
      XCTAssertEqual(target().dropFirst().first, thread)
    }
    func pairLan() async throws {
      app.terminate(); app.launchChineseTest()
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
      XCTAssertTrue(app.staticTexts["agent-dev-target"].waitForExistence(timeout: 5))
      // Await the previous revoked/offline connection's real terminal UI
      // before opening the public pairing route over that cached client.
      if app.alerts["无法完成操作"].waitForExistence(timeout: 45) { clearAlert() }
      // Exercise the registered public pairing-link route. No credentials or
      // binding results are injected into native storage.
      try await control("phone.open-pair-link")
      let systemOpen = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons["打开"]
      if systemOpen.waitForExistence(timeout: 5) { systemOpen.tap() }
      let pair = app.buttons["easywork-pair-lan"]
      for _ in 0..<6 where !pair.exists { app.swipeUp() }
      XCTAssertTrue(pair.waitForExistence(timeout: 10)); XCTAssertTrue(pair.isHittable)
      pair.tap()
      let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 90), .completed, app.debugDescription)
      XCTAssertTrue(app.staticTexts["agent-dev-target"].exists, "The public URL route must preserve the running DEV application")
    }
    func pairRelay() async throws {
      clearAlert(); dismissMenu(); app.buttons["client-switch"].tap()
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'edit-client-easywork-' ")).firstMatch.tap()
      selectConnectionMethod("通过中继连接", agent: "easywork")
      for _ in 0..<6 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
      XCTAssertTrue(app.buttons["easywork-dev-login"].isHittable)
      app.buttons["easywork-dev-login"].tap()
      let state = try await evidence()
      let liveOffer = try XCTUnwrap(state["relayOffer"] as? [String: Any])
      XCTAssertEqual(app.textFields["easywork-relay-url"].value as? String, state["relayURL"] as? String, "Re-pairing must address the current real Relay")
      let codeField = app.secureTextFields["easywork-relay-code"]
      for _ in 0..<6 where !codeField.isHittable { app.swipeUp() }
      XCTAssertTrue(codeField.waitForExistence(timeout: 10)); XCTAssertTrue(codeField.isHittable)
      replaceText(codeField, with: try XCTUnwrap(liveOffer["pairingCode"] as? String))
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
      app.swipeUp(); app.buttons["easywork-pair-relay"].tap()
      let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 90), .completed, app.debugDescription)
    }
    func passed(_ name: String, _ proof: [String: Any] = [:]) throws {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": name, "outcome": "passed", "proof": proof], options: [.sortedKeys]), as: UTF8.self))
    }
    // BEGIN isolated workspace completion resume
    if let resumed = initial["reuseIsolation"] as? [String: Any] {
      app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
      for scene in try XCTUnwrap(resumed["scenarios"] as? [[String: Any]]) {
        try passed(try XCTUnwrap(scene["scenario"] as? String), try XCTUnwrap(scene["proof"] as? [String: Any]))
      }
      let proof = try XCTUnwrap(resumed["pendingWorkspace"] as? [String: Any])
      let thread = try XCTUnwrap(proof["threadId"] as? String)
      let oldClient = try XCTUnwrap(proof["oldClientId"] as? String)
      let newClient = try XCTUnwrap(proof["newClientId"] as? String)
      let priorDraft = try XCTUnwrap(proof["oldDraft"] as? String)
      var freshWorkspaceRun: String?
      clearAlert(); dismissMenu()
      if proof["executionReused"] as? Bool == false {
        try await pairLan()
        XCTAssertEqual(target().first, newClient)
        select(thread); refresh()
        let inputReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
        XCTAssertEqual(XCTWaiter.wait(for: [inputReady], timeout: 60), .completed, app.debugDescription)
        XCTAssertFalse((composer.value as? String ?? "").contains(priorDraft))
        let fileName = try XCTUnwrap(proof["fileName"] as? String)
        let marker = "WORKSPACE_COMPLETED_" + (try XCTUnwrap(proof["suffix"] as? String))
        composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        typeComposerText("使用文件系统工具完成且仅完成一次成功写入：若 \(fileName) 不存在，直接创建内容为一行 WORKSPACE_ONCE 的文件；若已存在，追加且仅追加一行 WORKSPACE_ONCE。不要先写入标记再追加标记。完成后只回复 \(marker)。")
        if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
        XCTAssertTrue(app.buttons["send-message"].isEnabled); app.buttons["send-message"].tap()
        var migratedRun: String?
        for _ in 0..<300 {
          let state = try await evidence()
          let desktop = state["independentDesktop"] as? [String: Any] ?? [:]
          let runs = desktop["executions"] as? [[String: Any]] ?? []
          for snapshot in desktop["snapshots"] as? [[String: Any]] ?? [] where snapshot["id"] as? String == thread {
            for message in snapshot["messages"] as? [[String: Any]] ?? [] where message["role"] as? String == "assistant" && message["status"] as? String == "complete" {
              let text = String(decoding: try JSONSerialization.data(withJSONObject: message), as: UTF8.self)
              if text.contains(marker), runs.contains(where: { $0["id"] as? String == message["sourceRunId"] as? String && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) { migratedRun = message["sourceRunId"] as? String }
            }
          }
          if migratedRun != nil { break }
          try await Task.sleep(for: .seconds(1))
        }
        freshWorkspaceRun = try XCTUnwrap(migratedRun, "The repaired migrated Host must execute through its newly authorized target")
        let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
        XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 30), .completed)
      } else {
        app.buttons["client-switch"].tap(); app.buttons["client-" + newClient].tap()
        select(thread); refresh()
      }
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed)
      XCTAssertEqual(target().first, newClient)
      XCTAssertFalse((composer.value as? String ?? "").contains(priorDraft))
      composer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      typeComposerText("NEW_WORKSPACE_DRAFT_" + (try XCTUnwrap(proof["suffix"] as? String)))
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      app.buttons["client-switch"].tap(); app.buttons["client-" + oldClient].tap()
      select(thread); XCTAssertEqual(composer.value as? String, priorDraft)
      XCTAssertFalse(app.buttons["send-message"].isEnabled)
      var result = proof; if let freshWorkspaceRun { result["runId"] = freshWorkspaceRun }; result.removeValue(forKey: "oldClientId"); result.removeValue(forKey: "newClientId"); result.removeValue(forKey: "oldDraft"); result.removeValue(forKey: "threadId"); result.removeValue(forKey: "suffix")
      try passed("workspace-migration-old-target-write-denial-cache-isolation", result); shot("native-workspace-isolation")
      return
    }
    // END isolated workspace completion resume
    app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20)); try await pairLan(); try await pairRelay(); preference("仅中继"); preference("仅直连")
    try await control("relay.offline")
    let offlineBefore = ((try await evidence())["requests"] as? [[String: Any]] ?? []).count
    refresh()
    var offlineRead: String?
    for _ in 0..<60 {
      let records = ((try await evidence())["requests"] as? [[String: Any]] ?? []).dropFirst(offlineBefore)
      if let record = records.last(where: { $0["transportId"] as? String == "lan-wss" && ($0["request"] as? [String: Any])?["method"] as? String == "thread/sync" && ($0["response"] as? [String: Any])?["ok"] as? Bool == true }) {
        offlineRead = (record["request"] as? [String: Any])?["requestId"] as? String; break
      }
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertNotNil(offlineRead, "LAN history must remain authoritative while Relay is offline")
    try await control("relay.restart"); try await Task.sleep(for: .seconds(20))
    preference("仅中继"); preference("仅直连")
    var isolated: [String] = []
    for _ in 0..<2 {
      app.buttons["attachment-menu"].tap(); app.buttons["agent-dev-duplicate-create"].tap()
      let done = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH 'create:passed:'"), object: app.staticTexts["agent-dev-operation-evidence"])
      XCTAssertEqual(XCTWaiter.wait(for: [done], timeout: 90), .completed, app.staticTexts["agent-dev-operation-evidence"].exists ? app.staticTexts["agent-dev-operation-evidence"].label : "Missing replay evidence")
      let requests = (try await evidence())["requests"] as? [[String: Any]] ?? []
      let created = try XCTUnwrap(requests.last { ($0["request"] as? [String: Any])?["method"] as? String == "thread/create" })
      let id = try XCTUnwrap((((created["response"] as? [String: Any])?["result"] as? [String: Any])?["thread"] as? [String: Any])?["id"] as? String)
      isolated.append(id)
      try await Task.sleep(for: .seconds(2))
    }
    XCTAssertEqual(Set(isolated).count, 2)
    let draftA = "DRAFT_A_" + suffix, draftB = "DRAFT_B_" + suffix
    select(isolated[0]); composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10)); typeComposerText(draftA)
    select(isolated[1]); XCTAssertFalse((composer.value as? String ?? "").contains(draftA)); composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10)); typeComposerText(draftB)
    select(isolated[0]); XCTAssertEqual(composer.value as? String, draftA)
    try passed("similar-thread-draft-cache-isolation", ["threadIds": isolated, "relayOfflineReadRequestId": try XCTUnwrap(offlineRead)]); shot("native-thread-scope-isolation")
    try await control("response.delay-next-sync"); refresh()
    var delayed = false
    for _ in 0..<60 {
      if ((try await evidence())["faults"] as? [[String: Any]] ?? []).contains(where: { $0["operation"] as? String == "response.delay-next-sync" }) { delayed = true; break }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(delayed); select(isolated[1]); try await Task.sleep(for: .seconds(7))
    XCTAssertEqual(target().dropFirst().first, isolated[1]); XCTAssertEqual(composer.value as? String, draftB)
    try passed("late-authoritative-response-target-isolation"); shot("native-late-response-isolation")
    let oldTarget = target(); let before = try await evidence()
    try await control("relay.offline"); try await control("device.revoke"); refresh()
    let offline = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == false"), object: app.buttons["send-message"])
    XCTAssertEqual(XCTWaiter.wait(for: [offline], timeout: 45), .completed); XCTAssertEqual(composer.value as? String, draftB)
    try await control("host.restart")
    var hostRestarted = false
    for _ in 0..<180 {
      let current = try await evidence()
      if current["hostEpoch"] as? String != before["hostEpoch"] as? String, current["status"] as? String == "ready" { hostRestarted = true; break }
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertTrue(hostRestarted, "The real Host must initialize its LAN service while Relay is offline")
    app.terminate(); app.launchChineseTest(); XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20)); refresh()
    try await Task.sleep(for: .seconds(10)); clearAlert(); XCTAssertFalse(app.buttons["send-message"].isEnabled)
    try await control("relay.restart"); preference("仅中继", wait: false); refresh(); try await Task.sleep(for: .seconds(12)); clearAlert()
    XCTAssertFalse(app.buttons["send-message"].isEnabled); XCTAssertEqual(composer.value as? String, draftB)
    let revoked = try await evidence()
    let beforeRuns = (before["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
    let afterRuns = (revoked["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []
    XCTAssertEqual(Set(beforeRuns.compactMap { $0["id"] as? String }), Set(afterRuns.compactMap { $0["id"] as? String }))
    try await pairLan(); preference("仅直连"); select(isolated[1]); XCTAssertEqual(composer.value as? String, draftB)
    XCTAssertEqual(target().first, oldTarget.first)
    try passed("offline-revocation-both-paths-host-restart-explicit-repair", ["beforeHostEpoch": try XCTUnwrap(before["hostEpoch"] as? String), "recoveredHostEpoch": try XCTUnwrap(revoked["hostEpoch"] as? String)]); shot("native-revocation-repair")
    let migratedBefore = try await evidence(); try await control("workspace.migrate"); try await control("workspace.restart")
    var migrated: [String: Any] = [:]
    for _ in 0..<180 {
      let current = try await evidence()
      if current["hostEpoch"] as? String != migratedBefore["hostEpoch"] as? String, current["status"] as? String == "ready" { migrated = current; break }
      try await Task.sleep(for: .seconds(1))
    }
    let oldIdentity = try XCTUnwrap(migratedBefore["target"] as? [String: Any]); let newIdentity = try XCTUnwrap(migrated["target"] as? [String: Any])
    XCTAssertEqual(oldIdentity["desktopDeviceId"] as? String, newIdentity["desktopDeviceId"] as? String)
    XCTAssertNotEqual(oldIdentity["workspaceId"] as? String, newIdentity["workspaceId"] as? String)
    refresh(); try await Task.sleep(for: .seconds(10)); clearAlert(); XCTAssertFalse(app.buttons["send-message"].isEnabled); XCTAssertEqual(composer.value as? String, draftB)
    try await pairLan(); XCTAssertNotEqual(target().first, oldTarget.first)
    select(isolated[1]); XCTAssertFalse((composer.value as? String ?? "").contains(draftB))
    let fileName = "workspace-scope-proof-" + suffix + ".txt"
    let marker = "WORKSPACE_COMPLETED_" + suffix
    composer.tap(); XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10)); typeComposerText("使用文件系统工具完成且仅完成一次成功写入：若 \(fileName) 不存在，直接创建内容为一行 WORKSPACE_ONCE 的文件；若已存在，追加且仅追加一行 WORKSPACE_ONCE。不要先写入标记再追加标记。完成后只回复 \(marker)。")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    XCTAssertTrue(app.buttons["send-message"].isEnabled); app.buttons["send-message"].tap()
    var migratedRun: String?
    for _ in 0..<300 {
      let state = try await evidence()
      let desktop = state["independentDesktop"] as? [String: Any] ?? [:]
      let runs = desktop["executions"] as? [[String: Any]] ?? []
      for snapshot in desktop["snapshots"] as? [[String: Any]] ?? [] where snapshot["id"] as? String == isolated[1] {
        for message in snapshot["messages"] as? [[String: Any]] ?? [] where message["role"] as? String == "assistant" && message["status"] as? String == "complete" {
          let text = String(decoding: try JSONSerialization.data(withJSONObject: message), as: UTF8.self)
          if text.contains(marker), runs.contains(where: { $0["id"] as? String == message["sourceRunId"] as? String && ($0["run"] as? [String: Any])?["status"] as? String == "completed" }) { migratedRun = message["sourceRunId"] as? String }
        }
      }
      if migratedRun != nil { break }
      try await Task.sleep(for: .seconds(1))
    }
    let runId = try XCTUnwrap(migratedRun, "New workspace must execute through its newly authorized target")
    let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 30), .completed)
    let draftReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
    XCTAssertEqual(XCTWaiter.wait(for: [draftReady], timeout: 60), .completed)
    composer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    typeComposerText("NEW_WORKSPACE_DRAFT_" + suffix)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["client-switch"].tap(); app.buttons["client-" + (oldTarget.first ?? "")].tap()
    select(isolated[1]); XCTAssertEqual(composer.value as? String, draftB)
    try passed("workspace-migration-old-target-write-denial-cache-isolation", ["oldTarget": oldIdentity, "newTarget": newIdentity, "runId": runId, "fileName": fileName]); shot("native-workspace-isolation")
  }

  @MainActor private func runEasyWorkFeatures(_ initial: [String: Any]) async throws {
    let suffix = String((initial["controlAuthorization"] as? String ?? UUID().uuidString).prefix(8))
    func evidence() async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func control(_ operation: String) async throws {
      let state = try await evidence()
      var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/" + (operation.hasSuffix(".restart") || operation == "relay.offline" ? "restart" : "host"))!)
      request.httpMethod = "POST"; request.setValue(try XCTUnwrap(state["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      if let id = (try JSONSerialization.jsonObject(with: bytes) as? [String: Any])?["id"] as? String {
        for _ in 0..<120 {
          if let done = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(done["accepted"] as? Bool, true, String(describing: done)); return }
          try await Task.sleep(for: .seconds(1))
        }
        XCTFail("Unacknowledged isolation control " + operation)
      }
    }
    func refresh() {
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
      if app.buttons["刷新历史"].exists { app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.65)).tap() }
    }
    func requests(_ value: [String: Any], _ method: String) -> [[String: Any]] {
      (value["requests"] as? [[String: Any]] ?? []).filter { ($0["request"] as? [String: Any])?["method"] as? String == method && ($0["response"] as? [String: Any])?["ok"] as? Bool == true }
    }
    func params(_ value: [String: Any]) -> [String: Any] { (value["request"] as? [String: Any])?["params"] as? [String: Any] ?? [:] }
    func runs(_ value: [String: Any], _ thread: String) -> [[String: Any]] {
      ((value["independentDesktop"] as? [String: Any])?["executions"] as? [[String: Any]] ?? []).filter { ($0["run"] as? [String: Any])?["threadId"] as? String == thread }
    }
    func snapshot(_ value: [String: Any], _ thread: String) -> [String: Any] {
      ((value["independentDesktop"] as? [String: Any])?["snapshots"] as? [[String: Any]] ?? []).first { $0["id"] as? String == thread } ?? [:]
    }
    func passed(_ scene: String, _ proof: [String: Any]) throws {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": scene, "outcome": "passed", "proof": proof], options: [.sortedKeys]), as: UTF8.self))
    }
    func noKeyboard() {
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    }
    func send(_ text: String, mode: String? = nil) {
      // Respect native input availability after the previous immutable submission.
      let editable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
      XCTAssertEqual(XCTWaiter.wait(for: [editable], timeout: 45), .completed, app.debugDescription)
      composer.tap()
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      typeComposerText(text); noKeyboard()
      XCTAssertEqual(composer.value as? String, text)
      if let mode { app.segmentedControls["reply-mode"].buttons[mode].tap() }
      let action = app.buttons["send-message"]
      let writable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: action)
      XCTAssertEqual(XCTWaiter.wait(for: [writable], timeout: 45), .completed, app.debugDescription)
      action.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      // End native input availability precondition.
    }
    // BEGIN current feature client selection
    func currentClientId() -> String {
      let id = (app.staticTexts["agent-dev-target"].value as? String ?? "").components(separatedBy: "|").first ?? ""
      XCTAssertTrue(id.hasPrefix("easywork-"), app.debugDescription)
      return id
    }
    // END current feature client selection
    func preference(_ title: String) {
      let clientId = currentClientId()
      noKeyboard(); app.buttons["client-switch"].tap()
      let edit = app.buttons["edit-client-" + clientId]
      XCTAssertTrue(edit.waitForExistence(timeout: 10)); edit.tap()
      app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch.tap(); app.buttons[title].firstMatch.tap()
      app.buttons["cancel-client-form"].tap(); app.navigationBars.buttons["完成"].tap()
      let route = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", title == "仅直连" ? "直连" : "中继"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [route], timeout: 60), .completed, app.debugDescription)
    }
    func newThread() {
      noKeyboard(); app.buttons["new-chat"].tap()
      XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10)); app.buttons["choose-projectless"].tap()
    }
    func finished(_ thread: String, marker: String? = nil, count: Int = 1) async throws -> [String: Any] {
      for _ in 0..<600 {
        if app.buttons["approval-approve"].exists && app.buttons["approval-approve"].isEnabled {
          app.buttons["approval-approve"].tap()
        }
        let data = try await evidence(), executions = runs(data, thread)
        let completed = executions.filter { ($0["run"] as? [String: Any])?["status"] as? String == "completed" && ($0["providerUsage"] as? [Any])?.isEmpty == false }
        let canonical = snapshot(data, thread)
        let matchedReply = marker == nil || (canonical["messages"] as? [[String: Any]] ?? []).contains { message in
          guard message["role"] as? String == "assistant", message["status"] as? String == "complete",
            completed.contains(where: { $0["id"] as? String == message["sourceRunId"] as? String }) else { return false }
          return (message["content"] as? [[String: Any]] ?? []).contains { $0["type"] as? String == "text" && ($0["text"] as? String ?? "").contains(marker!) }
        }
        if completed.count >= count, matchedReply, ((canonical["snapshot"] as? [String: Any])?["activeRun"] as? [String: Any]) == nil {
          let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
          XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 30), .completed, app.debugDescription)
          return data
        }
        try await Task.sleep(for: .seconds(1))
      }
      shot("native-features-failure"); XCTFail("Real task did not complete in " + thread)
      throw NSError(domain: "RealDEV", code: 4)
    }
    func createdThread(_ text: String) async throws -> String {
      for _ in 0..<90 {
        let current = try await evidence()
        if let request = requests(current, "turn/start").last(where: { String(describing: params($0)["parts"] ?? "").contains(text) }), let id = params(request)["threadId"] as? String { return id }
        try await Task.sleep(for: .seconds(1))
      }
      throw NSError(domain: "RealDEV", code: 5)
    }
    app.launchArguments = ["-ui-testing", "-restore-testing", "-easywork-local-dev"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    // Fresh features re-authorize the managed Host through its public current offer.
    // The isolation phase may have last selected a different workspace identity.
    if initial["reuseFeaturesLan"] == nil {
      if app.alerts["无法完成操作"].waitForExistence(timeout: 10) { app.alerts.buttons["好"].tap() }
      try await control("phone.open-pair-link")
      let systemOpen = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.buttons["打开"]
      if systemOpen.waitForExistence(timeout: 5) { systemOpen.tap() }
      let pair = app.buttons["easywork-pair-lan"]
      for _ in 0..<6 where !pair.exists { app.swipeUp() }
      XCTAssertTrue(pair.waitForExistence(timeout: 10)); XCTAssertTrue(pair.isHittable); pair.tap()
      let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 90), .completed, app.debugDescription)
    }
    for route in ["lan", "relay"] {
      // Reused LAN scenes retain their original target and fingerprint.
      if route == "lan", let reused = initial["reuseFeaturesLan"] as? [String: Any], let scenes = reused["scenarios"] as? [[String: Any]] {
        for scene in scenes { var proof = scene["proof"] as? [String: Any] ?? [:]; proof["reused"] = true; proof["directory"] = reused["directory"]; proof["fingerprint"] = reused["fingerprint"]; try passed(try XCTUnwrap(scene["scenario"] as? String), proof) }
        continue
      }
      // End reused LAN scenes.
      // Restore Relay through the public form after an examiner reset its test storage.
      if route == "relay" {
        if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
        let clientId = currentClientId()
        noKeyboard(); app.buttons["client-switch"].tap()
        app.buttons["edit-client-" + clientId].tap()
        app.descendants(matching: .any).matching(identifier: "connection-preference").firstMatch.tap()
        app.buttons["仅直连"].firstMatch.tap()
        selectConnectionMethod("通过中继连接", agent: "easywork")
        for _ in 0..<6 where !app.buttons["easywork-dev-login"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.buttons["easywork-dev-login"].isHittable)
        app.buttons["easywork-dev-login"].tap()
        let relayState = try await evidence()
        let relayOffer = try XCTUnwrap(relayState["relayOffer"] as? [String: Any])
        for _ in 0..<6 where !app.textFields["easywork-relay-url"].isHittable { app.swipeDown() }
        XCTAssertTrue(app.textFields["easywork-relay-url"].isHittable)
        replaceText(app.textFields["easywork-relay-url"], with: try XCTUnwrap(relayState["relayURL"] as? String))
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        for _ in 0..<6 where !app.secureTextFields["easywork-relay-code"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.secureTextFields["easywork-relay-code"].isHittable)
        replaceText(app.secureTextFields["easywork-relay-code"], with: try XCTUnwrap(relayOffer["pairingCode"] as? String))
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        app.swipeUp(); app.buttons["easywork-pair-relay"].tap()
        let paired = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["client-switch"])
        XCTAssertEqual(XCTWaiter.wait(for: [paired], timeout: 90), .completed, app.debugDescription)
      }
      // End public Relay precondition; all feature assertions below remain unchanged.
      preference(route == "lan" ? "仅直连" : "仅中继")
      // Reuse only the independently verified Relay project/settings prefix.
      let relayPrefix = route == "relay" ? (initial["reuseFeaturesLan"] as? [String: Any])?["relayPrefix"] as? [[String: Any]] : nil
      if let relayPrefix {
        for scene in relayPrefix.prefix(2) {
          var proof = scene["proof"] as? [String: Any] ?? [:]
          proof["reused"] = true
          proof["directory"] = (initial["reuseFeaturesLan"] as? [String: Any])?["directory"]
          proof["fingerprint"] = (initial["reuseFeaturesLan"] as? [String: Any])?["fingerprint"]
          try passed(try XCTUnwrap(scene["scenario"] as? String), proof)
        }
      }
      // End Relay prefix presentation.
      if relayPrefix == nil {
      noKeyboard(); app.buttons["open-sidebar"].tap()
      let addProject = app.buttons["add-project"]
      XCTAssertTrue(addProject.waitForExistence(timeout: 10)); addProject.tap()
      let projectName = "Full DEV " + route + " " + suffix
      app.textFields["project-name"].tap(); app.textFields["project-name"].typeText(projectName)
      noKeyboard(); app.buttons["confirm-project"].tap()
      if app.alerts["无法完成操作"].waitForExistence(timeout: 4) {
        XCTAssertTrue(app.alerts.staticTexts.matching(NSPredicate(format: "label CONTAINS '目录授权'")).firstMatch.exists, app.debugDescription)
        app.alerts.buttons["好"].tap()
        let current = try await evidence()
        var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/host")!)
        request.httpMethod = "POST"; request.setValue(try XCTUnwrap(current["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": "desktop.filesystem-approve"])
        let (bytes, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let id = try XCTUnwrap((JSONSerialization.jsonObject(with: bytes) as? [String: Any])?["id"] as? String)
        for _ in 0..<60 {
          if let done = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(done["accepted"] as? Bool, true); break }
          try await Task.sleep(for: .seconds(1))
        }
        if !app.buttons["confirm-project"].exists {
          if !app.buttons["add-project"].exists { app.buttons["open-sidebar"].tap() }
          app.buttons["add-project"].tap()
          app.textFields["project-name"].tap(); app.textFields["project-name"].typeText(projectName)
        }
        app.buttons["confirm-project"].tap()
      }
      var projectProof = try await evidence()
      for _ in 0..<60 where !(projectProof["projects"] as? [[String: Any]] ?? []).contains(where: { $0["name"] as? String == projectName }) { try await Task.sleep(for: .seconds(1)); projectProof = try await evidence() }
      XCTAssertTrue((projectProof["projects"] as? [[String: Any]] ?? []).contains { $0["name"] as? String == projectName })
      let projectRequest = try XCTUnwrap(requests(projectProof, "project/create").last)
      XCTAssertNotNil(params(projectRequest)["directoryRef"] as? String)
      XCTAssertNil(params(projectRequest)["path"])
      shot("native-" + route + "-project-created")
      try passed(route + "-project-create-scoped-directory", ["request": params(projectRequest)])
      if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
      else if app.buttons["open-sidebar"].isHittable == false { app.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.4)).tap() }
      newThread()
      let generationReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["generation-settings"])
      XCTAssertEqual(XCTWaiter.wait(for: [generationReady], timeout: 35), .completed)
      app.buttons["generation-settings"].tap()
      let picker = app.descendants(matching: .any).matching(identifier: "model-picker").firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 10)); picker.tap()
      let model = app.buttons.matching(NSPredicate(format: "label CONTAINS 'GLM-5.2'")).firstMatch
      XCTAssertTrue(model.waitForExistence(timeout: 30), app.debugDescription); model.tap()
      let effort = app.descendants(matching: .any).matching(identifier: "effort-picker").firstMatch
      effort.tap()
      let high = app.buttons["高"].firstMatch
      XCTAssertTrue(high.waitForExistence(timeout: 5), app.debugDescription); high.tap()
      let permission = app.buttons["permission-confirm_high_risk"]
      for _ in 0..<4 where !permission.isHittable { app.swipeUp() }
      XCTAssertTrue(permission.isHittable); permission.tap()
      XCTAssertFalse(app.switches["fast-mode-toggle"].exists)
      shot("native-" + route + "-easywork-generation-settings")
      app.navigationBars.buttons["完成"].tap()
      let settingsMarker = "SETTINGS_" + route + "_" + suffix
      send("不要调用工具，只回复 " + settingsMarker)
      let settingsThread = try await createdThread(settingsMarker)
      let settingsProof = try await finished(settingsThread, marker: settingsMarker)
      let input = try XCTUnwrap((runs(settingsProof, settingsThread).last?["run"] as? [String: Any])?["input"] as? [String: Any])
      XCTAssertEqual((input["effectiveModelRef"] as? [String: Any])?["modelId"] as? String, "GLM-5.2")
      let preferences = try XCTUnwrap(((snapshot(settingsProof, settingsThread)["snapshot"] as? [String: Any])?["thread"] as? [String: Any])?["generationPreferences"] as? [String: Any])
      XCTAssertEqual((preferences["reasoning"] as? [String: Any])?["effort"] as? String, "high")
      XCTAssertEqual(preferences["permissionMode"] as? String, "confirm_high_risk")
      XCTAssertFalse(requests(settingsProof, "model/list").isEmpty)
      try passed(route + "-model-catalogue-generation-settings", ["threadId": settingsThread, "input": input])
      }
      // End preserved project/settings assertions.

      // Restore the completed proposal in its original Thread; otherwise prepare a new one.
      let reusedPlan = relayPrefix?.first(where: { $0["scenario"] as? String == "relay-plan-proposal-native-review" })?["proof"] as? [String: Any]
      let thread: String
      var proof: [String: Any]
      if let reusedPlan {
        thread = try XCTUnwrap(reusedPlan["threadId"] as? String)
        noKeyboard(); app.buttons["open-sidebar"].tap()
        let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@", "recent-thread-" + thread, "thread-" + thread)).firstMatch
        for _ in 0..<6 where !row.isHittable { app.swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
        proof = try await evidence()
      } else {
      // Begin unchanged fresh proposal behavior.
      newThread()
      // A restored empty draft may already have Plan mode selected.
      setComposerPlanMode(true)
      XCTAssertTrue(app.buttons["plan-mode-toggle"].waitForExistence(timeout: 10))
      // End idempotent Plan-mode selection.
      let planMarker = "PLAN_" + route + "_" + suffix
      send("只制定计划，必须使用 submit_plan 提交正式计划，不要执行。计划包含：一、用文件工具新建 feature-\(route).txt 写入 \(planMarker)；二、用终端等待 120 秒，设置至少 180 秒的工具超时；三、逐步用 update_plan 核对进度并回复 \(planMarker)_DONE。计划简洁、使用中文。")
      thread = try await createdThread(planMarker)
      proof = try await finished(thread)
      }
      // End fresh proposal behavior and restore boundary.
      XCTAssertTrue(app.buttons["implement-plan"].waitForExistence(timeout: 30), app.debugDescription)
      XCTAssertTrue(app.descendants(matching: .any)["proposed-plan-message"].firstMatch.exists)
      let original = try XCTUnwrap((snapshot(proof, thread)["messages"] as? [[String: Any]])?.flatMap { $0["content"] as? [[String: Any]] ?? [] }.last { $0["type"] as? String == "plan-artifact" })
      let originalId = try XCTUnwrap(original["planId"] as? String)
      shot("native-" + route + "-plan-proposed")
      // Keep the original proposal evidence and verify the currently displayed version.
      var proposalProof: [String: Any] = ["threadId": thread, "planId": originalId, "version": original["version"] ?? 0]
      if let reusedPlan {
        XCTAssertEqual(reusedPlan["planId"] as? String, originalId)
        XCTAssertEqual(reusedPlan["version"] as? Int, original["version"] as? Int)
        proposalProof["reused"] = true
        proposalProof["directory"] = (initial["reuseFeaturesLan"] as? [String: Any])?["directory"]
        proposalProof["fingerprint"] = (initial["reuseFeaturesLan"] as? [String: Any])?["fingerprint"]
      }
      try passed(route + "-plan-proposal-native-review", proposalProof)
      // End restored proposal evidence.
      // Start message scrolling inside the visible area above the fixed composer.
      func scrollMessagesUp() {
        let list = app.scrollViews["message-list"].frame
        let origin = app.coordinate(withNormalizedOffset: .zero)
        let start = origin.withOffset(CGVector(dx: list.midX, dy: min(list.maxY - 20, composer.frame.minY - 24)))
        let end = origin.withOffset(CGVector(dx: list.midX, dy: list.minY + 24))
        start.press(forDuration: 0.01, thenDragTo: end)
      }
      // End visible message scroll helper.
      // Wait for each plan action to become usable above the composer.
      func tapPlanAction(_ id: String) {
        let action = app.buttons[id]
        for _ in 0..<6 where !action.isHittable || action.frame.maxY >= composer.frame.minY {
          scrollMessagesUp()
        }
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: action)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed, app.debugDescription)
        action.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      }
      tapPlanAction("adjust-plan")
      // End plan-action readiness precondition.
      let feedback = app.descendants(matching: .any)["plan-feedback"].firstMatch
      XCTAssertTrue(feedback.waitForExistence(timeout: 15))
      feedback.tap(); feedback.typeText("保留全部步骤和等待时间，把第一步文件内容增加一行 REVISED_\(suffix)。使用 submit_plan 提交修订版，不执行。")
      // Scroll the feedback action above the composer and verify actual submission.
      noKeyboard()
      let submitFeedback = app.buttons["submit-plan-feedback"]
      for _ in 0..<6 where !submitFeedback.isHittable || submitFeedback.frame.maxY >= composer.frame.minY {
        scrollMessagesUp()
      }
      XCTAssertTrue(submitFeedback.isHittable && submitFeedback.frame.maxY < composer.frame.minY, app.debugDescription)
      submitFeedback.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      var revisionAccepted = false
      for _ in 0..<30 {
        if requests(try await evidence(), "turn/start").contains(where: { params($0)["revisionOfPlanId"] as? String == originalId }) { revisionAccepted = true; break }
        try await Task.sleep(for: .seconds(1))
      }
      XCTAssertTrue(revisionAccepted, "The Host must accept the revision before waiting for its model result")
      // End feedback submission precondition; existing revision evidence remains required.
      proof = try await finished(thread, count: 2)
      XCTAssertTrue(app.buttons["implement-plan"].waitForExistence(timeout: 30))
      let revision = try XCTUnwrap(requests(proof, "turn/start").last { params($0)["revisionOfPlanId"] as? String == originalId })
      XCTAssertEqual(params(revision)["expectedPlanVersion"] as? Int, original["version"] as? Int)
      tapPlanAction("implement-plan")
      XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 45))
      let tool = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tool-' AND label BEGINSWITH '正在'")).firstMatch
      func waitForLiveTool() async throws {
        for _ in 0..<600 {
          if app.buttons["approval-approve"].exists && app.buttons["approval-approve"].isEnabled { app.buttons["approval-approve"].tap() }
          if tool.exists { return }
          try await Task.sleep(for: .seconds(1))
        }
        shot("native-feature-tool-start-timeout"); XCTFail("The real model did not expose a live tool within its bounded start window")
        throw NSError(domain: "RealDEV", code: 8)
      }
      try await waitForLiveTool()
      XCTAssertTrue(app.descendants(matching: .any)["runtime-plan"].firstMatch.exists)
      proof = try await evidence()
      let approval = try XCTUnwrap(requests(proof, "plan/action").last)
      XCTAssertEqual(params(approval)["threadId"] as? String, thread)
      let runId = try XCTUnwrap(((approval["response"] as? [String: Any])?["result"] as? [String: Any])?["runId"] as? String)
      shot("native-" + route + "-plan-executing")
      try passed(route + "-plan-revision-approve-original-thread", ["threadId": thread, "runId": runId, "approval": params(approval)])
      let steer = "STEER_" + route + "_" + suffix
      send("在本次任务的最终回复中增加 " + steer + "，继续执行当前计划，不创建新任务。", mode: "立即追加")
      proof = try await evidence()
      for _ in 0..<30 where requests(proof, "turn/steer").last.map({ params($0)["message"] as? String ?? "" }).map({ !$0.contains(steer) }) ?? true {
        try await Task.sleep(for: .seconds(1)); proof = try await evidence()
      }
      let steerRequest = try XCTUnwrap(requests(proof, "turn/steer").last)
      XCTAssertEqual(params(steerRequest)["turnId"] as? String, runId)
      XCTAssertTrue((params(steerRequest)["message"] as? String ?? "").contains(steer))
      let q1 = "QUEUE_ONE_" + route + "_" + suffix, q2 = "QUEUE_CANCEL_" + route + "_" + suffix, q3 = "QUEUE_THREE_" + route + "_" + suffix
      var queueIds: [String] = []
      for marker in [q1, q2, q3] {
        send("不要调用工具，只回复 " + marker, mode: "排队执行")
        for _ in 0..<30 {
          proof = try await evidence()
          if let accepted = requests(proof, "turn/start").last(where: { params($0)["mode"] as? String == "queue" && String(describing: params($0)["parts"] ?? "").contains(marker) }), let id = params(accepted)["clientRequestId"] as? String { queueIds.append(id); break }
          try await Task.sleep(for: .seconds(1))
        }
      }
      XCTAssertEqual(queueIds.count, 3)
      let cancel = app.buttons["cancel-queued-" + queueIds[1]]
      XCTAssertTrue(cancel.waitForExistence(timeout: 15), app.debugDescription); cancel.tap()
      let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.staticTexts["queued-text-" + queueIds[1]])
      XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 20), .completed)
      if route == "lan" {
        app.terminate(); app.launchChineseTest()
        XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["queued-text-" + queueIds[0]].waitForExistence(timeout: 30), app.debugDescription)
      }
      shot("native-" + route + "-queue-persist-cancel")
      proof = try await finished(thread, marker: q3, count: 5)
      let canonical = snapshot(proof, thread)
      let messages = canonical["messages"] as? [[String: Any]] ?? []
      let originalReply = messages.filter { $0["role"] as? String == "assistant" && $0["status"] as? String == "complete" && $0["sourceRunId"] as? String == runId }
        .flatMap { $0["content"] as? [[String: Any]] ?? [] }
        .filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined(separator: "\n")
      XCTAssertTrue(originalReply.contains(steer), "Steer must reach the visible completed reply of its exact Run")
      let userMessages = messages.filter { $0["role"] as? String == "user" }
      let ordered = try userMessages.map { String(decoding: try JSONSerialization.data(withJSONObject: $0), as: UTF8.self) }
      let first = try XCTUnwrap(ordered.firstIndex { $0.contains(q1) }), third = try XCTUnwrap(ordered.firstIndex { $0.contains(q3) })
      XCTAssertLessThan(first, third)
      XCTAssertFalse(ordered.contains { $0.contains(q2) })
      XCTAssertEqual(ordered.filter { $0.contains(q1) }.count, 1)
      XCTAssertEqual(ordered.filter { $0.contains(q3) }.count, 1)
      let originalRun = try XCTUnwrap(runs(proof, thread).first { $0["id"] as? String == runId })
      XCTAssertEqual((originalRun["run"] as? [String: Any])?["status"] as? String, "completed")
      let beforeQueueRestart = try await evidence()
      let settledRunIds = Set(runs(beforeQueueRestart, thread).compactMap { $0["id"] as? String })
      try await control("host.restart")
      var queueHostRestarted = false
      for _ in 0..<180 {
        let state = try await evidence()
        if state["status"] as? String == "ready", state["hostEpoch"] as? String != beforeQueueRestart["hostEpoch"] as? String { queueHostRestarted = true; break }
        try await Task.sleep(for: .seconds(1))
      }
      XCTAssertTrue(queueHostRestarted)
      refresh(); try await Task.sleep(for: .seconds(10))
      proof = try await finished(thread, marker: q3, count: 5)
      XCTAssertEqual(Set(runs(proof, thread).compactMap { $0["id"] as? String }), settledRunIds)
      let restoredMessages = (snapshot(proof, thread)["messages"] as? [[String: Any]] ?? []).filter { $0["role"] as? String == "user" }
      let restoredText = try restoredMessages.map { String(decoding: try JSONSerialization.data(withJSONObject: $0), as: UTF8.self) }
      XCTAssertEqual(restoredText.filter { $0.contains(q1) }.count, 1)
      XCTAssertEqual(restoredText.filter { $0.contains(q3) }.count, 1)
      XCTAssertFalse(restoredText.contains { $0.contains(q2) })
      shot("native-" + route + "-plan-queue-completed")
      try passed(route + "-same-run-steer-live-plan-progress", ["threadId": thread, "runId": runId, "steer": params(steerRequest)])
      try passed(route + "-durable-queue-fifo-cancel-no-replay", ["threadId": thread, "messageIds": queueIds, "appRestarted": route == "lan", "hostRestarted": queueHostRestarted])

      newThread()
      let stopMarker = "STOP_" + route + "_" + suffix
      send("本次先使用终端等待 90 秒，然后只回复 \(stopMarker)。如果后续收到继续完成或重新执行上一个任务的消息，不再调用工具和等待，只回复 \(stopMarker)_RECOVERED。")
      let stoppedThread = try await createdThread(stopMarker)
      try await waitForLiveTool()
      app.buttons["stop-turn"].tap()
      XCTAssertTrue(app.buttons["resume-task"].waitForExistence(timeout: 45), app.debugDescription)
      let before = try await evidence(), previousIds = Set(runs(before, stoppedThread).compactMap { $0["id"] as? String })
      app.buttons["resume-task"].tap()
      proof = try await finished(stoppedThread, marker: stopMarker + "_RECOVERED")
      XCTAssertEqual(Set(runs(proof, stoppedThread).compactMap { $0["id"] as? String }).subtracting(previousIds).count, 1)
      let sendButton = app.buttons["send-message"]
      sendButton.press(forDuration: 1)
      let rerun = app.buttons["重新运行上一任务"]
      XCTAssertTrue(rerun.waitForExistence(timeout: 10)); rerun.tap()
      proof = try await finished(stoppedThread, marker: stopMarker + "_RECOVERED", count: 2)
      let explicitTurns = requests(proof, "turn/start").filter { params($0)["threadId"] as? String == stoppedThread }
      XCTAssertEqual(explicitTurns.count, 3)
      try passed(route + "-stop-continue-explicit-rerun", ["threadId": stoppedThread, "runIds": runs(proof, stoppedThread).compactMap { $0["id"] as? String }])
    }
  }

  @MainActor func testEasyWorkOrdinaryAppDEV() async throws {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let initial = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    guard initial["phase"] as? String == "observability" else { throw XCTSkip("Requires the isolated real EasyWork observability Host") }
    try await runEasyWorkObservability(initial, ordinaryOnly: true)
  }

  @MainActor private func runEasyWorkObservability(_ initial: [String: Any], ordinaryOnly: Bool = false) async throws {
    let suffix = String((initial["controlAuthorization"] as? String ?? UUID().uuidString).prefix(8))
    func evidence() async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/evidence")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func control(_ operation: String) async throws {
      var request = URLRequest(url: URL(string: "http://127.0.0.1:8798/control/host")!)
      request.httpMethod = "POST"; request.setValue(try XCTUnwrap(initial["controlAuthorization"] as? String), forHTTPHeaderField: "x-easywork-acceptance")
      request.httpBody = try JSONSerialization.data(withJSONObject: ["operation": operation])
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      let result = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
      guard let id = result["id"] as? String else { return }
      for _ in 0..<60 {
        if let entry = ((try await evidence())["controls"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) { XCTAssertEqual(entry["accepted"] as? Bool, true, String(describing: entry)); return }
        try await Task.sleep(for: .seconds(1))
      }
      XCTFail("Diagnostic control did not settle")
    }
    func passed(_ name: String, _ proof: [String: Any]) throws {
      print("EASYWORK_REAL_SCENARIO:" + String(decoding: try JSONSerialization.data(withJSONObject: ["scenario": name, "outcome": "passed", "proof": proof], options: [.sortedKeys]), as: UTF8.self))
    }
    func pair(ordinary: Bool) async throws {
      app.terminate(); app.launchArguments = ordinary ? ["-easywork-local-dev"] : ["-ui-testing", "-easywork-local-dev"]; app.launchChineseTest()
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
      if app.alerts["无法完成操作"].exists { app.alerts.buttons["好"].tap() }
      // External URL delivery follows the ordinary foreground launch transition.
      try await Task.sleep(for: .seconds(2))
      // Every presentation receives a fresh single-claim offer from the real Host.
      func openLink() async throws {
        try await control("lan.enable")
        XCUIDevice.shared.press(.home)
        try await control("phone.open-pair-link")
        let systemOpen = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["打开"]
        if systemOpen.waitForExistence(timeout: 3) { systemOpen.tap() }
        XCTAssertTrue(app.buttons["easywork-pair-lan"].waitForExistence(timeout: 20), app.debugDescription)
        let current = try await evidence()
        let fresh = try XCTUnwrap((current["lan"] as? [String: Any])?["pairingUri"] as? String)
        let field = app.descendants(matching: .any).matching(identifier: "easywork-lan-offer").firstMatch
        var matches = false
        for _ in 0..<30 {
          if field.exists, field.value as? String == fresh { matches = true; break }
          try await Task.sleep(for: .milliseconds(200))
        }
        XCTAssertTrue(matches, "Public form must display the newest Host offer")
      }
      try await openLink()
      if ordinary {
        // A newer public link must survive the older form's dismissal callback.
        try await openLink()
        app.buttons["cancel-client-form"].tap()
        // Cancellation must release that link and allow another public presentation.
        try await openLink()
      }
      app.buttons["easywork-pair-lan"].tap()
      let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS 'EasyWork' AND value CONTAINS '直连' AND value CONTAINS '实时连接'"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 90), .completed, app.debugDescription)
      if ordinary { XCTAssertFalse(app.staticTexts["agent-dev-target"].exists) }
    }
    func newThread() {
      app.buttons["new-chat"].tap(); XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10)); app.buttons["choose-projectless"].tap()
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
    }
    func send(_ prompt: String, marker: String, completedRuns: Int = 1) async throws -> (String, [String: Any]) {
      composer.tap(); typeComposerText(prompt)
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["send-message"])
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
      app.buttons["send-message"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      var thread: String?
      for _ in 0..<420 {
        if app.buttons["approval-approve"].exists && app.buttons["approval-approve"].isEnabled { app.buttons["approval-approve"].tap() }
        let current = try await evidence()
        let requests = current["requests"] as? [[String: Any]] ?? []
        if thread == nil { thread = requests.last { (($0["request"] as? [String: Any])?["method"] as? String) == "turn/start" && String(describing: (($0["request"] as? [String: Any])?["params"] as? [String: Any])?["parts"] ?? "").contains(marker) }.flatMap { (($0["request"] as? [String: Any])?["params"] as? [String: Any])?["threadId"] as? String } }
        if let thread {
          let desktop = current["independentDesktop"] as? [String: Any] ?? [:]
          let runs = (desktop["executions"] as? [[String: Any]] ?? []).filter { ($0["run"] as? [String: Any])?["threadId"] as? String == thread && ($0["run"] as? [String: Any])?["status"] as? String == "completed" && ($0["providerUsage"] as? [Any])?.isEmpty == false }
          let snapshot = (desktop["snapshots"] as? [[String: Any]] ?? []).first { $0["id"] as? String == thread } ?? [:]
          if runs.count >= completedRuns, (snapshot["snapshot"] as? [String: Any])?["activeRun"] == nil, String(describing: (snapshot["messages"] as? [[String: Any]] ?? []).filter { $0["role"] as? String == "assistant" }).contains(marker) {
            let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
            XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 30), .completed)
            return (thread, current)
          }
        }
        try await Task.sleep(for: .seconds(1))
      }
      shot("native-observability-failure"); XCTFail("Real observability task did not complete"); throw NSError(domain: "RealDEV", code: 6)
    }
    if !ordinaryOnly {
    try await pair(ordinary: false)
    newThread()
    let patchMarker = "PATCH_" + suffix
    let file = "native-patch-" + suffix + ".txt"
    let patch = "*** Begin Patch\n*** Add File: " + file + "\n+" + patchMarker + "\n*** End Patch\n"
    let (patchThread, patchEvidence) = try await send("使用 terminal_exec 执行 apply_patch，stdin 必须完整为以下补丁，保留 *** Begin Patch 开头，不改用 add 子命令：\n" + patch + "执行成功后只回复 " + patchMarker, marker: patchMarker)
    let snapshots = ((patchEvidence["independentDesktop"] as? [String: Any])?["snapshots"] as? [[String: Any]] ?? [])
    let messages = snapshots.first { $0["id"] as? String == patchThread }?["messages"] as? [[String: Any]] ?? []
    let tool = try XCTUnwrap(messages.flatMap { $0["content"] as? [[String: Any]] ?? [] }.first { $0["type"] as? String == "tool-call" && (($0["args"] as? [String: Any])?["stdin"] as? String)?.hasPrefix("*** Begin Patch") == true })
    XCTAssertEqual(tool["isError"] as? Bool, false)
    let edits = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tool-item-' AND label BEGINSWITH '已编辑'"))
    if !edits.firstMatch.exists {
      let group = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tool-group-' ")).firstMatch
      XCTAssertTrue(group.waitForExistence(timeout: 10)); group.tap()
    }
    XCTAssertTrue(edits.firstMatch.waitForExistence(timeout: 15), app.debugDescription); edits.firstMatch.tap()
    let detail = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tool-detail-' ")).firstMatch
    XCTAssertTrue(detail.waitForExistence(timeout: 10), app.debugDescription)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS '*** Begin Patch' ")).firstMatch.exists)
    let thought = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'tool-item-' AND label BEGINSWITH '已进行'"))
    XCTAssertTrue(thought.firstMatch.exists, "Actual reasoning must use the native collapsible activity view")
    shot("native-real-patch-reasoning-detail")
    try passed("lan-real-file-diff-and-collapsible-reasoning", ["threadId": patchThread, "file": file, "toolCallId": tool["toolCallId"] ?? ""])

    try await control("desktop.observability-policy-enable")
    newThread()
    let compressionMarker = "COMPRESSION_" + suffix
    var compressionThread = "", compressionEvidence: [String: Any] = [:]
    for index in 1...3 {
      let marker = compressionMarker + "_" + String(index)
      let prompt = "使用 terminal_exec 执行 python3 -c \"print('native-compression-memory ' * 6000)\"，读取真实工具输出后只回复 " + marker
      (compressionThread, compressionEvidence) = try await send(prompt, marker: marker, completedRuns: index)
    }
    let compressed = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'context-compaction-' AND label == '已压缩上下文'")).firstMatch
    for _ in 0..<8 where !compressed.exists { app.scrollViews["message-list"].swipeDown() }
    XCTAssertTrue(compressed.waitForExistence(timeout: 30), app.debugDescription)
    let canonical = ((compressionEvidence["independentDesktop"] as? [String: Any])?["snapshots"] as? [[String: Any]] ?? []).first { $0["id"] as? String == compressionThread } ?? [:]
    let parts = (canonical["messages"] as? [[String: Any]] ?? []).flatMap { $0["content"] as? [[String: Any]] ?? [] }
    let diagnostics = parts.filter { $0["type"] as? String == "agent-debug" }.flatMap { $0["contextCompressionSampling"] as? [[String: Any]] ?? [] }
    XCTAssertTrue(diagnostics.contains { ["created", "regenerated", "repaired"].contains($0["projectionStatus"] as? String ?? "") })
    shot("native-real-context-compression")
    try await control("desktop.observability-policy-restore")
    try passed("lan-real-context-compaction-native-status", ["threadId": compressionThread, "diagnostics": diagnostics])

      return
    }

    try await pair(ordinary: true)
    newThread()
    let ordinaryMarker = "ORDINARY_EASYWORK_" + suffix
    let (ordinaryThread, ordinaryEvidence) = try await send("不要调用工具，只回复 " + ordinaryMarker, marker: ordinaryMarker)
    let requests = ordinaryEvidence["requests"] as? [[String: Any]] ?? []
    XCTAssertEqual(requests.filter { (($0["request"] as? [String: Any])?["method"] as? String) == "turn/start" && (($0["request"] as? [String: Any])?["params"] as? [String: Any])?["threadId"] as? String == ordinaryThread }.count, 1)
    XCTAssertFalse(app.staticTexts["agent-dev-target"].exists)
    shot("native-ordinary-easywork-created")
    try passed("ordinary-app-easywork-projectless-create", ["threadId": ordinaryThread, "testStorage": false, "localDevelopmentIdentity": true, "supersededLinkPreserved": true, "cancelledLinkRecovered": true])
  }

  @MainActor private func approveEasyWorkFixtureIfNeeded() {
    let button = app.buttons["approval-approve"]
    if button.exists && button.isEnabled { button.tap() }
  }

  private func shot(_ name: String, fullScreen: Bool = false) {
    let screenshot = fullScreen ? XCUIScreen.main.screenshot() : app.screenshot()
    let attachment = XCTAttachment(screenshot: screenshot)
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
    if name.hasPrefix("approval-") || name.hasPrefix("real-desktop-question-") || name.hasPrefix("async-question-") {
      let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      try? screenshot.pngRepresentation.write(to: directory.appendingPathComponent(name + ".png"), options: .atomic)
    }
  }
  private var composer: XCUIElement {
    // SwiftUI's vertical TextField can expose a TextView after restoring
    // multiline text or rebuilding the composer when dictation ends.
    app.descendants(matching: .any).matching(identifier: "composer-input").firstMatch
  }


  private func typeComposerText(_ text: String) {
    composer.typeText(text)
    // Xcode 27 may return before layout settles. A physical device can expose
    // an offscreen keyboard while hardware input is active; that is valid when
    // the composer is visible and no onscreen keyboard covers it.
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { [self] _, _ in
      let keyboard = app.keyboards.firstMatch
      let field = composer.frame
      guard composer.isHittable && app.frame.contains(field) else { return false }
      return !keyboard.exists || !keyboard.frame.intersects(app.frame)
        || field.maxY <= keyboard.frame.minY
    }, object: app)
    let layout = XCTWaiter.wait(for: [ready], timeout: 8)
    if layout != .completed {
      let keyboard = app.keyboards.firstMatch
      let evidence: [String: Any] = ["app": String(describing: app.frame),
        "composer": String(describing: composer.frame), "composerHittable": composer.isHittable,
        "keyboardExists": keyboard.exists,
        "keyboard": String(describing: keyboard.exists ? keyboard.frame : .zero)]
      let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      if let bytes = try? JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys]) {
        try? bytes.write(to: directory.appendingPathComponent("composer-layout-failure.json"), options: .atomic)
        let attachment = XCTAttachment(data: bytes, uniformTypeIdentifier: "public.json")
        attachment.name = "composer-layout-failure"; attachment.lifetime = .keepAlways; add(attachment)
      }
      let screenshot = app.screenshot()
      try? screenshot.pngRepresentation.write(to: directory.appendingPathComponent("composer-layout-failure.png"), options: .atomic)
      shot("composer-layout-failure")
    }
    XCTAssertEqual(layout, .completed,
      "The composer must be visible and above any onscreen keyboard before the next action")
  }

  func testComposerTypingPerformance() throws {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-performance-fixture"]
    app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 20))
    XCTAssertTrue(app.buttons["typing-reset"].waitForExistence(timeout: 10))
    app.buttons["typing-reset"].tap()
    let prompt = String(repeating: "fast-prompt ", count: 10)
    composer.tap(); typeComposerText(prompt)
    XCTAssertEqual(composer.value as? String, prompt)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    app.buttons["typing-sample"].tap()
    let evidence = app.staticTexts["typing-evidence"]
    XCTAssertTrue(evidence.waitForExistence(timeout: 5))
    let metrics = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(evidence.label.utf8)) as? [String: Double])
    print("COMPOSER_TYPING_METRICS " + evidence.label)
    XCTAssertGreaterThan(metrics["edits"] ?? 0, 90)
    XCTAssertLessThan(metrics["p95MS"] ?? 1000, 8)
    XCTAssertEqual(metrics["mainThreadWrites"], 0)
    XCTAssertLessThan(metrics["chatRenders"] ?? 1000, 10)
    XCTAssertLessThan(metrics["writes"] ?? 1000, 15)
    shot("composer-long-cache-input")
    XCUIDevice.shared.press(.home)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-performance-fixture", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertEqual(composer.value as? String, prompt)
    switchClient("demo-studio"); XCTAssertFalse((composer.value as? String ?? "").contains(prompt))
    composer.tap(); typeComposerText("isolated-second-draft")
    switchClient("demo-mac"); XCTAssertEqual(composer.value as? String, prompt)
  }

  @MainActor func testHistoryContinuousScrollPerformance() throws {
    app.launchArguments = ["-ui-testing", "-history-scroll-fixture", "-history-scroll-probe"]
    app.launchChineseTest()
    XCTAssertTrue(app.scrollViews["message-list"].waitForExistence(timeout: 30))
    let screen = app.frame
    let top = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.55, dy: screen.height * 0.25))
    let bottom = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.55, dy: screen.height * 0.70))
    // Explicitly issue all eight gestures; XCTest warm-up counts vary by platform.
    top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
    bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    let options = XCTMeasureOptions(); options.iterationCount = 3
    measure(metrics: [XCTOSSignpostMetric.scrollDecelerationMetric], options: options) {
      top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
      bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    }
    let report = app.staticTexts["history-scroll-evidence"]
    XCTAssertTrue(report.waitForExistence(timeout: 5))
    let metrics = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(report.label.utf8)) as? [String: Double])
    print("HISTORY_SCROLL_METRICS " + report.label)
    XCTAssertGreaterThanOrEqual(metrics["gestures"] ?? 0, 8)
    XCTAssertGreaterThan(metrics["movingFrames"] ?? 0, 250, "The test must actually scroll through history")
    XCTAssertLessThanOrEqual(metrics["timelineBuilds"] ?? 1000, 2,
      "Gestures and unrelated UI state must reuse the current history projection")
    #if !targetEnvironment(simulator)
    // Simulator frame budgets depend on the host; physical evidence owns this gate.
    XCTAssertLessThan(metrics["p95FrameMS"] ?? 1000, 25)
    XCTAssertLessThan(metrics["maxFrameMS"] ?? 1000, 50)
    #endif
    XCTAssertTrue(app.buttons["open-sidebar"].isHittable)
    shot("history-continuous-scroll")
  }

  @MainActor func testHistoryShortListPrependKeepsPosition() throws {
    app.launchArguments = ["-ui-testing", "-history-short-paging-fixture", "-history-position-probe", "-history-scroll-probe"]
    app.launchChineseTest()
    let boundary = app.staticTexts["HISTORY_SHORT_068"]
    XCTAssertTrue(boundary.waitForExistence(timeout: 15))
    XCTAssertEqual(liveHistoryCounts()?["messages"], 72)
    XCTAssertEqual(liveHistoryCounts()?["window"], 4)
    let y = boundary.frame.minY
    let more = app.buttons["history-local-more"]
    XCTAssertTrue(more.isHittable)
    more.tap()
    // LazyVStack can leave the prefix offscreen and uninstantiated. Check the
    // actual source window rather than depending on an offscreen AX element.
    let expanded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      self.liveHistoryCounts()?["window"] == 64
    }, object: app)
    XCTAssertEqual(XCTWaiter.wait(for: [expanded], timeout: 5), .completed,
      "Expand exactly one cached window, from 4 to 64 of 72 source messages")
    XCTAssertTrue(boundary.isHittable)
    XCTAssertEqual(boundary.frame.minY, y, accuracy: 3,
      "A short list must retain its old reading position when a prefix fills the viewport")
    shot("history-short-list-prepend")
    let latest = app.buttons["跳转最新消息"]
    XCTAssertTrue(latest.isHittable)
    latest.tap()
    XCTAssertTrue(app.staticTexts["HISTORY_SHORT_071"].isHittable)
    XCTAssertTrue(latest.waitForNonExistence(timeout: 5))
    shot("history-short-list-return-latest")
  }

  /// Independent App, actual existing Windows history and system TLS. No model
  /// input, original connection replacement, or test-mode persistence reset.
  @MainActor func testWindowsConversationSyncReadOnlyDEV() async throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["WINDOWS_HISTORY_READ_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(encoded.utf8))
    let configuration = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    let expectedText = try XCTUnwrap(configuration["expectedText"] as? String)
    let expectedTarget = try XCTUnwrap(configuration["expectedTarget"] as? [String: String])
    XCTAssertGreaterThan(expectedText.count, 12)
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }
    for path in ["v1/workspace", "v1/history"] {
      var url = try XCTUnwrap(URL(string: fixture.endpoint)).appendingPathComponent(path)
      XCTAssertEqual(url.scheme, "https")
      url.append(queryItems: [URLQueryItem(name: "hostId", value: fixture.hostId)])
      if path.hasSuffix("history") {
        url.append(queryItems: [URLQueryItem(name: "threadId", value: fixture.threadId), URLQueryItem(name: "window", value: "latest-v1")])
      }
      var request = URLRequest(url: url); request.timeoutInterval = 40
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let start = ProcessInfo.processInfo.systemUptime
      do {
        let (data, response) = try await session.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(body["hostId"] as? String, fixture.hostId)
        let target = try XCTUnwrap(body["readTarget"] as? [String: String])
        for key in ["agentId", "hostId", "workspaceId", "runtimeId"] {
          XCTAssertNotNil(expectedTarget[key]); XCTAssertEqual(target[key], expectedTarget[key])
        }
        if path.hasSuffix("history") {
          XCTAssertEqual(body["threadId"] as? String, fixture.threadId)
          XCTAssertEqual(body["ready"] as? Bool, true)
          XCTAssertEqual((body["runtime"] as? [String: Any])?["canControl"] as? Bool, true)
          XCTAssertFalse((body["messages"] as? [[String: Any]] ?? []).isEmpty)
        }
        print("WINDOWS_READ_PROBE", path, "200", Int((ProcessInfo.processInfo.systemUptime - start) * 1000))
      } catch {
        print("WINDOWS_READ_NETWORK_ERROR", path, (error as NSError).domain, (error as NSError).code)
        throw error
      }
    }
    let existingBundle = ProcessInfo.processInfo.environment["WINDOWS_EXISTING_APP_BUNDLE"]
    if let existingBundle {
      XCTAssertEqual(existingBundle, "com.easyai.agenthub")
      app = XCUIApplication(bundleIdentifier: existingBundle)
    }
    app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    app.buttons["client-switch"].tap()
    if existingBundle != nil {
      XCTAssertTrue(app.buttons["client-" + fixture.hostId].exists, "The original connection must exist; never replace it")
    }
    if app.buttons["client-" + fixture.hostId].exists {
      app.buttons["client-" + fixture.hostId].tap()
    } else {
      guard existingBundle == nil else { return }
      app.buttons["add-client"].tap(); selectConnectionMethod("手动连接", agent: "codex")
      let address = app.textFields["client-address"]
      XCTAssertTrue(address.waitForExistence(timeout: 15)); address.tap()
      address.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (address.value as? String ?? "").count))
      address.typeText(fixture.endpoint)
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
      let key = app.secureTextFields["client-token"]
      XCTAssertTrue(key.waitForExistence(timeout: 10)); key.tap(); key.typeText(fixture.token)
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
      let connect = app.buttons["connect-client"]
      if !connect.isHittable { app.swipeUp() }
      XCTAssertTrue(connect.isHittable); connect.tap()
      XCTAssertTrue(key.waitForNonExistence(timeout: 60), "The original Windows HTTPS identity must authenticate")
    }
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    dismissSystemPasswordOffer()
    openDesktopTestConversation(fixture)
    let actual = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expectedText)).firstMatch
    XCTAssertTrue(actual.waitForExistence(timeout: 45), "Render the actual independently read Windows conversation")
    let draft = composer.value as? String
    if existingBundle != nil {
      let menu = app.buttons["chat-menu"]
      XCTAssertTrue(menu.isHittable); menu.tap()
      let refresh = app.buttons["刷新历史"]
      XCTAssertTrue(refresh.waitForExistence(timeout: 10)); refresh.tap()
      try await Task.sleep(for: .seconds(40))
      XCTAssertFalse(app.buttons["conversation-sync-retry"].exists)
      XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
    }
    for _ in 0..<2 {
      app.terminate(); app.launchArguments = []; app.activate()
      XCTAssertTrue(actual.waitForExistence(timeout: 30))
      try await Task.sleep(for: .seconds(existingBundle == nil ? 6 : 40))
      XCTAssertFalse(app.buttons["conversation-sync-retry"].exists)
      XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
      XCTAssertEqual(composer.value as? String, draft)
    }
    shot("windows-existing-conversation-read-only-recovered")
    print("WINDOWS_READ_ONLY_RECOVERY_PASSED")
  }

  @MainActor func testStoppedOriginalDesktopReadOnlyDEV() throws {
    XCTAssertEqual(ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"], "com.easyai.agenthub.thermal.acceptance")
    guard ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"] == "com.easyai.agenthub.thermal.acceptance" else { return }
    let input = try XCTUnwrap(ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    app.launchArguments = []
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture)
    openHistoryTestConversation(fixture)
    let resume = app.buttons["resume-task"]
    XCTAssertTrue(resume.waitForExistence(timeout: 45), app.debugDescription)
    XCTAssertTrue(resume.isEnabled)
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    XCTAssertFalse(app.descendants(matching: .any)["runtime-progress"].firstMatch.exists)
    shot("original-desktop-stopped-without-running-spinner")
    // Read-only revisit verifies both the event snapshot and latest-history path.
    openHistoryTestConversation(fixture)
    XCTAssertTrue(resume.waitForExistence(timeout: 20))
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    XCTAssertFalse(app.descendants(matching: .any)["runtime-progress"].firstMatch.exists)
    shot("original-desktop-stopped-after-revisit")
  }


  @MainActor private func launchThermalAcceptance() {
    addUIInterruptionMonitor(withDescription: "Thermal acceptance own App network permission") { alert in
      let title = alert.label
      guard title.contains("Agent Hub"), title.contains("无线数据") || title.contains("本地网络") || title.localizedCaseInsensitiveContains("network") else { return false }
      let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "仅无线局域网", "允许", "Allow", "WLAN & Cellular", "Wi-Fi & Mobile Data"])).firstMatch
      guard allow.exists else { return false }
      allow.tap(); return true
    }
    if ProcessInfo.processInfo.environment["THERMAL_EXTERNAL_LAUNCH"] == "1" {
      print("THERMAL_LAUNCH_REQUEST", Date().timeIntervalSince1970)
      Thread.sleep(forTimeInterval: 3)
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        self.app.state == .runningForeground || self.app.state == .runningBackground
      }, object: app)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
      app.activate()
    } else { app.launchChineseTest() }
  }

  @MainActor func testThermalNetworkAuthorizationDEV() {
    XCTAssertEqual(ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"], "com.easyai.agenthub.thermal.acceptance")
    guard ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"] == "com.easyai.agenthub.thermal.acceptance" else { return }
    prepareOwnAcceptanceNetworkPermission()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 15))
  }

  @MainActor func testThermalStoppedOriginalDesktopReadOnlyDEV() throws {
    XCTAssertEqual(ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"], "com.easyai.agenthub.thermal.acceptance")
    guard ProcessInfo.processInfo.environment["HOST_KEY_DEV_APP_BUNDLE_ID"] == "com.easyai.agenthub.thermal.acceptance" else { return }
    let input = try XCTUnwrap(ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    #if !targetEnvironment(simulator)
    // Open only this independent bundle's Settings. iOS 27 exposes these
    // choices as cells/buttons; a StaticText can have an invalid AX hit point.
    app.launchArguments = ["-ui-testing", "-voice-open-settings"]
    app.launchChineseTest()
    let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
    XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 15))
    let wireless = settings.buttons["com.easyai.agenthub.thermal.acceptance.wireless"]
    XCTAssertTrue(wireless.waitForExistence(timeout: 10)); wireless.tap()
    XCTAssertTrue(settings.navigationBars["无线数据"].waitForExistence(timeout: 10))
    let wifi = settings.cells["1"]
    XCTAssertTrue(wifi.waitForExistence(timeout: 10))
    XCTAssertTrue(["无线局域网", "WLAN", "Wi-Fi"].contains(wifi.label))
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: wifi)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    wifi.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(wifi.isSelected)
    app.terminate()
    #endif
    app.launchArguments = []
    launchThermalAcceptance()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture)
    openHistoryTestConversation(fixture)
    let resume = app.buttons["resume-task"]
    XCTAssertTrue(resume.waitForExistence(timeout: 45), app.debugDescription)
    XCTAssertTrue(resume.isEnabled)
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    XCTAssertFalse(app.descendants(matching: .any)["runtime-progress"].firstMatch.exists)
    shot("original-desktop-stopped-without-running-spinner")
    // Read-only revisit verifies both the event snapshot and latest-history path.
    openHistoryTestConversation(fixture)
    XCTAssertTrue(resume.waitForExistence(timeout: 20))
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    XCTAssertFalse(app.descendants(matching: .any)["runtime-progress"].firstMatch.exists)
    shot("original-desktop-stopped-after-revisit")
  }

  @MainActor func testThermalRealHistorySidebarReopenDEV() throws {
    guard let input = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires a private read-only history target")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    app.launchArguments = ["-history-scroll-probe"]
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_HOST"] = fixture.hostId
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_THREAD"] = fixture.threadId
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture); openHistoryTestConversation(fixture)
    let client = app.buttons["client-switch"].label
    for iteration in 0..<3 {
      if iteration == 0 { swipeSidebar(from: 0.02, to: 0.85) }
      else { app.buttons["open-sidebar"].tap() }
      XCTAssertTrue(app.scrollViews["sidebar-list"].waitForExistence(timeout: 10))
      XCTAssertTrue(app.buttons["sidebar-client-switch"].isHittable)
      XCTAssertTrue(app.buttons["sidebar-search-toggle"].isHittable)
      app.buttons["sidebar-search-toggle"].tap()
      XCTAssertTrue(app.textFields["sidebar-search"].waitForExistence(timeout: 5))
      app.buttons["sidebar-search-toggle"].tap()
      if iteration == 0 { swipeSidebar(from: 0.97, to: 0.3) }
      else { app.buttons["close-sidebar"].tap() }
      XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 5))
      XCTAssertEqual(app.buttons["client-switch"].label, client)
      XCTAssertEqual(liveHistoryCounts()?["target"], 1)
    }
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
    shot("history-sidebar-reopened-current-target")
  }

  @MainActor func testThermalSustainedRepliesDEV() throws {
    guard let input = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires a dedicated real desktop model target")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    XCTAssertNotEqual(fixture.historyThreadArchived, true)
    app.launchArguments = ["-thermal-performance-probe"]
    launchThermalAcceptance()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture)
    let create = app.buttons["new-chat"]
    let canCreate = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: create)
    XCTAssertEqual(XCTWaiter.wait(for: [canCreate], timeout: 60), .completed)
    create.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let projectless = app.buttons["choose-projectless"]
    XCTAssertTrue(projectless.waitForExistence(timeout: 15)); projectless.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 15))
    let originalDraft = composer.value as? String ?? ""
    XCTAssertTrue(originalDraft.isEmpty || originalDraft == composer.placeholderValue)
    guard originalDraft.isEmpty || originalDraft == composer.placeholderValue else { return }
    XCTAssertFalse(app.buttons["remove-attachment"].exists)
    let generation = app.buttons["generation-settings"]
    XCTAssertTrue(generation.waitForExistence(timeout: 30))
    if !generation.label.contains("极高") {
      generation.tap()
      let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
      XCTAssertTrue(effort.waitForExistence(timeout: 15)); effort.tap()
      app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
    }
    XCTAssertTrue(generation.label.contains("极高"))
    let start = "THERMAL_START_" + fixture.nonce, end = "THERMAL_DONE_" + fixture.nonce
    focusLiveComposer()
    composer.typeText("专用手机性能验收，不读取或修改文件，不使用工具。先单独输出 \(start)。随后连续完整生成从0001到0200的200行编号文字，每行原样写出编号以及：正在核对手机持续回复的性能与能耗。必须逐行输出，不省略、不使用代码块、不提前结束。最后另起一行原样输出 \(end)。")
    print("THERMAL_MEASUREMENT_BEGIN", Date().timeIntervalSince1970)
    sendLiveComposer()
    let first = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", start)).firstMatch
    XCTAssertTrue(first.waitForExistence(timeout: 240))
    print("THERMAL_STREAM_FIRST", Date().timeIntervalSince1970)
    let final = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", end)).firstMatch
    XCTAssertTrue(final.waitForExistence(timeout: 300))
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 45))
    print("THERMAL_MEASUREMENT_END", Date().timeIntervalSince1970)
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
  }

  /// Actual navigation and original desktop replies. Remote existing chats are
  /// opened through the App, never exported by the performance driver.
  @MainActor func testThermalMixedInteractionDEV() async throws {
    executionTimeAllowance = 1800
    guard let input = ProcessInfo.processInfo.environment["THERMAL_MIXED_CLIENTS_JSON"] else {
      throw XCTSkip("Requires explicitly selected private client targets")
    }
    let clients = try JSONDecoder().decode([LiveFixture].self, from: Data(input.utf8))
    XCTAssertEqual(clients.count, 3)
    guard clients.count == 3 else { return }
    let local = clients[0]
    XCTAssertEqual(Set(clients.map(\.hostId)).count, 3)
    app.launchArguments = ["-history-scroll-probe", "-thermal-performance-probe", "-thermal-thread-identity-probe"]
    if ProcessInfo.processInfo.environment["THERMAL_NATIVE_ONLY"] == "1" {
      app.launchArguments.append("-history-work-probe")
    }
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_HOST"] = local.hostId
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_THREAD"] = local.threadId
    launchThermalAcceptance()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    for client in clients { prepareHistoryConnection(client) }
    prepareHistoryConnection(local); openHistoryTestConversation(local)

    var step = 0
    var recording = false
    func record(_ action: String, _ client: Int, _ started: Double) {
      guard recording else { return }
      step += 1
      let value: [String: Any] = ["sequence": step, "action": action, "client": client,
        "startUTC": started, "endUTC": Date().timeIntervalSince1970,
        "nativeHistory": liveHistoryCounts() ?? [:]]
      let data = try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
      print("THERMAL_MIXED_STEP " + String(decoding: data, as: UTF8.self))
    }
    func scroll(_ pairs: Int = 1) {
      let history = app.scrollViews["message-list"]
      XCTAssertTrue(history.waitForExistence(timeout: 30))
      let top = history.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.38))
      let bottom = history.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.74))
      for _ in 0..<pairs {
        top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
        bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
      }
    }
    func markLeft(_ marker: String) {
      print("THERMAL_MIXED_LEFT \(marker) \(Date().timeIntervalSince1970)")
    }
    func newChat(leaving marker: String? = nil) {
      dismissLiveQuestionPresentation(wait: 1)
      let create = app.buttons["new-chat"]
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "isEnabled == true"), object: create)], timeout: 60), .completed)
      create.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.navigationBars["选择项目"].waitForExistence(timeout: 15))
      let projectless = app.buttons["choose-projectless"]
      var previousChoice = CGRect.null
      let choiceReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard projectless.exists, projectless.isHittable else { return false }
        let frame = projectless.frame
        defer { previousChoice = frame }
        return !frame.isEmpty && !frame.isNull && frame == previousChoice
      }, object: projectless)
      XCTAssertEqual(XCTWaiter.wait(for: [choiceReady], timeout: 15), .completed)
      projectless.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let draftSelected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        !self.app.buttons["choose-projectless"].exists && self.liveHistoryCounts()?["host"] == 1
          && self.liveHistoryCounts()?["threadSelected"] == 0
      }, object: app)
      let selection = XCTWaiter.wait(for: [draftSelected], timeout: 20)
      if selection != .completed {
        print("THERMAL_NEW_DRAFT_SELECTION", liveHistoryCounts() ?? [:])
        shot("thermal-new-draft-selection-failure")
      }
      XCTAssertEqual(selection, .completed,
        "The original local projectless draft must replace the previous chat before any input")
      if let marker, selection == .completed { markLeft(marker) }
      XCTAssertTrue(composer.waitForExistence(timeout: 30))
      let draft = composer.value as? String ?? ""
      XCTAssertTrue(draft.isEmpty || draft == composer.placeholderValue)
      XCTAssertFalse(app.buttons["remove-attachment"].exists)
      let generation = app.buttons["generation-settings"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: generation)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 90), .completed)
      generation.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let picker = app.descendants(matching: .any)["effort-picker"].firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 15)); picker.tap()
      let choice = app.buttons["极高"].firstMatch
      XCTAssertTrue(choice.waitForExistence(timeout: 10))
      choice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "极高", "极高"), object: picker)
      XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed)
      let done = app.buttons["generation-done"]
      let doneReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: done)
      XCTAssertEqual(XCTWaiter.wait(for: [doneReady], timeout: 15), .completed)
      done.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let applied = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND label CONTAINS %@", "极高"), object: generation)
      XCTAssertEqual(XCTWaiter.wait(for: [applied], timeout: 15), .completed)
    }
    func submit(_ marker: String, existing: Bool = false) {
      guard liveHistoryCounts()?["host"] == 1,
        liveHistoryCounts()?["threadSelected"] == (existing ? 1 : 0) else {
        XCTFail("The original local draft/chat must still be selected before input"); return
      }
      let draft = composer.value as? String ?? ""
      guard draft.isEmpty || draft == composer.placeholderValue else {
        XCTFail("Never replace an existing draft"); return
      }
      focusLiveComposer()
      composer.typeText("专用手机混合交互性能验收 \(marker)。不读取或修改文件，不使用工具。先单独输出 \(marker)_START。随后逐行完整生成0001到0400共400行，每行格式严格为四位编号、一个空格、原文：核对多对话切换、阅读历史与持续回复。不得省略，不使用代码块。最后另起一行输出 \(marker)_DONE。")
      sendLiveComposer()
      // Observe the running control before text. AX can coalesce streamed
      // text until completion; desktop timestamps independently verify departure.
      XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 60),
        "The original reply must actually be running before navigation")
      print("THERMAL_MIXED_RUNNING " + marker)
      print("THERMAL_MIXED_RUNNING_AT \(marker) \(Date().timeIntervalSince1970)")
    }
    func searchSidebar(_ text: String) {
      let search = openDesktopSidebarSearch()
      search.tap()
      let current = search.value as? String ?? ""
      if !current.isEmpty && current != search.placeholderValue {
        let clear = app.buttons["清除搜索"]
        XCTAssertTrue(clear.waitForExistence(timeout: 5))
        clear.tap(); search.tap()
      }
      let empty = search.value as? String ?? ""
      guard empty.isEmpty || empty == search.placeholderValue else {
        XCTFail("Read-only search must replace the previous query"); return
      }
      search.typeText(text)
      let exact = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", text), object: search)
      XCTAssertEqual(XCTWaiter.wait(for: [exact], timeout: 10), .completed,
        "The native search field must contain the complete requested query")
      search.typeText("\n")
    }
    func resolveCreated(_ marker: String) throws -> LiveFixture {
      // Read the selected identity without a sidebar round trip while replying.
      // This separate opt-in value never enters numeric history telemetry.
      let identity = app.staticTexts["thermal-selected-thread"]
      XCTAssertTrue(identity.waitForExistence(timeout: 10))
      let id = try XCTUnwrap(identity.value as? String)
      XCTAssertNotNil(UUID(uuidString: id))
      var value = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"]!.utf8)) as? [String: Any])
      value["threadId"] = id; value["threadTitle"] = marker; value["historyThreadArchived"] = false
      print("THERMAL_MIXED_CREATED \(marker) \(id)")
      return try JSONDecoder().decode(LiveFixture.self, from: JSONSerialization.data(withJSONObject: value))
    }
    func openModel(_ fixture: LiveFixture) {
      searchSidebar(fixture.threadTitle ?? "")
      let row = app.buttons["recent-thread-" + fixture.threadId]
      XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap()
      XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
      XCTAssertEqual(liveHistoryCounts()?["host"], 1)
      XCTAssertEqual(liveHistoryCounts()?["threadSelected"], 1)
    }
    func readRemote(_ index: Int, _ count: Int = 2) {
      var started = Date().timeIntervalSince1970
      dismissLiveQuestionPresentation(wait: 1); prepareHistoryConnection(clients[index], allowPairing: false)
      record("switch-client", index, started)
      for thread in (clients[index].switchThreads ?? []).prefix(count) {
        started = Date().timeIntervalSince1970
        searchSidebar(thread.title)
        let rows = app.buttons.matching(NSPredicate(format: "identifier IN %@",
          ["recent-thread-" + thread.id, "pinned-thread-" + thread.id, "thread-" + thread.id]))
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 30))
        for _ in 0..<8 {
          if rows.allElementsBoundByIndex.contains(where: { $0.isHittable }) { break }
          app.scrollViews["sidebar-list"].swipeDown()
        }
        let row = rows.allElementsBoundByIndex.first(where: { $0.isHittable })
        XCTAssertNotNil(row, "Open the exact thread wherever this client's directory places it")
        row?.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.scrollViews["message-list"].waitForExistence(timeout: 30))
        dismissLiveQuestionPresentation(wait: 1)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
          (self.liveHistoryCounts()?["messages"] ?? 0) > 0
        }, object: app)], timeout: 45), .completed)
        XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
        scroll(); record("read-existing-chat", index, started)
      }
    }
    let markerA = "THERMAL_MIXED_A_" + local.nonce
    let markerB = "THERMAL_MIXED_B_" + local.nonce
    let followup = "THERMAL_MIXED_F_" + local.nonce
    // Validate all native row placements before any model input. The same
    // already-configured targets are then revisited during the measured flow.
    readRemote(1); readRemote(2)
    if ProcessInfo.processInfo.environment["THERMAL_NAVIGATION_ONLY"] == "1" {
      readRemote(1); readRemote(2)
      print("THERMAL_NAVIGATION_ONLY_PASS 8")
      return
    }
    prepareHistoryConnection(local); openHistoryTestConversation(local)
    try await Task.sleep(for: .seconds(15))
    if ProcessInfo.processInfo.environment["THERMAL_IDLE_BASELINE"] == "1" {
      // Keep the real network connection and history on screen. A separate
      // quiet window distinguishes ongoing app work from gesture/model work.
      let deadline = Date().addingTimeInterval(300)
      while ProcessInfo.processInfo.thermalState != .nominal && Date() < deadline {
        try await Task.sleep(for: .seconds(5))
      }
      print("THERMAL_IDLE_READY", ProcessInfo.processInfo.thermalState.rawValue)
      print("THERMAL_IDLE_BEGIN", Date().timeIntervalSince1970)
      try await Task.sleep(for: .seconds(60))
      print("THERMAL_IDLE_END", Date().timeIntervalSince1970)
    }
    print("THERMAL_MIXED_PREFLIGHT_VERIFIED 3")
    recording = true
    print("THERMAL_MEASUREMENT_BEGIN", Date().timeIntervalSince1970)
    var started = Date().timeIntervalSince1970
    newChat(); submit(markerA)
    let chatA = try resolveCreated(markerA)
    record("new-chat-and-reply-running", 0, started)
    started = Date().timeIntervalSince1970
    openHistoryTestConversation(local); markLeft(markerA); scroll(2); record("read-long-history", 0, started)
    readRemote(1); readRemote(2)
    started = Date().timeIntervalSince1970
    prepareHistoryConnection(local, allowPairing: false); record("switch-client", 0, started)
    started = Date().timeIntervalSince1970
    openModel(chatA)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", markerA + "_DONE")).firstMatch.waitForExistence(timeout: 300))
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 45))
    submit(followup, existing: true); record("reopen-chat-and-followup-running", 0, started)
    started = Date().timeIntervalSince1970
    newChat(leaving: followup); submit(markerB)
    let chatB = try resolveCreated(markerB)
    record("new-chat-and-reply-running", 0, started)
    started = Date().timeIntervalSince1970
    openHistoryTestConversation(local); markLeft(markerB); scroll(2); record("read-long-history", 0, started)
    readRemote(1, 1)
    started = Date().timeIntervalSince1970
    prepareHistoryConnection(local, allowPairing: false); record("switch-client", 0, started)
    for (chat, marker) in [(chatA, followup), (chatB, markerB)] {
      started = Date().timeIntervalSince1970
      openModel(chat)
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", marker + "_DONE")).firstMatch.waitForExistence(timeout: 300))
      XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 45))
      scroll(); record("reopen-completed-reply", 0, started)
    }
    print("THERMAL_MEASUREMENT_END", Date().timeIntervalSince1970)
    print("THERMAL_SCROLL_METRICS " + app.staticTexts["history-scroll-evidence"].label)
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
    shot("thermal-mixed-real-clients-completed")
  }

  /// Dedicated original chat: read its previous long replies during a new reply,
  /// then leave for a genuinely empty draft. No business chat or replay is used.
  @MainActor func testThermalStreamingHistorySwitchDEV() throws {
    continueAfterFailure = false
    executionTimeAllowance = 900
    guard let encoded = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires the original dedicated performance chat")
    }
    let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    let host = try XCTUnwrap(fixture["hostId"] as? String)
    let thread = try XCTUnwrap(fixture["threadId"] as? String)
    let title = try XCTUnwrap(fixture["threadTitle"] as? String)
    let nonce = try XCTUnwrap(fixture["nonce"] as? String)
    let live = XCUIApplication(bundleIdentifier: "com.easyai.agenthub.thermal.acceptance")
    live.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
      "-history-scroll-probe", "-history-work-probe", "-thermal-performance-probe"]
    live.launchEnvironment["HISTORY_SCROLL_EXPECTED_HOST"] = host
    live.launchEnvironment["HISTORY_SCROLL_EXPECTED_THREAD"] = thread
    if ProcessInfo.processInfo.environment["THERMAL_EXTERNAL_LAUNCH"] == "1" {
      print("THERMAL_LAUNCH_REQUEST", Date().timeIntervalSince1970)
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        live.state == .runningForeground || live.state == .runningBackground
      }, object: live)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
      live.activate()
    } else { live.launch() }
    func counts() -> [String: Int]? {
      let element = live.staticTexts["history-scroll-evidence"]
      guard element.exists, let text = element.value as? String else { return nil }
      return (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Int]
    }
    func selected(_ condition: @escaping ([String: Int]) -> Bool, timeout: TimeInterval = 30) {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        counts().map(condition) ?? false
      }, object: live)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: timeout), .completed)
    }
    func openOriginal() {
      if counts()?["target"] == 1 { return }
      if !live.buttons["close-sidebar"].exists {
        let sidebar = live.buttons["open-sidebar"]
        XCTAssertTrue(sidebar.waitForExistence(timeout: 20))
        sidebar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      }
      XCTAssertTrue(live.buttons["close-sidebar"].waitForExistence(timeout: 10))
      let search = live.textFields["sidebar-search"]
      if !search.exists { live.buttons["sidebar-search-toggle"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      XCTAssertTrue(search.waitForExistence(timeout: 10))
      if live.buttons["清除搜索"].exists { live.buttons["清除搜索"].tap() }
      search.tap(); search.typeText(title); search.typeText("\n")
      let row = live.buttons["recent-thread-" + thread]
      XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
      XCTAssertTrue(live.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
      selected({ $0["target"] == 1 && $0["host"] == 1 })
    }
    func scroll(_ pairs: Int) {
      let history = live.scrollViews["message-list"]
      XCTAssertTrue(history.waitForExistence(timeout: 15))
      let top = history.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.38))
      let bottom = history.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.74))
      for _ in 0..<pairs {
        top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
        bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
      }
    }
    XCTAssertTrue(live.buttons["client-switch"].waitForExistence(timeout: 20))
    live.buttons["client-switch"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(live.navigationBars["客户端"].waitForExistence(timeout: 15))
    let client = live.buttons["client-" + host]
    XCTAssertTrue(client.waitForExistence(timeout: 15)); client.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(live.navigationBars["客户端"].waitForNonExistence(timeout: 15))
    openOriginal()
    selected({ $0["target"] == 1 && ($0["messages"] ?? 0) >= 4 })
    XCTAssertTrue(live.buttons["generation-settings"].label.contains("极高"))
    let input = live.descendants(matching: .any).matching(identifier: "composer-input").firstMatch
    XCTAssertTrue(input.waitForExistence(timeout: 15))
    let previous = input.value as? String ?? ""
    guard previous.isEmpty || previous == input.placeholderValue else {
      XCTFail("Preserve an existing draft; never replace it"); return
    }
    XCTAssertFalse(live.buttons["remove-attachment"].exists)
    let marker = "THERMAL_FOCUS_" + nonce
    input.tap()
    input.typeText("专用手机历史阅读性能验收 \(marker)。不读取或修改文件，不使用工具。先单独输出 \(marker)_START。随后逐行完整生成0001到0600共600行，每行格式严格为四位编号、一个空格、原文：核对持续回复期间翻看长历史与切换对话。不得省略，不使用代码块。最后另起一行输出 \(marker)_DONE。")
    if live.buttons["composer-dismiss-keyboard"].exists { live.buttons["composer-dismiss-keyboard"].tap() }
    print("THERMAL_TARGET_VERIFIED 1")
    print("THERMAL_MEASUREMENT_BEGIN", Date().timeIntervalSince1970)
    let send = live.buttons["send-message"]
    XCTAssertTrue(send.isEnabled); send.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(live.buttons["stop-turn"].waitForExistence(timeout: 60))
    print("THERMAL_FOCUS_RUNNING", Date().timeIntervalSince1970)
    print("THERMAL_FOCUS_HISTORY_BEGIN", Date().timeIntervalSince1970)
    scroll(2)
    XCTAssertTrue(live.buttons["stop-turn"].exists, "The old long replies must be read while the new original reply runs")
    print("THERMAL_FOCUS_HISTORY_END", Date().timeIntervalSince1970)
    let create = live.buttons["new-chat"]
    XCTAssertTrue(create.isEnabled); create.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let projectless = live.buttons["choose-projectless"]
    XCTAssertTrue(projectless.waitForExistence(timeout: 15))
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: projectless)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
    projectless.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    selected({ $0["host"] == 1 && $0["threadSelected"] == 0 })
    print("THERMAL_FOCUS_LEFT", Date().timeIntervalSince1970)
    openOriginal()
    let done = live.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", marker + "_DONE")).firstMatch
    XCTAssertTrue(done.waitForExistence(timeout: 300))
    XCTAssertTrue(live.buttons["stop-turn"].waitForNonExistence(timeout: 45))
    print("THERMAL_FOCUS_COMPLETED", Date().timeIntervalSince1970)
    scroll(10)
    print("THERMAL_MEASUREMENT_END", Date().timeIntervalSince1970)
    print("THERMAL_SCROLL_METRICS " + live.staticTexts["history-scroll-evidence"].label)
    XCTAssertFalse(live.staticTexts["conversation-sync-failure"].exists)
  }

  @MainActor func testThermalRealHistoryComparisonDEV() throws {
    guard let input = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires a private read-only real history target")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    app.launchArguments = ["-history-scroll-probe", "-thermal-performance-probe"]
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_HOST"] = fixture.hostId
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_THREAD"] = fixture.threadId
    launchThermalAcceptance()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture)
    openHistoryTestConversation(fixture)
    XCTAssertTrue(app.scrollViews["message-list"].waitForExistence(timeout: 30))
    // Resolve the actual history viewport. A system banner can make the whole
    // Application AX element look interrupted while this scroll view is clear.
    let history = app.scrollViews["message-list"]
    let top = history.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.38))
    let bottom = history.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.74))
    top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
    bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    print("THERMAL_PROFILE_REQUEST", Date().timeIntervalSince1970)
    Thread.sleep(forTimeInterval: 15)
    XCTAssertEqual(liveHistoryCounts()?["target"], 1, "Measure only the exact requested history")
    print("THERMAL_TARGET_VERIFIED 1")
    print("THERMAL_MEASUREMENT_BEGIN", Date().timeIntervalSince1970)
    for _ in 0..<12 {
      top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
      bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    }
    print("THERMAL_MEASUREMENT_END", Date().timeIntervalSince1970)
    let evidence = app.staticTexts["history-scroll-evidence"]
    XCTAssertTrue(evidence.waitForExistence(timeout: 5))
    print("THERMAL_SCROLL_METRICS " + evidence.label)
    let metrics = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(evidence.label.utf8)) as? [String: Double])
    XCTAssertGreaterThanOrEqual(metrics["gestures"] ?? 0, 24)
    XCTAssertGreaterThan(metrics["movingFrames"] ?? 0, 400)
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
  }

  @MainActor func testHistoryContinuousScrollOriginalDesktopDEV() throws {
    guard let input = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires the private read-only original-desktop history configuration")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    let monitor = addUIInterruptionMonitor(withDescription: "Isolated history DEV network permission") { alert in
      for label in ["无线局域网与蜂窝网络", "允许"] where alert.buttons[label].exists {
        alert.buttons[label].tap(); return true
      }
      return false
    }
    defer { removeUIInterruptionMonitor(monitor) }
    app.launchArguments = ["-history-scroll-probe"]
    if ProcessInfo.processInfo.environment["HISTORY_SCROLL_WORK_PROBE"] == "1" {
      app.launchArguments.append("-history-work-probe")
    }
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_HOST"] = fixture.hostId
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_THREAD"] = fixture.threadId
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture)
    openHistoryTestConversation(fixture)
    XCTAssertTrue(app.scrollViews["message-list"].waitForExistence(timeout: 30))
    let screen = app.frame
    let top = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.55, dy: screen.height * 0.25))
    let bottom = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.55, dy: screen.height * 0.70))
    // Keep the same eight actual gestures as the synthetic history regression.
    top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
    bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    let options = XCTMeasureOptions(); options.iterationCount = 3
    measure(metrics: [XCTOSSignpostMetric.scrollDecelerationMetric], options: options) {
      top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
      bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    }
    let evidence = app.staticTexts["history-scroll-evidence"]
    XCTAssertTrue(evidence.waitForExistence(timeout: 5))
    print("HISTORY_SCROLL_LIVE_METRICS " + evidence.label)
    let metrics = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(evidence.label.utf8)) as? [String: Double])
    XCTAssertGreaterThanOrEqual(metrics["gestures"] ?? 0, 8)
    XCTAssertGreaterThan(metrics["movingFrames"] ?? 0, 250)
    XCTAssertLessThan(metrics["p95FrameMS"] ?? 1000, 25)
    XCTAssertLessThan(metrics["maxFrameMS"] ?? 1000, 50)
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
    XCTAssertTrue(app.buttons["open-sidebar"].isHittable)
    shot("history-scroll-original-desktop")
  }

  /// Keep paging independent so a frame failure cannot prevent the real
  /// remote-prefix regression from executing in the same acceptance run.
  @MainActor func testHistoryRemotePrependOriginalDesktopDEV() throws {
    guard let input = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires the private read-only original-desktop history configuration")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    app.launchArguments = ["-history-scroll-probe", "-history-position-probe"]
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_HOST"] = fixture.hostId
    app.launchEnvironment["HISTORY_SCROLL_EXPECTED_THREAD"] = fixture.threadId
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    prepareHistoryConnection(fixture)
    openHistoryTestConversation(fixture)
    XCTAssertTrue(app.scrollViews["message-list"].waitForExistence(timeout: 30))
    try verifyLiveHistoryPrependPosition()
  }

  /// Ordinary installed application only: no test-mode reset, credential replacement,
  /// message submission or synthetic history. Also works against a Release build.
  @MainActor func testHistoryInstalledAppReadOnlyDEV() throws {
    guard let input = ProcessInfo.processInfo.environment["HISTORY_SCROLL_LIVE_JSON"] else {
      throw XCTSkip("Requires the private existing-connection history configuration")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(input.utf8))
    if let installedBundle = ProcessInfo.processInfo.environment["HISTORY_SCROLL_INSTALLED_BUNDLE"] {
      XCTAssertEqual(installedBundle, "com.easyai.agenthub", "Only the explicitly selected existing formal app is supported")
      app = XCUIApplication(bundleIdentifier: installedBundle)
    }
    app.launchArguments = []
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    switchClient(fixture.hostId)
    openHistoryTestConversation(fixture)
    XCTAssertTrue(app.scrollViews["message-list"].waitForExistence(timeout: 30))
    let screen = app.frame
    let top = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.55, dy: screen.height * 0.25))
    let bottom = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.55, dy: screen.height * 0.70))
    let options = XCTMeasureOptions(); options.iterationCount = 3
    measure(metrics: [XCTOSSignpostMetric.scrollDecelerationMetric], options: options) {
      top.press(forDuration: 0.05, thenDragTo: bottom, withVelocity: .fast, thenHoldForDuration: 0)
      bottom.press(forDuration: 0.05, thenDragTo: top, withVelocity: .fast, thenHoldForDuration: 0)
    }
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
    try verifyLiveHistoryPrependPosition(requireRemotePage: false)
    shot("history-installed-read-only")
  }

  @MainActor private func liveHistoryCounts() -> [String: Int]? {
    let evidence = app.staticTexts["history-scroll-evidence"]
    guard evidence.exists, let value = evidence.value as? String,
      let data = value.data(using: .utf8) else { return nil }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Int]
  }

  @MainActor private func openHistoryTestConversation(_ fixture: LiveFixture) {
    // Reopening the app can already restore this exact Host/thread. Check the
    // native store's opt-in boolean without exposing identities in evidence.
    if liveHistoryCounts()?["target"] == 1 { return }
    dismissLiveQuestionPresentation(wait: 3)
    guard fixture.historyThreadArchived == true else {
      openDesktopTestConversation(fixture); return
    }
    let menu = app.buttons["chat-menu"]
    XCTAssertTrue(menu.waitForExistence(timeout: 20))
    let archived = app.buttons["archived-chats"]
    // Native navigation and its AX hierarchy can settle at different times.
    // Accept only the archive list or the independently checked exact target.
    func navigationExpectation() -> XCTNSPredicateExpectation {
      XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        self.app.navigationBars["已归档对话"].exists || self.liveHistoryCounts()?["target"] == 1
      }, object: app)
    }
    var navigationCompleted = false
    for attempt in 0..<3 {
      if app.navigationBars["已归档对话"].exists || liveHistoryCounts()?["target"] == 1 {
        navigationCompleted = true; break
      }
      if !archived.exists || attempt > 0 {
        menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(archived.waitForExistence(timeout: 10))
      }
      var previous = CGRect.null
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard archived.exists, archived.isEnabled else { return false }
        let frame = archived.frame
        defer { previous = frame }
        return !frame.isNull && !frame.isEmpty && frame.intersects(self.app.frame) && frame == previous
      }, object: archived)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
      archived.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      // This is read-only presentation navigation. Retry only while neither
      // the native archive list nor the exact requested target has appeared.
      if XCTWaiter.wait(for: [navigationExpectation()], timeout: 5) == .completed {
        navigationCompleted = true; break
      }
      print("THERMAL_ARCHIVE_NAVIGATION_RETRY", attempt + 1)
    }
    XCTAssertTrue(navigationCompleted, "The archive list or exact history must actually open")
    if liveHistoryCounts()?["target"] == 1 { return }
    let row = app.buttons["archived-chat-" + fixture.threadId]
    for _ in 0..<80 {
      if row.exists && row.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(row.isHittable, "The exact existing archived history must be opened read-only")
    row.tap()
    if liveHistoryCounts() != nil {
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        self.liveHistoryCounts()?["target"] == 1
      }, object: app)
      XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed,
        "The exact requested Host/thread must be selected")
    }
  }

  @MainActor private func prepareHistoryConnection(_ fixture: LiveFixture, allowPairing: Bool = true) {
    // The regression targets an existing connection and its real history.
    // Pair only if this independent installation has no such saved computer.
    dismissLiveQuestionPresentation(wait: 3)
    let picker = app.navigationBars["客户端"]
    if !picker.exists {
      let close = app.buttons["close-sidebar"]
      if close.exists {
        close.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(close.waitForNonExistence(timeout: 10))
      }
      let control = app.buttons["client-switch"]
      var previous = CGRect.null
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard control.exists, control.isEnabled else { return false }
        let frame = control.frame
        defer { previous = frame }
        return !frame.isEmpty && !frame.isNull && frame == previous
      }, object: control)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
      control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(picker.waitForExistence(timeout: 15))
    }
    let existing = app.buttons["client-" + fixture.hostId]
    if existing.waitForExistence(timeout: 10) {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: existing)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
      existing.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(picker.waitForNonExistence(timeout: 15))
      return
    }
    guard allowPairing else { XCTFail("The exact preflighted client must already exist"); return }
    picker.buttons["完成"].tap()
    connectLive(fixture, supportsCreation: false)
  }

  @MainActor private func verifyLiveHistoryPrependPosition(requireRemotePage: Bool = true) throws {
    var cachedPages = 0, remotePages = 0
    var remoteMessagesAdded = 0
    var deltas: [Double] = []
    if requireRemotePage {
      let sourceReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        (self.liveHistoryCounts()?["messages"] ?? 0) > 0
      }, object: app)
      XCTAssertEqual(XCTWaiter.wait(for: [sourceReady], timeout: 45), .completed,
        "The existing real history must finish its initial application before paging")
    }
    // Each cached expansion must increase the window. Bound by the actual
    // available prefix rather than assuming a fresh 100-message cache.
    let maximumPages = requireRemotePage ? max(1, liveHistoryCounts()?["messages"] ?? 0) + 1 : 1
    for _ in 0..<maximumPages {
      let id = app.buttons["history-local-more"].exists ? "history-local-more" : "history-more"
      let control = scrollToHistoryControl(id)
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: control)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed,
        "Wait for the existing history read to release its paging control")
      let beforeCounts = liveHistoryCounts()
      if requireRemotePage { XCTAssertNotNil(beforeCounts, "Native source counts are required for real-page proof") }
      let lowerEdge = control.frame.maxY
      let candidates = app.scrollViews["message-list"].staticTexts.allElementsBoundByIndex
      let candidate = candidates.first { element in
        let text = element.label
        guard element.isHittable, element.frame.minY > lowerEdge,
          element.frame.minY < app.frame.height * 0.75, text.count > 3 else { return false }
        return app.staticTexts.matching(NSPredicate(format: "label == %@", text)).count == 1
      }
      let anchor = try XCTUnwrap(candidate, "A unique visible native reading anchor is required")
      let label = anchor.label
      let stableAnchor = app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch
      let y = stableAnchor.frame.minY
      control.tap()
      var stableSamples = 0
      let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard stableAnchor.exists, stableAnchor.isHittable,
          self.app.buttons.matching(NSPredicate(format: "identifier IN %@ AND value == %@",
            ["history-local-more", "history-more"], "正在加载")).count == 0 else { stableSamples = 0; return false }
        if requireRemotePage {
          let field = id == "history-more" ? "messages" : "window"
          guard let before = beforeCounts?[field], let after = self.liveHistoryCounts()?[field], after > before else {
            stableSamples = 0; return false
          }
        }
        let delta = abs(stableAnchor.frame.minY - y)
        stableSamples = delta <= 3 ? stableSamples + 1 : 0
        return stableSamples >= 2
      }, object: app)
      let result = XCTWaiter.wait(for: [settled], timeout: 30)
      var diagnostic: [String: Any] = ["control": id, "anchorExists": stableAnchor.exists,
        "anchorVisible": stableAnchor.isHittable, "before": beforeCounts ?? [:], "after": liveHistoryCounts() ?? [:]]
      if stableAnchor.exists { diagnostic["anchorDeltaPt"] = Double(abs(stableAnchor.frame.minY - y)) }
      let diagnosticData = try JSONSerialization.data(withJSONObject: diagnostic, options: [.sortedKeys])
      print("HISTORY_SCROLL_LIVE_PAGE_WAIT " + String(decoding: diagnosticData, as: UTF8.self))
      XCTAssertEqual(result, .completed,
        "A real earlier page must preserve the old reading anchor within 3pt")
      let delta = abs(stableAnchor.frame.minY - y)
      XCTAssertEqual(stableAnchor.frame.minY, y, accuracy: 3)
      deltas.append(Double(delta))
      if id == "history-more" {
        remotePages += 1
        remoteMessagesAdded += (liveHistoryCounts()?["messages"] ?? 0) - (beforeCounts?["messages"] ?? 0)
      } else { cachedPages += 1 }
      if remotePages > 0 { break }
    }
    if requireRemotePage {
      XCTAssertGreaterThan(remotePages, 0, "At least one actual Host page must be read")
      XCTAssertGreaterThan(remoteMessagesAdded, 0, "Failed or duplicate reads cannot stand in for a new earlier page")
    }
    let proof: [String: Any] = ["cachedPages": cachedPages, "remotePages": remotePages,
      "remoteMessagesAdded": remoteMessagesAdded,
      "maxAnchorDeltaPt": deltas.max() ?? -1, "checkedPages": deltas.count]
    let data = try JSONSerialization.data(withJSONObject: proof, options: [.sortedKeys])
    print("HISTORY_SCROLL_LIVE_PAGING " + String(decoding: data, as: UTF8.self))
    let latest = app.buttons["跳转最新消息"]
    XCTAssertTrue(latest.isHittable); latest.tap()
    XCTAssertTrue(latest.waitForNonExistence(timeout: 10))
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("history-real-earlier-position")
  }

  func testComposerChinesePasteAndImmediateSend() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-performance-fixture"]
    app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 20))
    XCTAssertTrue(app.buttons["typing-copy-chinese"].waitForExistence(timeout: 10))
    app.buttons["typing-copy-chinese"].tap()
    let paste = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Paste' OR label == '粘贴'")).firstMatch
    composer.tap()
    if !paste.waitForExistence(timeout: 1) { composer.press(forDuration: 1.1) }
    if !paste.waitForExistence(timeout: 5) { shot("composer-paste-menu-missing"); print(app.debugDescription) }
    XCTAssertTrue(paste.exists)
    paste.tap()
    let prompt = String(repeating: "请优化输入框，保留中文、换行和标点。\n", count: 8)
    XCTAssertEqual(composer.value as? String, prompt)
    // The send reads the latest text even before the debounced save fires.
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", prompt)).firstMatch.waitForExistence(timeout: 5))
    XCTAssertFalse((composer.value as? String ?? "").contains("请优化输入框"))
    composer.tap(); typeComposerText("next-draft")
    XCUIDevice.shared.press(.home)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-performance-fixture", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertEqual(composer.value as? String, "next-draft")
    shot("composer-chinese-paste-restored")
  }

  // Live acceptance on an already connected phone. Expectations come from an
  // independent desktop catalog; no credentials or user titles live in source.
  @MainActor func testPhysicalSidebarCatalogAlignment() throws {
    struct Expected: Decodable {
      let hostId: String
      let recentThreadIds: [String]
      let projectIds: [String]
      let pinnedThreadIds: [String]
      let targetProjectId: String
      let targetThreadId: String
      let targetProjectThreadIds: [String]
    }
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["SIDEBAR_PARITY_JSON"])
    let expected = try JSONDecoder().decode(Expected.self, from: Data(encoded.utf8))
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    let originalDraft = composer.value as? String
    let selected = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-' AND isSelected == true")).firstMatch
    for _ in 0..<3 {
      if selected.exists { break }
      app.buttons["client-switch"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      if selected.waitForExistence(timeout: 3) { break }
    }
    XCTAssertTrue(selected.exists)
    let originalId = String(selected.identifier.dropFirst("client-".count))
    let originalClients = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-' AND identifier != 'client-switch'")).allElementsBoundByIndex.map(\.identifier)
    let client = app.buttons["client-" + expected.hostId]
    XCTAssertTrue(client.waitForExistence(timeout: 10)); client.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    physicalSidebar()
    let list = app.scrollViews["sidebar-list"]
    XCTAssertTrue(list.waitForExistence(timeout: 10)); list.swipeDown()
    let recent = app.buttons["sidebar-recent-toggle"]
    XCTAssertTrue(recent.waitForExistence(timeout: 30))
    let recentWasCollapsed = recent.value as? String == "已折叠"
    if recentWasCollapsed { recent.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    let first = app.buttons["recent-thread-" + (try XCTUnwrap(expected.recentThreadIds.first))]
    XCTAssertTrue(first.waitForExistence(timeout: 30))
    let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recent-thread-'"))
    XCTAssertEqual(Array(rows.allElementsBoundByIndex.map { String($0.identifier.dropFirst("recent-thread-".count)) }.prefix(5)), Array(expected.recentThreadIds.prefix(5)))
    let pins = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pinned-thread-'"))
    if !expected.pinnedThreadIds.isEmpty {
      let toggle = app.buttons["sidebar-pinned-toggle"]
      XCTAssertTrue(toggle.waitForExistence(timeout: 15))
      if toggle.value as? String == "已折叠" { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      XCTAssertEqual(pins.allElementsBoundByIndex.map { String($0.identifier.dropFirst("pinned-thread-".count)) }, expected.pinnedThreadIds)
      for id in expected.pinnedThreadIds { XCTAssertFalse(app.buttons["recent-thread-" + id].exists) }
    } else { XCTAssertFalse(app.buttons["sidebar-pinned-toggle"].exists) }
    shot("physical-sidebar-recent-and-pinned-order", fullScreen: true)
    recent.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let projects = app.buttons["sidebar-projects-toggle"]
    XCTAssertTrue(projects.exists)
    let projectsWereCollapsed = projects.value as? String == "已折叠"
    if projectsWereCollapsed { projects.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    var expanded: [String] = []
    var observed: [String] = []
    for id in expected.projectIds {
      let folder = app.buttons["project-" + id]
      for _ in 0..<18 where !folder.isHittable { list.swipeUp() }
      XCTAssertTrue(folder.isHittable)
      if folder.value as? String == "已展开" { expanded.append(id); folder.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      for row in app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'project-'" )).allElementsBoundByIndex where row.isHittable {
        let key = String(row.identifier.dropFirst("project-".count))
        if !observed.contains(key) { observed.append(key) }
      }
    }
    XCTAssertEqual(observed, expected.projectIds)
    for _ in 0..<18 where !app.buttons["project-" + expected.targetProjectId].isHittable { list.swipeDown() }
    let folder = app.buttons["project-" + expected.targetProjectId]
    XCTAssertTrue(folder.isHittable); folder.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.buttons["thread-" + expected.targetThreadId].waitForExistence(timeout: 10))
    let children = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'thread-'"))
    XCTAssertEqual(Array(children.allElementsBoundByIndex.map { String($0.identifier.dropFirst("thread-".count)) }.prefix(5)), Array(expected.targetProjectThreadIds.prefix(5)))
    shot("physical-sidebar-original-project-and-thread-order", fullScreen: true)
    folder.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    for id in expanded {
      let row = app.buttons["project-" + id]
      for _ in 0..<18 where !row.isHittable { list.swipeUp() }
      if row.isHittable { row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    }
    for _ in 0..<20 where !recent.isHittable { list.swipeDown() }
    if !recentWasCollapsed { recent.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    if projectsWereCollapsed, projects.isHittable { projects.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    app.buttons["close-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    app.terminate(); app.activate()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30)); physicalSidebar()
    XCTAssertTrue(app.buttons["sidebar-recent-toggle"].waitForExistence(timeout: 30))
    XCTAssertEqual(app.buttons["sidebar-recent-toggle"].value as? String, recentWasCollapsed ? "已折叠" : "已展开")
    shot("physical-sidebar-ordinary-restart", fullScreen: true)
    app.buttons["close-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); app.buttons["client-switch"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-' AND identifier != 'client-switch'")).allElementsBoundByIndex.map(\.identifier), originalClients)
    app.buttons["client-" + originalId].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertEqual(composer.value as? String, originalDraft)
    print("SIDEBAR_PHYSICAL_PARITY_PASS", expected.hostId, expected.projectIds.count, expected.recentThreadIds.count)
  }

  @MainActor private func physicalSidebarChatMenu() {
    let pin = physicalAction("chat-pin")
    let menu = app.buttons["chat-menu"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: menu)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
    for _ in 0..<3 {
      if pin.exists && pin.isHittable { return }
      menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      if pin.waitForExistence(timeout: 3) && pin.isHittable { return }
    }
    XCTFail("The native conversation menu did not open")
  }

  @MainActor func testPhysicalSidebarModelPinLifecycle() throws {
    let hostId = try XCTUnwrap(ProcessInfo.processInfo.environment["SIDEBAR_MODEL_HOST_ID"])
    let marker = try XCTUnwrap(ProcessInfo.processInfo.environment["SIDEBAR_MODEL_MARKER"])
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    let selected = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-' AND isSelected == true")).firstMatch
    for _ in 0..<3 {
      if selected.exists { break }
      app.buttons["client-switch"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      if selected.waitForExistence(timeout: 3) { break }
    }
    XCTAssertTrue(selected.exists)
    let originalId = String(selected.identifier.dropFirst("client-".count))
    app.buttons["client-" + hostId].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let creationReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["new-chat"])
    XCTAssertEqual(XCTWaiter.wait(for: [creationReady], timeout: 60), .completed)
    let projectless = app.buttons["choose-projectless"]
    for _ in 0..<3 {
      if projectless.exists { break }
      app.buttons["new-chat"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      if projectless.waitForExistence(timeout: 3) { break }
    }
    XCTAssertTrue(projectless.exists); projectless.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 15))
    let placeholder = composer.value as? String ?? ""
    XCTAssertTrue(placeholder.isEmpty || placeholder == composer.placeholderValue, "Do not replace an existing projectless draft")
    XCTAssertFalse(app.buttons["remove-attachment"].exists)
    let high = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "推理强度，极高"), object: app.buttons["generation-settings"])
    XCTAssertEqual(XCTWaiter.wait(for: [high], timeout: 15), .completed)
    focusLiveComposer()
    composer.typeText("手机侧栏对齐专项验收。仅原样回复 \(marker)，不要调用工具，不要读取或修改文件。")
    sendLiveComposer(); waitForDesktopAnswer(marker)
    let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 45), .completed)
    physicalSidebar()
    let recentToggle = app.buttons["sidebar-recent-toggle"]
    XCTAssertTrue(recentToggle.waitForExistence(timeout: 30))
    let wasCollapsed = recentToggle.value as? String == "已折叠"
    if wasCollapsed { recentToggle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recent-thread-'"))
    let newest = rows.firstMatch
    let selectedNew = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: newest)
    XCTAssertEqual(XCTWaiter.wait(for: [selectedNew], timeout: 30), .completed)
    let id = String(newest.identifier.dropFirst("recent-thread-".count))
    print("SIDEBAR_PHYSICAL_MODEL_THREAD", id)
    shot("physical-sidebar-new-real-model-first-in-recents", fullScreen: true)
    newest.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); physicalSidebarChatMenu()
    XCTAssertEqual(physicalAction("chat-pin").label, "置顶")
    physicalAction("chat-pin").tap()
    physicalSidebar()
    let pinToggle = app.buttons["sidebar-pinned-toggle"]
    XCTAssertTrue(pinToggle.waitForExistence(timeout: 30))
    if pinToggle.value as? String == "已折叠" { pinToggle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    let pinned = app.buttons["pinned-thread-" + id]
    XCTAssertTrue(pinned.waitForExistence(timeout: 30))
    XCTAssertFalse(app.buttons["recent-thread-" + id].exists)
    XCTAssertFalse(app.buttons["thread-" + id].exists)
    shot("physical-sidebar-native-pin-excludes-other-lists", fullScreen: true)
    pinned.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); physicalSidebarChatMenu()
    let unpin = physicalAction("chat-pin")
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND label == '取消置顶'"), object: unpin)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    unpin.tap(); physicalSidebar()
    XCTAssertTrue(app.buttons["recent-thread-" + id].waitForExistence(timeout: 30))
    XCTAssertFalse(app.buttons["pinned-thread-" + id].exists)
    if wasCollapsed { recentToggle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    shot("physical-sidebar-native-unpin-restores-recency", fullScreen: true)
    app.buttons["close-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); app.terminate(); app.activate()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30)); physicalSidebar()
    XCTAssertFalse(app.buttons["pinned-thread-" + id].exists)
    app.buttons["close-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); app.buttons["client-switch"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    app.buttons["client-" + originalId].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    print("SIDEBAR_PHYSICAL_MODEL_PIN_PASS", id, marker)
  }

  // Resume only the already verified QA thread; never repeat its model input.
  @MainActor func testPhysicalSidebarResumeUnpin() throws {
    let id = try XCTUnwrap(ProcessInfo.processInfo.environment["SIDEBAR_RESUME_THREAD_ID"])
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30))
    physicalSidebar()
    let pinToggle = app.buttons["sidebar-pinned-toggle"]
    XCTAssertTrue(pinToggle.waitForExistence(timeout: 30))
    if pinToggle.value as? String == "已折叠" { pinToggle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    let row = app.buttons["pinned-thread-" + id]
    XCTAssertTrue(row.waitForExistence(timeout: 30))
    XCTAssertTrue(row.isSelected, "Resume only the original selected QA thread")
    XCTAssertFalse(app.buttons["recent-thread-" + id].exists)
    row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    physicalSidebarChatMenu()
    let unpin = physicalAction("chat-pin")
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND label == '取消置顶'"), object: unpin)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    // UIKit menu actions use their semantic activation, not a stale popup coordinate.
    unpin.tap()
    let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["取消置顶"])
    XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed)
    physicalSidebar()
    let recent = app.buttons["sidebar-recent-toggle"]
    XCTAssertTrue(recent.waitForExistence(timeout: 30))
    if recent.value as? String == "已折叠" { recent.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    XCTAssertTrue(app.buttons["recent-thread-" + id].waitForExistence(timeout: 30))
    XCTAssertFalse(app.buttons["pinned-thread-" + id].exists)
    shot("physical-sidebar-native-unpin-restores-recency", fullScreen: true)
    app.buttons["close-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    app.terminate(); app.activate()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30)); physicalSidebar()
    XCTAssertTrue(app.buttons["recent-thread-" + id].waitForExistence(timeout: 30))
    XCTAssertFalse(app.buttons["pinned-thread-" + id].exists)
    shot("physical-sidebar-unpin-ordinary-restart", fullScreen: true)
    app.buttons["close-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    print("SIDEBAR_PHYSICAL_MODEL_PIN_RESUMED_PASS", id)
  }

  @MainActor func testRegularAppSidebarCatalogParity() throws {
    struct ExpectedProject: Decodable { let id: String; let name: String; let threadIds: [String] }
    guard let value = ProcessInfo.processInfo.environment["SIDEBAR_EXPECTATIONS"] else {
      throw XCTSkip("Provide the independently verified desktop sidebar catalog")
    }
    let expected = try JSONDecoder().decode([ExpectedProject].self, from: Data(value.utf8))
    XCTAssertGreaterThanOrEqual(expected.count, 2)
    if let fixtureValue = ProcessInfo.processInfo.environment["SIDEBAR_LIVE_FIXTURE"] {
      let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(fixtureValue.utf8))
      connectLive(fixture, supportsCreation: false)
    } else {
      app.terminate()
      app.launchArguments = []
      app.launchChineseTest()
    }
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap()
    let list = app.scrollViews["sidebar-list"]
    XCTAssertTrue(list.waitForExistence(timeout: 5))
    list.swipeDown()
    let first = app.buttons["project-" + expected[0].id]
    XCTAssertTrue(first.waitForExistence(timeout: 15))
    for project in expected.prefix(2) {
      let folder = app.buttons["project-" + project.id]
      for _ in 0..<8 where !folder.isHittable { list.swipeUp() }
      XCTAssertTrue(folder.isHittable)
      XCTAssertTrue(folder.label.contains(project.name))
      if folder.value as? String != "已展开" { folder.tap() }
      for id in project.threadIds {
        XCTAssertTrue(app.buttons["thread-" + id].waitForExistence(timeout: 5))
      }
      shot("regular-sidebar-" + project.id)
      folder.tap()
      for id in project.threadIds { XCTAssertFalse(app.buttons["thread-" + id].exists) }
    }
    let second = app.buttons["project-" + expected[1].id]
    XCTAssertLessThan(first.frame.minY, second.frame.minY)
    // Each expected chat appeared under its own folder and disappeared when
    // that folder collapsed; shared directories must not duplicate the rows.
    shot("regular-sidebar-project-order")
  }
  private func clearComposer() {
    let count = (composer.value as? String ?? "").count
    composer.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.98)).tap()
    typeComposerText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: count))
  }
  private func selectPhotoFromSystemPicker() {
    // Scope selection to the system grid; conversation image previews also have photo labels.
    let grid = app.collectionViews.firstMatch
    if grid.waitForExistence(timeout: 3) {
      let photo = grid.cells.firstMatch
      XCTAssertTrue(photo.waitForExistence(timeout: 10))
      photo.tap()
    } else {
      // The iOS 26 system picker uses a SwiftUI photo grid without collection-view cells.
      let photo = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
      if !photo.waitForExistence(timeout: 10) {
        shot("photo-picker-grid-failure")
        print("SYSTEM_PHOTO_PICKER_STATE", app.debugDescription)
      }
      XCTAssertTrue(photo.exists)
      photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    let add = app.navigationBars.buttons.matching(
      NSPredicate(format: "label BEGINSWITH 'Add' OR label CONTAINS '添加' OR label == 'Done' OR label == '完成' OR label == '选取' OR label == '选取照片'"))
      .firstMatch
    if !add.waitForExistence(timeout: 10) {
      shot("photo-picker-selection-failure")
      print("PHOTO_PICKER_SELECTION_STATE", app.debugDescription)
    }
    XCTAssertTrue(add.exists)
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: add)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    add.tap()
    XCTAssertTrue(app.buttons["remove-attachment"].firstMatch.waitForExistence(timeout: 10))
  }
  private func switchClient(_ id: String) {
    app.buttons["client-switch"].tap()
    XCTAssertTrue(app.buttons["client-" + id].waitForExistence(timeout: 3))
    app.buttons["client-" + id].tap()
  }

  private func replaceText(_ field: XCUIElement, with text: String) {
    field.tap()
    field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (field.value as? String ?? "").count))
    field.typeText(text)
  }

  @MainActor func testContextCompactionLifecycleAndClientIsolation() async throws {
    guard let result = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8800")!),
      let fixture = try? JSONDecoder().decode(LiveFixture.self, from: result.0)
    else { throw XCTSkip("Run scripts/context-compaction-ios-fixture.mjs") }
    connectLive(fixture, supportsCreation: false)
    let progress = app.descendants(matching: .any).matching(identifier: "context-compaction-fixture-compaction").firstMatch
    func expect(_ label: String) {
      let updated = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", label), object: progress)
      XCTAssertEqual(XCTWaiter.wait(for: [updated], timeout: 10), .completed)
    }
    func state(_ value: String) async throws {
      var request = URLRequest(url: URL(string: fixture.endpoint + "/state?value=" + value)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    expect("正在压缩上下文 · 可能需要几分钟")
    XCTAssertFalse(app.descendants(matching: .any)["runtime-progress"].exists)
    let tools = app.buttons["tool-group-fixture-command-a"]
    XCTAssertTrue(tools.waitForExistence(timeout: 5))
    XCTAssertTrue((tools.value as? String ?? "").contains("已折叠"))
    XCTAssertFalse(app.buttons["tool-item-fixture-command-a"].exists)
    tools.tap()
    XCTAssertTrue((tools.value as? String ?? "").contains("已展开"))
    XCTAssertTrue(app.buttons["tool-item-fixture-command-a"].exists)
    tools.tap()
    XCTAssertFalse(app.buttons["tool-item-fixture-command-a"].exists)
    XCTAssertTrue(progress.isHittable)
    shot("context-compaction-running")

    try await state("offline")
    expect("上下文压缩状态待同步")
    try await state("inProgress")
    expect("正在压缩上下文 · 可能需要几分钟")
    try await state("completed")
    expect("已压缩上下文")
    shot("context-compaction-completed")
    try await state("interrupted")
    expect("上下文压缩已停止")
    try await state("failed")
    expect("上下文压缩失败")
    try await state("unknown")
    expect("上下文压缩状态待同步")

    switchClient("demo-mac")
    XCTAssertFalse(progress.exists)
    try await state("completed")
    XCTAssertFalse(progress.exists)
    switchClient(fixture.hostId)
    expect("已压缩上下文")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    expect("已压缩上下文")
  }

  @MainActor private func clientEditFixture(_ action: String = "reset") async throws -> LiveFixture {
    guard let result = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8798/" + action)!) else {
      throw XCTSkip("Run scripts/client-edit-ios-fixture.mjs for client editing acceptance")
    }
    return try JSONDecoder().decode(LiveFixture.self, from: result.0)
  }

  @MainActor func testClientEditCancelAndNicknamePreserveSelectionAndDraft() async throws {
    let fixture = try await clientEditFixture()
    connectLive(fixture, supportsCreation: false)
    composer.tap()
    typeComposerText("client-edit-draft")
    app.buttons["client-switch"].tap()
    let edit = app.buttons["edit-client-" + fixture.hostId]
    XCTAssertTrue(edit.waitForExistence(timeout: 5))
    shot("client-list-edit-entry")
    edit.tap()
    XCTAssertTrue(app.navigationBars["编辑客户端"].waitForExistence(timeout: 5))
    XCTAssertEqual(app.textFields["client-name"].value as? String, "本机 Mac")
    XCTAssertEqual(app.textFields["client-address"].value as? String, fixture.endpoint)
    replaceText(app.textFields["client-name"], with: "Cancelled name")
    app.buttons["cancel-client-form"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].label.contains("本机 Mac"))

    edit.tap()
    replaceText(app.textFields["client-name"], with: "Edited Mac")
    _ = try await clientEditFixture("offline")
    app.buttons["save-client"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].label.contains("Edited Mac"))
    app.buttons["完成"].tap()
    XCTAssertEqual(composer.value as? String, "client-edit-draft")

    switchClient("demo-mac")
    composer.tap()
    typeComposerText("other-client-draft")
    app.buttons["client-switch"].tap()
    edit.tap()
    replaceText(app.textFields["client-name"], with: "Renamed offline Mac")
    app.buttons["save-client"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 5))
    app.buttons["完成"].tap()
    XCTAssertTrue(app.buttons["client-switch"].label.contains("我的 MacBook"))
    XCTAssertEqual(composer.value as? String, "other-client-draft")

    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertEqual(composer.value as? String, "other-client-draft")
    app.buttons["client-switch"].tap()
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].label.contains("Renamed offline Mac"))
    app.buttons["client-" + fixture.hostId].tap()
    XCTAssertEqual(composer.value as? String, "client-edit-draft")
  }

  @MainActor func testClientEditConnectionValidationAndPersistence() async throws {
    let fixture = try await clientEditFixture()
    connectLive(fixture, supportsCreation: false)
    composer.tap()
    typeComposerText("connection-edit-draft")
    app.buttons["client-switch"].tap()
    let edit = app.buttons["edit-client-" + fixture.hostId]
    edit.tap()
    replaceText(app.textFields["client-name"], with: "")
    app.buttons["save-client"].tap()
    XCTAssertTrue(app.staticTexts["client-form-error"].waitForExistence(timeout: 5))
    XCTAssertEqual(app.staticTexts["client-form-error"].label, "请输入客户端名称。")
    replaceText(app.textFields["client-name"], with: "Connected Edited Mac")
    replaceText(app.textFields["client-address"], with: fixture.secondEndpoint)
    _ = try await clientEditFixture("wrong-host")
    app.buttons["save-client"].tap()
    let mismatch = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS '电脑身份不一致'"), object: app.staticTexts["client-form-error"])
    XCTAssertEqual(XCTWaiter.wait(for: [mismatch], timeout: 10), .completed)
    app.buttons["cancel-client-form"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["client-" + fixture.secondHostId].exists)
    edit.tap()
    XCTAssertEqual(app.textFields["client-name"].value as? String, "本机 Mac")
    XCTAssertEqual(app.textFields["client-address"].value as? String, fixture.endpoint)

    replaceText(app.textFields["client-address"], with: fixture.secondEndpoint)
    replaceText(app.secureTextFields["client-token"], with: "invalid-fixture-key")
    app.buttons["save-client"].tap()
    XCTAssertTrue(app.staticTexts["client-form-error"].waitForExistence(timeout: 10))
    app.buttons["cancel-client-form"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 5))
    edit.tap()
    // Retrying with the prefilled Keychain secret proves failed saves kept it intact.
    replaceText(app.textFields["client-address"], with: fixture.secondEndpoint)
    app.buttons["save-client"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 10))

    edit.tap()
    let rotated = try await clientEditFixture("rotate-key")
    replaceText(app.textFields["client-name"], with: "Connected Edited Mac")
    replaceText(app.secureTextFields["client-token"], with: rotated.token)
    app.buttons["save-client"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 10))
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].label.contains("Connected Edited Mac"))
    shot("client-edit-saved")
    app.buttons["完成"].tap()
    XCTAssertEqual(composer.value as? String, "connection-edit-draft")

    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["CLIENT_EDIT_FIXTURE_HISTORY"].waitForExistence(timeout: 10))
    XCTAssertEqual(composer.value as? String, "connection-edit-draft")
    app.buttons["client-switch"].tap()
    edit.tap()
    XCTAssertEqual(app.textFields["client-name"].value as? String, "Connected Edited Mac")
    XCTAssertEqual(app.textFields["client-address"].value as? String, fixture.secondEndpoint)
    replaceText(app.textFields["client-address"], with: fixture.endpoint)
    app.buttons["save-client"].tap()
    XCTAssertTrue(edit.waitForExistence(timeout: 10))
  }

  private func setDemoEnabledFromSettings(_ enabled: Bool) {
    app.buttons["open-sidebar"].tap()
    app.buttons["open-settings"].tap()
    let toggle = app.switches["settings-demo-toggle"].firstMatch
    for _ in 0..<3 where !toggle.isHittable { app.swipeUp() }
    XCTAssertTrue(toggle.waitForExistence(timeout: 5))
    XCTAssertEqual(toggle.value as? String, enabled ? "0" : "1")
    // SwiftUI exposes the entire row as a switch; tap the control at the trailing edge.
    toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    XCTAssertTrue(app.navigationBars["设置"].exists)
    let changed = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "value == %@", enabled ? "1" : "0"), object: toggle)
    let result = XCTWaiter.wait(for: [changed], timeout: 5)
    if result != .completed {
      shot("settings-demo-toggle-failure")
      print("DEMO_TOGGLE_DIAGNOSTIC", app.debugDescription)
    }
    XCTAssertEqual(result, .completed)
    shot(enabled ? "settings-demo-enabled" : "settings-demo-disabled")
    app.buttons["完成"].tap()
  }

  func testCloseDemoReturnsToOnboardingAndPersists() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-action-fixture"]
    app.launchChineseTest()
    composer.tap()
    typeComposerText("demo-task-before-close")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 3))
    composer.tap()
    typeComposerText("demo-draft-to-keep")
    XCTAssertFalse(app.buttons["close-demo"].exists)
    setDemoEnabledFromSettings(false)
    XCTAssertTrue(app.buttons["onboarding-add"].waitForExistence(timeout: 5))
    XCTAssertFalse(composer.exists)
    shot("demo-closed-onboarding")

    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["onboarding-add"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["enable-demo"].exists)
    XCTAssertFalse(app.staticTexts["connection-mode"].exists)
    setDemoEnabledFromSettings(true)
    XCTAssertEqual(composer.value as? String, "demo-draft-to-keep")
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    app.buttons["client-switch"].tap()
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-demo-' ")).count, 3)
    XCTAssertFalse(app.buttons["clients-close-demo"].exists)
    XCTAssertFalse(app.buttons["体验界面"].exists)
    app.buttons["client-demo-mac"].tap()
    shot("demo-main-without-test-controls")
    setDemoEnabledFromSettings(false)
    XCTAssertTrue(app.buttons["onboarding-add"].waitForExistence(timeout: 5))
  }

  @MainActor func testCloseDemoPreservesConnectedClientAndDraft() async throws {
    let fixture = try await sidebarRefreshFixture()
    connectLive(fixture, supportsCreation: false)
    composer.tap()
    typeComposerText("connected-client-draft")
    switchClient("demo-mac")
    setDemoEnabledFromSettings(false)
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    XCTAssertEqual(composer.value as? String, "connected-client-draft")
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_HISTORY"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.buttons["close-demo"].exists)

    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertEqual(composer.value as? String, "connected-client-draft")
    app.buttons["client-switch"].tap()
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].waitForExistence(timeout: 5))
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-demo-' ")).count, 0)
    app.buttons["client-" + fixture.hostId].tap()
    setDemoEnabledFromSettings(true)
    switchClient(fixture.hostId)
    setDemoEnabledFromSettings(false)
    XCTAssertEqual(composer.value as? String, "connected-client-draft")
    XCTAssertFalse(app.buttons["close-demo"].exists)
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_HISTORY"].exists)
    shot("demo-closed-connected-client-retained")
  }

  func testUnifiedComposerStopResumeAndRestart() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-action-fixture"]
    app.launchChineseTest()
    composer.tap()
    typeComposerText("state-control-task")
    let send = app.buttons["send-message"]
    let mic = app.buttons["voice-input"]
    XCTAssertLessThan(mic.frame.maxX, send.frame.midX)
    send.tap()
    let stop = app.buttons["stop-turn"]
    XCTAssertTrue(stop.waitForExistence(timeout: 3))
    XCTAssertFalse(send.exists)
    let position = stop.frame
    composer.tap()
    typeComposerText("running-draft")
    XCTAssertTrue(send.exists)
    XCTAssertTrue(app.segmentedControls["reply-mode"].exists)
    clearComposer()
    stop.tap()
    let resume = app.buttons["resume-task"]
    XCTAssertTrue(resume.waitForExistence(timeout: 3))
    XCTAssertEqual(resume.frame.midX, position.midX, accuracy: 1)
    XCTAssertFalse(stop.exists)
    shot("composer-resume")
    resume.tap()
    XCTAssertTrue(app.staticTexts["继续完成上一个任务。"].waitForExistence(timeout: 3))
    XCTAssertTrue(stop.waitForExistence(timeout: 3))
    XCTAssertTrue(send.waitForExistence(timeout: 18))
    XCTAssertFalse(send.isEnabled)
    XCTAssertFalse(app.buttons["restart-task"].exists)
    XCTAssertEqual(send.frame.midX, position.midX, accuracy: 1)
    shot("composer-completed-disabled-send")
    send.press(forDuration: 1)
    let restart = app.buttons["重新运行上一任务"]
    XCTAssertTrue(restart.waitForExistence(timeout: 3))
    restart.tap()
    XCTAssertTrue(app.staticTexts["请重新执行上一个任务。"].waitForExistence(timeout: 3))
    XCTAssertTrue(stop.waitForExistence(timeout: 3))
    stop.tap()
    XCTAssertTrue(resume.waitForExistence(timeout: 3))
    composer.tap()
    typeComposerText("next-draft")
    XCTAssertTrue(send.exists)
    XCTAssertFalse(resume.exists)
    switchClient("demo-home")
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    XCTAssertFalse(app.buttons["resume-task"].exists)
    switchClient("demo-mac")
    XCTAssertEqual(composer.value as? String, "next-draft")
  }

  @MainActor func testPhysicalReplyWaitRecoveryDEV() {
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30))
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    XCTAssertFalse(app.descendants(matching: .any)["outbox-pending"].firstMatch.exists)
    let content = composer.value as? String ?? ""
    if content.isEmpty || content == "输入消息，长按语音输入" {
      XCTAssertFalse(app.segmentedControls["reply-mode"].exists)
    }
    shot("physical-reply-wait-cleared")
    app.buttons["chat-menu"].tap()
    let records = app.buttons["reply-delivery-records"]
    XCTAssertTrue(records.waitForExistence(timeout: 10)); records.tap()
    XCTAssertGreaterThan(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'query-detached-reply-' ")).count, 0)
    shot("physical-original-reply-preserved")
    app.buttons["完成"].tap()
  }

  @MainActor func testReplyWaitReceiptReadOnlyRecoveryDEV() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8816")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let id = try XCTUnwrap(fixture.originalReplyId)
    XCTAssertTrue(composer.waitForExistence(timeout: 20))
    let draft = composer.value as? String
    XCTAssertFalse(app.descendants(matching: .any)["outbox-pending"].firstMatch.exists)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["chat-menu"].tap(); app.buttons["reply-delivery-records"].tap()
    let query = app.buttons["query-detached-reply-" + id]
    XCTAssertTrue(query.waitForExistence(timeout: 10)); query.tap()
    let state = app.staticTexts["reply-record-state-" + id]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == '已完成'"), object: state)], timeout: 15), .completed)
    shot("real-reply-record-query-recovered")
    app.buttons["完成"].tap()
    XCTAssertEqual(composer.value as? String, draft)
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 20)); XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(app.descendants(matching: .any)["outbox-pending"].firstMatch.exists)
  }

  @MainActor func testReplyWaitRecoversOmittedReceiptsAutomatically() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8816")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let send = app.buttons["send-message"]
    let pending = app.descendants(matching: .any)["outbox-pending"].firstMatch
    func control(_ path: String) async throws {
      var request = URLRequest(url: URL(string: fixture.endpoint + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    if app.buttons["generation-settings"].exists {
      app.buttons["generation-settings"].tap()
      app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
      XCTAssertTrue(app.buttons["极高"].waitForExistence(timeout: 10)); app.buttons["极高"].tap()
      app.buttons["generation-done"].tap()
    }
    let marker = "REPLY_WAIT_" + fixture.nonce
    focusLiveComposer(); composer.typeText("只原样回复 " + marker + "_FIRST，不调用工具。")
    sendLiveComposer()
    XCTAssertTrue(pending.waitForExistence(timeout: 15))
    focusLiveComposer(); composer.typeText("只原样回复 " + marker + "_SECOND，不调用工具。")
    let second = composer.value as? String
    try await control("/wait/settled?index=0")
    try await control("/wait/confirm?index=0")
    let recoveryDeadline: TimeInterval = fixture.nonce == "fixture-stalled-history" ? 12 : 40
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: pending)], timeout: recoveryDeadline), .completed)
    XCTAssertEqual(composer.value as? String, second)
    XCTAssertTrue(send.isEnabled, "An independently confirmed receipt must release the held POST's lock")
    sendLiveComposer()
    XCTAssertTrue(pending.waitForExistence(timeout: 15))
    focusLiveComposer(); composer.typeText("NEXT_DRAFT_" + marker)
    try await control("/wait/release?index=0")
    try await Task.sleep(for: .seconds(2))
    XCTAssertTrue(pending.exists, "A late timeout must not clear the newer pending input")
    XCTAssertFalse(app.alerts["无法完成操作"].exists, "A late HTTP error cannot invalidate a confirmed receipt")
    XCTAssertEqual(composer.value as? String, "NEXT_DRAFT_" + marker)
    try await control("/wait/settled?index=1")
    try await control("/wait/confirm?index=1")
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: pending)], timeout: 40), .completed)
    XCTAssertTrue(send.isEnabled)
    try await control("/wait/release?index=1")
    if fixture.nonce.hasPrefix("fixture") {
      sendLiveComposer()
      XCTAssertTrue(pending.waitForExistence(timeout: 15))
      try await Task.sleep(for: .seconds(5))
      XCTAssertTrue(pending.exists, "Completed-looking history without an exact receipt proves nothing")
      if app.alerts["无法完成操作"].exists { app.alerts["无法完成操作"].buttons["好"].tap() }
      app.buttons["detach-reply-wait"].tap()
      XCTAssertFalse(pending.exists)
      focusLiveComposer(); composer.typeText("PRESERVED_UNKNOWN_DRAFT")
    }
    let draft = composer.value as? String
    XCUIDevice.shared.press(.home)
    try await Task.sleep(for: .seconds(2))
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 20))
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(pending.exists)
    shot("reply-receipt-auto-recovered-after-restart")
  }

  @MainActor func testReplyWaitRecoverySchedulingWithoutEvents() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:18816")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let pending = app.descendants(matching: .any)["outbox-pending"].firstMatch
    func control(_ path: String) async throws -> [String: Any] {
      var request = URLRequest(url: URL(string: fixture.endpoint + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    for (index, phase) in ["cold", "foreground", "manual"].enumerated() {
      focusLiveComposer(); composer.typeText("SCHEDULING_ORIGINAL_" + phase)
      sendLiveComposer()
      XCTAssertTrue(pending.waitForExistence(timeout: 10))
      let draft = "PRESERVED_SCHEDULING_DRAFT_" + phase
      focusLiveComposer(); composer.typeText(draft)
      if phase == "cold" || phase == "foreground" {
        XCUIDevice.shared.press(.home)
        try await Task.sleep(for: .seconds(2))
        if phase == "cold" { app.terminate() }
        _ = try await control("/wait/checkpoint?phase=" + phase)
        _ = try await control("/wait/confirm?index=" + String(index))
        if phase == "cold" {
          app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
        } else { app.activate() }
      } else {
        _ = try await control("/wait/checkpoint?phase=" + phase)
        app.buttons["query-original-reply"].tap()
        var held = false
        for _ in 0..<30 {
          let state = try await control("/wait/status")
          if (state["heldHistory"] as? Int ?? 0) > 0 { held = true; break }
          try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(held, "Manual receipt query must reach the deliberately stalled history pull")
        _ = try await control("/wait/confirm?index=" + String(index))
        if app.alerts["无法完成操作"].exists { app.alerts["无法完成操作"].buttons["好"].tap() }
      }
      XCTAssertTrue(composer.waitForExistence(timeout: 10))
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: pending)], timeout: 12), .completed)
      XCTAssertEqual(composer.value as? String, draft)
      let state = try await control("/wait/status")
      XCTAssertGreaterThan((state["boundaryQueries"] as? [String: Int])?[phase] ?? 0, 0)
      XCTAssertEqual((state["sends"] as? [[String: Any]])?.count, index + 1, "Only the three explicit original inputs may be submitted")
      _ = try await control("/wait/release-history")
      _ = try await control("/wait/release?index=" + String(index))
      if phase != "manual" {
        focusLiveComposer()
        composer.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: draft.count))
      }
    }
    shot("reply-recovery-cold-foreground-manual-without-events")
  }

  @MainActor func testReplyWaitDetachesWithoutBlockingOrReplaying() async throws {
    let (metadata, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8816")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: metadata)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let send = app.buttons["send-message"]
    let pending = app.descendants(matching: .any)["outbox-pending"].firstMatch
    func control(_ path: String) async throws -> [String: Any] {
      var request = URLRequest(url: URL(string: fixture.endpoint + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    if app.buttons["generation-settings"].exists {
      app.buttons["generation-settings"].tap()
      app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
      XCTAssertTrue(app.buttons["极高"].waitForExistence(timeout: 10)); app.buttons["极高"].tap()
      app.buttons["generation-done"].tap()
    }
    let marker = "REPLY_WAIT_" + fixture.nonce
    focusLiveComposer(); composer.typeText("只原样回复 " + marker + "_FIRST，不调用工具。")
    sendLiveComposer()
    XCTAssertTrue(pending.waitForExistence(timeout: 15))
    let detach = app.buttons["detach-reply-wait"]
    XCTAssertTrue(detach.waitForExistence(timeout: 10)); detach.tap()
    XCTAssertFalse(pending.exists)
    XCTAssertFalse(app.segmentedControls["reply-mode"].exists)
    _ = try await control("/wait/settled?index=0")
    focusLiveComposer(); composer.typeText("只原样回复 " + marker + "_SECOND，不调用工具。")
    XCTAssertTrue(send.isEnabled); sendLiveComposer()
    XCTAssertTrue(pending.waitForExistence(timeout: 15))
    focusLiveComposer(); composer.typeText("NEXT_DRAFT_" + marker)
    let status = try await control("/wait/status")
    let sends = try XCTUnwrap(status["sends"] as? [[String: Any]])
    XCTAssertEqual(sends.count, 2)
    let originalId = try XCTUnwrap(sends[0]["clientMessageId"] as? String)
    _ = try await control("/wait/release?index=0")
    try await Task.sleep(for: .seconds(2))
    XCTAssertTrue(pending.exists, "A late result must not clear the newer outbox")
    XCTAssertEqual(composer.value as? String, "NEXT_DRAFT_" + marker)
    XCTAssertTrue(app.buttons["composer-busy"].exists || (send.exists && !send.isEnabled), "The old callback must not release the new send lock")
    _ = try await control("/wait/release?index=1")
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: send)], timeout: 15), .completed)
    XCTAssertFalse(pending.exists)
    XCTAssertEqual(composer.value as? String, "NEXT_DRAFT_" + marker)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["chat-menu"].tap(); app.buttons["reply-delivery-records"].tap()
    let query = app.buttons["query-detached-reply-" + originalId]
    XCTAssertTrue(query.waitForExistence(timeout: 5)); query.tap()
    XCTAssertTrue(app.staticTexts["reply-record-state-" + originalId].exists)
    app.buttons["完成"].tap()
    if fixture.nonce == "fixture" {
      // An authoritative terminal failure also releases the composer without
      // requiring an end-wait click. It never grants automatic retry authority.
      sendLiveComposer()
      XCTAssertTrue(app.buttons["resume-task"].waitForExistence(timeout: 1) || send.waitForExistence(timeout: 10))
      focusLiveComposer(); composer.typeText("AFTER_KNOWN_FAILURE")
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: send)], timeout: 15), .completed)
    }
    let draft = try XCTUnwrap(composer.value as? String)
    app.terminate()
    if fixture.nonce == "fixture" { _ = try await control("/wait/upgrade") }
    app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 20))
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(pending.exists)
    XCTAssertFalse(app.segmentedControls["reply-mode"].exists)
    app.buttons["chat-menu"].tap(); app.buttons["reply-delivery-records"].tap()
    XCTAssertTrue(app.buttons["query-detached-reply-" + originalId].waitForExistence(timeout: 10))
    shot("reply-wait-records-after-restart")
    app.buttons["完成"].tap()
    let final = try await control("/wait/status")
    XCTAssertEqual((final["sends"] as? [[String: Any]])?.count, fixture.nonce == "fixture" ? 3 : 2)
    XCTAssertTrue((final["queries"] as? [String] ?? []).contains(originalId))
  }

  @MainActor func testDesktopSuggestionClearingRestoresAndShowsEnabledAction() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8799")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let expected = try XCTUnwrap(fixture.suggestionText)
    var request = URLRequest(url: URL(string: fixture.endpoint + "/state?value=suggestion")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    _ = try await URLSession.shared.data(for: request)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let adopt = app.buttons["suggestion-adopt"]
    let details = app.buttons["suggestion-details"]
    XCTAssertTrue(adopt.waitForExistence(timeout: 20))
    setChatAppearance("浅色")
    XCTAssertTrue(details.waitForExistence(timeout: 15)); details.tap()
    let apply = app.buttons["suggestion-apply"]
    XCTAssertTrue(apply.waitForExistence(timeout: 10)); XCTAssertTrue(apply.isEnabled)
    shot("suggestion-enabled-primary-light")
    apply.tap(); expectSuggestionDraft(expected)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    quickAttachmentPanel(); quickAttachmentControl("clear-composer-draft").tap()
    expectSuggestionDraft(""); XCTAssertTrue(adopt.waitForExistence(timeout: 10))
    adopt.tap(); expectSuggestionDraft(expected)
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    composer.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: expected.count))
    expectSuggestionDraft(""); XCTAssertTrue(adopt.waitForExistence(timeout: 10))
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    setChatAppearance("深色")
    XCTAssertTrue(details.waitForExistence(timeout: 15)); details.tap()
    XCTAssertTrue(apply.waitForExistence(timeout: 10)); XCTAssertTrue(apply.isEnabled)
    shot("suggestion-enabled-primary-dark")
    app.buttons["suggestion-details-close"].tap()
    XCTAssertFalse(app.buttons["send-message"].isEnabled, "Restored gray text remains a suggestion until adopted")
  }

  @MainActor func testComposerRecoveryFromHostStates() async throws {
    guard let result = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8799")!),
      let fixture = try? JSONDecoder().decode(LiveFixture.self, from: result.0)
    else { throw XCTSkip("Run the composer state iOS acceptance harness") }
    connectLive(fixture, supportsCreation: false)
    let send = app.buttons["send-message"]
    let resume = app.buttons["resume-task"]
    let stop = app.buttons["stop-turn"]
    for state in ["completed", "interrupted", "failed", "paused", "completed-with-old-interruption", "unknown"] {
      var request = URLRequest(url: URL(string: fixture.endpoint + "/state?value=" + state)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      XCTAssertTrue(app.staticTexts["COMPOSER_STATE_" + state].waitForExistence(timeout: 10))
      if ["interrupted", "failed", "paused"].contains(state) {
        XCTAssertTrue(resume.exists)
        XCTAssertTrue(resume.isEnabled)
        XCTAssertFalse(send.exists)
      } else {
        XCTAssertTrue(send.exists)
        XCTAssertFalse(send.isEnabled)
        XCTAssertFalse(resume.exists)
      }
      XCTAssertFalse(stop.exists)
      XCTAssertFalse(app.descendants(matching: .any)["runtime-progress"].firstMatch.exists)
      XCTAssertFalse(app.segmentedControls["reply-mode"].exists)
      XCTAssertFalse(app.buttons["restart-task"].exists)
      shot("composer-host-" + state)
    }
    var request = URLRequest(url: URL(string: fixture.endpoint + "/state?value=running")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    _ = try await URLSession.shared.data(for: request)
    XCTAssertTrue(app.staticTexts["COMPOSER_STATE_running"].waitForExistence(timeout: 10))
    XCTAssertTrue(stop.exists)
    XCTAssertFalse(app.segmentedControls["reply-mode"].exists, "Empty input must not show queue/steer options")
    composer.tap()
    composer.typeText(" \n ")
    XCTAssertFalse(app.segmentedControls["reply-mode"].exists, "Whitespace is not sendable content")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    quickAttachmentPanel()
    quickAttachmentControl("clear-composer-draft").tap()
    for index in 1...3 {
      composer.tap()
      typeComposerText("queued-fixture-\(index)")
      XCTAssertTrue(send.exists)
      app.segmentedControls["reply-mode"].buttons["排队执行"].tap()
      sendLiveComposer()
      XCTAssertTrue(stop.waitForExistence(timeout: 10))
      XCTAssertFalse(app.segmentedControls["reply-mode"].exists, "Sending clears the draft and hides reply options")
    }
    let (queueData, _) = try await URLSession.shared.data(from: URL(string: fixture.endpoint + "/results")!)
    let queueResults = try XCTUnwrap(JSONSerialization.jsonObject(with: queueData) as? [String: Any])
    let queueIds = try XCTUnwrap(queueResults["queueIds"] as? [String])
    XCTAssertEqual(queueIds.count, 3)
    var previousY: CGFloat = 0
    for (index, id) in queueIds.enumerated() {
      let row = app.staticTexts["queued-text-" + id]
      XCTAssertTrue(row.waitForExistence(timeout: 10))
      XCTAssertEqual(row.label, "queued-fixture-\(index + 1)")
      XCTAssertEqual(app.staticTexts["queued-number-" + id].label, "\(index + 1)")
      XCTAssertGreaterThan(row.frame.midY, previousY)
      XCTAssertLessThan(row.frame.maxY, composer.frame.minY)
      previousY = row.frame.midY
    }
    shot("composer-numbered-queue-above-input")
    app.buttons["cancel-queued-" + queueIds[1]].tap()
    let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.staticTexts["queued-text-" + queueIds[1]])
    XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 10), .completed)
    XCTAssertEqual(app.staticTexts["queued-number-" + queueIds[2]].label, "2")
    composer.tap()
    typeComposerText("steer-fixture")
    app.segmentedControls["reply-mode"].buttons["立即追加"].tap()
    clearComposer()
    XCTAssertFalse(app.segmentedControls["reply-mode"].exists, "Clearing the draft hides reply options")
    composer.typeText("steer-fixture")
    XCTAssertTrue(app.segmentedControls["reply-mode"].buttons["立即追加"].isSelected, "Hiding reply options preserves the chosen mode")
    sendLiveComposer()
    XCTAssertTrue(stop.waitForExistence(timeout: 10))
    composer.tap()
    typeComposerText("retain-running-draft")
    XCTAssertTrue(send.exists)
    send.press(forDuration: 1)
    app.buttons["停止任务"].tap()
    XCTAssertTrue(app.staticTexts["COMPOSER_STATE_interrupted"].waitForExistence(timeout: 10))
    XCTAssertTrue(send.exists)
    XCTAssertTrue(send.isEnabled)
    XCTAssertEqual(composer.value as? String, "retain-running-draft")
    clearComposer()
    XCTAssertTrue(resume.waitForExistence(timeout: 3))
    resume.tap()
    XCTAssertTrue(stop.waitForExistence(timeout: 10))
    let (data, response) = try await URLSession.shared.data(for: URLRequest(url: URL(string: fixture.endpoint + "/results")!))
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let results = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    XCTAssertEqual(results["stopCount"] as? Int, 1)
    XCTAssertEqual(results["resumeCount"] as? Int, 1)
    XCTAssertEqual(results["queueCount"] as? Int, 3)
    XCTAssertEqual(results["steerCount"] as? Int, 1)
    XCTAssertEqual(results["cancelCount"] as? Int, 1)
    XCTAssertEqual(results["replyMode"] as? String, "next-turn")
    shot("composer-host-resumed")
  }

  private func launchVoiceFixture(denied: Bool = false) {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture"] + (denied ? ["-voice-input-denied"] : [])
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["voice-input"].waitForExistence(timeout: 5))
  }

  @MainActor func testAISharingNativePermissionDoesNotReplayAndRevocationPersists() async throws {
    let base = URL(string: "http://127.0.0.1:8818")!
    let (data, response) = try await URLSession.shared.data(from: base)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    func counts() async throws -> [String: Any] {
      let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("results"))
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func assertNoExtraSubmission(_ count: Int) async throws {
      let result = try await counts()
      XCTAssertEqual((result["posts"] as? [Any])?.count, count)
      XCTAssertEqual(result["voiceReads"] as? Int, 0)
    }
    func decline() {
      let button = app.buttons["ai-sharing-decline"]
      for _ in 0..<6 where !button.exists || !button.isHittable { app.swipeUp() }
      XCTAssertTrue(button.waitForExistence(timeout: 10))
      XCTAssertTrue(button.isHittable); button.tap()
      XCTAssertTrue(button.waitForNonExistence(timeout: 10))
    }
    focusLiveComposer(); typeComposerText("原许可草稿 😀")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["ai-sharing-allow"].waitForExistence(timeout: 10))
    shot("sharing-native-before-allow"); try await assertNoExtraSubmission(0)
    decline(); XCTAssertEqual(composer.value as? String, "原许可草稿 😀")
    app.buttons["send-message"].tap(); XCTAssertTrue(allowTestAISharingIfNeeded())
    try await assertNoExtraSubmission(0); XCTAssertEqual(composer.value as? String, "原许可草稿 😀")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 20)); XCTAssertEqual(composer.value as? String, "原许可草稿 😀")
    app.buttons["send-message"].tap()
    let submitted = Date().addingTimeInterval(15)
    while Date() < submitted {
      if (try await counts()["posts"] as? [Any])?.count == 1 { break }
      try await Task.sleep(for: .milliseconds(200))
    }
    try await assertNoExtraSubmission(1)
    focusLiveComposer(); typeComposerText("撤回后保留的草稿")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["chat-menu"].tap(); app.buttons["设置"].tap()
    for _ in 0..<6 where !app.buttons["ai-sharing-settings"].isHittable { app.swipeUp() }
    app.buttons["ai-sharing-settings"].tap()
    let revoke = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ai-sharing-revoke-")).firstMatch
    XCTAssertTrue(revoke.waitForExistence(timeout: 10)); revoke.tap()
    XCTAssertTrue(app.staticTexts["尚未允许共享"].waitForExistence(timeout: 10)); shot("sharing-native-revoked")
    app.terminate(); app.launchChineseTest(); XCTAssertTrue(composer.waitForExistence(timeout: 20))
    XCTAssertEqual(composer.value as? String, "撤回后保留的草稿")
    app.buttons["send-message"].tap(); XCTAssertTrue(app.buttons["ai-sharing-allow"].waitForExistence(timeout: 10))
    try await assertNoExtraSubmission(1); decline()
    app.buttons["voice-input"].tap()
    let voiceModel = app.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@", "Codex 语音转写", "模型、Codex 语音转写")).firstMatch
    XCTAssertTrue(voiceModel.waitForExistence(timeout: 10))
    XCTAssertTrue(allowTestAISharingIfNeeded()); try await assertNoExtraSubmission(1)
    XCTAssertEqual(composer.value as? String, "撤回后保留的草稿")
    XCTAssertFalse(app.buttons["stop-dictation"].exists); shot("sharing-native-voice-permission-no-replay")
  }

  @MainActor func testCodexHostRecordedAudioTranscriptionDEV() async throws {
    guard let base = ProcessInfo.processInfo.environment["VOICE_FIXTURE_URL"] else { throw XCTSkip("Run voice desktop iOS acceptance harness") }
    let environment = ProcessInfo.processInfo.environment
    let fixture: [String: String]
    if let publicHost = environment["VOICE_PUBLIC_HOST_ID"] {
      fixture = ["hostId": publicHost, "fileFixture": environment["VOICE_FILE_FIXTURE"] ?? "false"]
    } else if let pairing = environment["VOICE_PAIRING_URI"] {
      fixture = ["uri": pairing, "fileFixture": environment["VOICE_FILE_FIXTURE"] ?? "false"]
    } else {
      let (data, _) = try await URLSession.shared.data(from: URL(string: base)!)
      fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    }
    let restoreOriginal = environment["VOICE_RESTORE_ORIGINAL"] == "1"
    let injectPairing = fixture["fileFixture"] == "true" && !restoreOriginal && fixture["hostId"] == nil
    if fixture["fileFixture"] == "true" {
      app.terminate(); app.launchArguments = ["-ui-testing", "-voice-file-fixture"] + (restoreOriginal ? ["-restore-testing"] : [])
      app.launchEnvironment = ["VOICE_FILE_FIXTURE_URL": base + "/audio"]
      if injectPairing {
        // Keep the private URI out of XCTest keyboard typing activities.
        app.launchArguments.append("-voice-pairing-fixture")
        app.launchEnvironment["VOICE_PAIRING_URI"] = try XCTUnwrap(fixture["uri"])
      }
      app.launchChineseTest()
    }
    if !restoreOriginal {
      if !injectPairing {
        app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
      }
      if let host = fixture["hostId"] {
        let row = app.buttons["codex-discovered-" + host]
        let permissionDeadline = Date().addingTimeInterval(60)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        while Date() < permissionDeadline && !row.exists {
          if springboard.alerts.firstMatch.exists {
            let alert = springboard.alerts.firstMatch
            let text = alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")
            if text.contains("本地网络") || text.localizedCaseInsensitiveContains("local network") {
              let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "Allow", "好", "OK"])).firstMatch
              XCTAssertTrue(allow.exists); allow.tap()
            }
          }
          try await Task.sleep(for: .milliseconds(200))
        }
        XCTAssertTrue(row.exists, "The actual isolated Host must appear through native discovery; no Host token is supplied to the App")
        XCTAssertTrue(row.label.contains("点选连接")); row.tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 90), app.debugDescription)
        XCTAssertFalse(app.staticTexts["client-form-error"].exists)
      } else {
        if !injectPairing { selectConnectionMethod("扫码连接", agent: "codex") }
        let qr = app.textFields["codex-qr-description"]
        XCTAssertTrue(qr.waitForExistence(timeout: 15))
        if !injectPairing {
          qr.tap(); qr.typeText(try XCTUnwrap(fixture["uri"]))
        }
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        app.buttons["codex-connect-qr"].tap()
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: qr)], timeout: 90), .completed)
      }
    }
    func selectRecordingTarget() {
      guard let thread = environment["VOICE_CAPTURE_THREAD_ID"] else { return }
      if !app.buttons["close-sidebar"].exists { app.buttons["open-sidebar"].tap() }
      if let title = environment["VOICE_CAPTURE_THREAD_TITLE"] {
        let search = app.textFields["sidebar-search"]
        if !search.exists { app.buttons["sidebar-search-toggle"].tap() }
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
        search.tap(); search.typeText(title + "\n")
      }
      let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@", "recent-thread-" + thread, "thread-" + thread)).firstMatch
      XCTAssertTrue(row.waitForExistence(timeout: 25)); row.tap()
      if app.buttons["close-async-questions"].waitForExistence(timeout: 3) { app.buttons["close-async-questions"].tap() }
    }
    selectRecordingTarget()
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    XCTAssertTrue(composer.waitForExistence(timeout: 25))
    if environment["VOICE_RESTORE_ORIGINAL"] == "1" {
      XCTAssertEqual(composer.value as? String, "VOICE_ORIGINAL_DRAFT", "Reuse the original unsent draft; never append or replace it")
    } else { focusLiveComposer(); composer.typeText("VOICE_ORIGINAL_DRAFT") }
    func voiceEvidence(_ name: String) {
      shot(name, fullScreen: true)
      let ax = XCTAttachment(string: app.debugDescription)
      ax.name = name + "-AX"; ax.lifetime = .keepAlways; add(ax)
    }
    voiceEvidence("actual-voice-before-tap")
    app.buttons["voice-input"].tap()
    print("VOICE_MIC_ACTION_SENT")
    voiceEvidence("actual-voice-after-tap")
    if app.buttons["ai-sharing-allow"].waitForExistence(timeout: 15) && allowTestAISharingIfNeeded() {
      XCTAssertTrue(app.buttons["voice-input"].waitForExistence(timeout: 5)); app.buttons["voice-input"].tap()
    }
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    if environment["VOICE_EXPECT_CAPTURE_UNAVAILABLE"] == "1" {
      let alert = app.alerts["无法完成操作"]
      XCTAssertTrue(alert.waitForExistence(timeout: 20), app.debugDescription)
      XCTAssertTrue(alert.staticTexts.allElementsBoundByIndex.map(\.label).joined().contains("录音设备准备超时"))
      voiceEvidence("actual-unavailable-microphone-responsive-error")
      alert.buttons["好"].tap()
      XCTAssertEqual(composer.value as? String, "VOICE_ORIGINAL_DRAFT")
      XCTAssertTrue(app.buttons["voice-input"].isEnabled)
      voiceEvidence("actual-unavailable-original-draft-preserved")
      return
    }
    let deadline = Date().addingTimeInterval(35)
    let stop = app.buttons["stop-dictation"]
    while Date() < deadline && !(stop.exists && stop.isEnabled) {
      if springboard.alerts.firstMatch.exists {
        let allow = springboard.alerts.firstMatch.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "好", "Allow", "OK"])).firstMatch
        XCTAssertTrue(allow.exists); allow.tap()
      }
      if app.alerts.firstMatch.exists { XCTFail(app.alerts.firstMatch.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")); return }
      try await Task.sleep(for: .milliseconds(200))
    }
    voiceEvidence("actual-voice-after-native-wait")
    XCTAssertTrue(stop.exists && stop.isEnabled, app.debugDescription)
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "VOICE_ORIGINAL_DRAFT")
    if fixture["fileFixture"] != "true" {
      print("VOICE_HOST_ACOUSTIC_READY")
      try await Task.sleep(for: .seconds(12))
    }
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "VOICE_ORIGINAL_DRAFT", "Recording must not perform phone transcription")
    XCTAssertFalse(app.buttons["send-dictation"].exists)
    stop.tap()
    if environment["VOICE_VERIFY_RECOVERY"] == "1" {
      let leave = app.buttons["cancel-dictation"]
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "结束等待"), object: leave)], timeout: 5), .completed)
      leave.tap()
      XCTAssertTrue(composer.waitForExistence(timeout: 5))
      XCTAssertEqual(composer.value as? String, "VOICE_ORIGINAL_DRAFT")
      try await Task.sleep(for: .seconds(1))
      app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing", "-voice-file-fixture"]
      app.launchChineseTest()
      selectRecordingTarget()
      XCTAssertTrue(composer.waitForExistence(timeout: 30))
      XCTAssertEqual(composer.value as? String, "VOICE_ORIGINAL_DRAFT")
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: app.buttons["voice-input"])], timeout: 30), .completed, app.debugDescription)
      app.buttons["voice-input"].tap()
      XCTAssertTrue(app.alerts["无法完成操作"].waitForExistence(timeout: 5))
      XCTAssertTrue(app.alerts["无法完成操作"].staticTexts.allElementsBoundByIndex.map(\.label).joined().contains("已恢复"))
      app.alerts["无法完成操作"].buttons["好"].tap()
      XCTAssertEqual(stop.label, "重试转写")
      XCTAssertTrue(app.buttons["voice-record-again"].isHittable)
      app.buttons["voice-record-again"].tap()
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "结束录音并转写"), object: stop)], timeout: 35), .completed)
      XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "VOICE_ORIGINAL_DRAFT")
      app.buttons["cancel-dictation"].tap()
      XCTAssertTrue(composer.waitForExistence(timeout: 5))
      XCTAssertEqual(composer.value as? String, "VOICE_ORIGINAL_DRAFT")
      app.buttons["voice-input"].tap()
      XCTAssertTrue(app.alerts["无法完成操作"].waitForExistence(timeout: 5))
      XCTAssertTrue(app.alerts["无法完成操作"].staticTexts.allElementsBoundByIndex.map(\.label).joined().contains("已恢复"))
      app.alerts["无法完成操作"].buttons["好"].tap()
      XCTAssertEqual(stop.label, "重试转写")
      XCTAssertTrue(stop.isEnabled); stop.tap()
    }
    XCTAssertTrue(composer.waitForExistence(timeout: 110))
    let result = try XCTUnwrap(composer.value as? String)
    XCTAssertTrue(result.hasPrefix("VOICE_ORIGINAL_DRAFT\n"))
    if let phrase = environment["VOICE_EXPECTED_PHRASE"] {
      func normalized(_ text: String) -> String { text.lowercased().filter { $0.isLetter || $0.isNumber } }
      XCTAssertTrue(normalized(result).contains(normalized(phrase)))
    } else {
      XCTAssertTrue(result.localizedCaseInsensitiveContains("violet lantern"))
      XCTAssertTrue(result.localizedCaseInsensitiveContains("copper bridge"))
    }
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "VOICE_ORIGINAL_DRAFT\n")).firstMatch.exists, "Text must remain an unsent draft")
    print("VOICE_HOST_TRANSCRIPTION_RESULT", result)
    shot("codex-host-original-recording-transcribed-draft")
    var evidence = URLRequest(url: URL(string: base + "/evidence")!)
    evidence.httpMethod = "POST"
    evidence.setValue(environment["VOICE_FIXTURE_NONCE"], forHTTPHeaderField: "X-Voice-Fixture-Nonce")
    evidence.httpBody = try JSONSerialization.data(withJSONObject: ["text": result])
    _ = try await URLSession.shared.data(for: evidence)
  }

  @MainActor func testRealtimeVoiceNetworkPermissionDEV() throws {
    app.launchArguments = ["-ui-testing", "-voice-open-settings"]
    app.launchChineseTest()
    let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")
    XCTAssertTrue(settings.wait(for: .runningForeground, timeout: 15))
    let ax = XCTAttachment(string: settings.debugDescription)
    ax.name = "own-dev-app-network-settings"; ax.lifetime = .keepAlways; add(ax)
    let screen = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    screen.name = "own-dev-app-network-settings"; screen.lifetime = .keepAlways; add(screen)
    let wireless = settings.buttons["com.easyai.agenthub.rtvoicefix.wireless"]
    XCTAssertTrue(wireless.exists)
    wireless.tap()
    let options = XCTAttachment(string: settings.debugDescription)
    options.name = "own-dev-app-network-options"; options.lifetime = .keepAlways; add(options)
    let wifi = settings.staticTexts.matching(NSPredicate(format: "label IN %@", ["无线局域网", "WLAN", "Wi-Fi"])).firstMatch
    XCTAssertTrue(wifi.waitForExistence(timeout: 5), "Only enable Wi-Fi for the exact isolated acceptance bundle")
    wifi.tap()
  }

  @MainActor func testRealtimeVoicePhysicalDEV() async throws {
    guard let encoded = ProcessInfo.processInfo.environment["RTVOICE_PHYSICAL_CONFIG"],
      let data = encoded.data(using: .utf8),
      let config = try JSONSerialization.jsonObject(with: data) as? [String: String],
      let pairing = config["pairingURI"], let threadId = config["threadId"],
      let title = config["title"], let marker = config["marker"],
      let archivedThreadId = config["archivedThreadId"],
      let audioURL = config["audioURL"], let phrase = config["expectedPhrase"] else {
      throw XCTSkip("Use an isolated signed App and a dedicated real desktop verification conversation")
    }
    app.launchArguments = ["-ui-testing", "-voice-pairing-fixture", "-voice-file-fixture"]
    app.launchEnvironment = ["VOICE_PAIRING_URI": pairing, "VOICE_FILE_FIXTURE_URL": audioURL]
    app.launchChineseTest()
    let connect = app.buttons["codex-connect-qr"]
    XCTAssertTrue(connect.waitForExistence(timeout: 20)); connect.tap()
    let permissionDeadline = Date().addingTimeInterval(90)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    while Date() < permissionDeadline && connect.exists {
      let alert = springboard.alerts.firstMatch
      if alert.exists {
        let text = alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")
        if text.contains("Agent Hub") && (text.contains("本地网络") || text.localizedCaseInsensitiveContains("local network")) {
          let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "Allow", "好", "OK"])).firstMatch
          XCTAssertTrue(allow.exists); allow.tap()
        }
      }
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertFalse(connect.exists)
    XCTAssertFalse(app.staticTexts["client-form-error"].exists)
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(title + "\n")
    let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@",
      "recent-thread-" + threadId, "thread-" + threadId)).firstMatch
    XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 45))
    XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
    shot("rtvoice-real-live-owner", fullScreen: true)
    func menu() {
      let button = app.buttons["chat-menu"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: button)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
      button.tap()
    }
    menu(); app.buttons["archived-chats"].tap()
    let archived = app.buttons["archived-chat-" + archivedThreadId]
    for _ in 0..<30 {
      if archived.exists && archived.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(archived.waitForExistence(timeout: 40)); archived.tap()
    let notice = app.descendants(matching: .any)["archived-conversation-notice"].firstMatch
    XCTAssertTrue(notice.waitForExistence(timeout: 30))
    // Span a full event long-poll and background history refresh. Missing an
    // archived execution owner must not reintroduce the live-retry banner.
    for _ in 0..<13 {
      XCTAssertFalse(app.staticTexts["conversation-sync-failure"].exists)
      try await Task.sleep(for: .seconds(2))
    }
    shot("rtvoice-archived-history-without-live-retry", fullScreen: true)
    // Some third-party keyboards expose keys without a Keyboard element.
    // Verify the actual draft rather than requiring that standard AX node.
    let draftReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
    XCTAssertEqual(XCTWaiter.wait(for: [draftReady], timeout: 30), .completed)
    composer.tap(); composer.typeText("RTVOICE_UNSENT_DRAFT")
    XCTAssertEqual(composer.value as? String, "RTVOICE_UNSENT_DRAFT")
    app.buttons["voice-input"].tap()
    if app.buttons["ai-sharing-allow"].waitForExistence(timeout: 10), allowTestAISharingIfNeeded() {
      app.buttons["voice-input"].tap()
    }
    let stop = app.buttons["stop-dictation"]
    let listening = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isEnabled == true"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [listening], timeout: 35), .completed)
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "RTVOICE_UNSENT_DRAFT")
    stop.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 110))
    let result = try XCTUnwrap(composer.value as? String)
    XCTAssertTrue(result.hasPrefix("RTVOICE_UNSENT_DRAFT\n") && result.contains(phrase))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label == %@", result)).firstMatch.exists,
      "Transcription stays in the draft and must not create a chat input")
    shot("rtvoice-real-asr-unsent-draft", fullScreen: true)
  }

  @MainActor func testVoiceExistingTranscriptionDraftDEV() async throws {
    guard let expected = ProcessInfo.processInfo.environment["VOICE_EXISTING_EXPECTED_DRAFT"] else { throw XCTSkip("Use original completed voice receipt; never start another recording") }
    XCTAssertTrue(composer.waitForExistence(timeout: 30))
    XCTAssertEqual(composer.value as? String, expected)
    XCTAssertTrue(app.buttons["voice-input"].exists)
    XCTAssertFalse(app.buttons["stop-dictation"].exists)
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label == %@", expected)).firstMatch.exists, "The transcript stays in the unsent composer")
    shot("existing-real-voice-exact-unsent-draft", fullScreen: true)
    let ax = XCTAttachment(string: app.debugDescription); ax.name = "existing-real-voice-AX"; ax.lifetime = .keepAlways; add(ax)
  }

  @MainActor func testComposerLongPressStartsVoiceInput() {
    launchVoiceFixture()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertTrue((composer.placeholderValue ?? "").contains("长按语音输入"))
    shot("voice-long-press-empty-placeholder", fullScreen: true)
    composer.press(forDuration: 0.7)
    let stop = app.buttons["stop-dictation"]
    XCTAssertTrue(stop.waitForExistence(timeout: 5), "Long pressing the input must start recording")
    guard stop.exists else { return }
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
    shot("voice-long-press-recording", fullScreen: true)
    app.buttons["cancel-dictation"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    composer.tap(); typeComposerText("long-press-retained-draft")
    composer.press(forDuration: 0.7)
    XCTAssertTrue(stop.waitForExistence(timeout: 5))
    guard stop.exists else { return }
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "long-press-retained-draft")
    app.buttons["cancel-dictation"].tap()
    XCTAssertEqual(composer.value as? String, "long-press-retained-draft")
  }

  @MainActor func testDictationFailureKeepsVisibleRecovery() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-voice-transcription-failed"]
    app.launchChineseTest()
    app.buttons["voice-input"].tap()
    let stop = app.buttons["stop-dictation"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
    stop.tap()
    shot("voice-empty-draft-after-desktop-failure", fullScreen: true)
    XCTAssertTrue(app.staticTexts["voice-failure-title"].waitForExistence(timeout: 5), "Failure must remain visible in the input")
    XCTAssertEqual(stop.label, "重试转写", "The icon must keep its accessibility label")
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertTrue(app.staticTexts["voice-status"].label.contains("录音已保留"))
    stop.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "重试转写"), object: stop)], timeout: 5), .completed)
    XCTAssertTrue(app.staticTexts["voice-failure-title"].exists)
    shot("voice-empty-draft-retry-still-failed", fullScreen: true)
    let failureMessage = app.staticTexts["voice-status"].label
    XCUIDevice.shared.press(.home)
    app.activate()
    XCTAssertTrue(app.staticTexts["voice-failure-title"].waitForExistence(timeout: 5))
    XCTAssertEqual(app.staticTexts["voice-status"].label, failureMessage)
    app.buttons["cancel-dictation"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
  }

  @MainActor func testDictationFailureOffersRetryAndLocalTranscription() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-voice-transcription-failed"]
    app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    composer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    typeComposerText("retained-fallback-draft")
    app.buttons["voice-input"].tap()
    let stop = app.buttons["stop-dictation"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
    stop.tap()
    XCTAssertTrue(app.staticTexts["voice-failure-title"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertEqual(stop.label, "重试转写")
    XCTAssertTrue(app.buttons["voice-local-transcription-inline"].exists)
    shot("voice-failure-inline-recovery-options", fullScreen: true)
    stop.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "重试转写"), object: stop)], timeout: 5), .completed)
    XCTAssertTrue(app.staticTexts["voice-failure-title"].exists)
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "retained-fallback-draft")
    app.buttons["voice-local-transcription-inline"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "retained-fallback-draft\n语音输入测试")
    XCTAssertFalse(app.buttons["stop-dictation"].exists)
    XCTAssertFalse(app.staticTexts["retained-fallback-draft\n语音输入测试"].exists)
    shot("voice-local-fallback-unsent-draft")
  }

  @MainActor func testDiscardPendingRecordingDoesNotRestoreAfterRestart() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-voice-transcription-failed"]
    // One second of synthetic PCM WAV, saved through the production pending store.
    var wav = Data([82,73,70,70,36,125,0,0,87,65,86,69,102,109,116,32,16,0,0,0,1,0,1,0,128,62,0,0,0,125,0,0,2,0,16,0,100,97,116,97,0,125,0,0])
    wav.append(Data(repeating: 0, count: 32000))
    app.launchEnvironment["VOICE_PENDING_FIXTURE_WAV"] = wav.base64EncodedString()
    app.launchChineseTest()
    let draft = "discard-recording-" + UUID().uuidString.lowercased()
    XCTAssertTrue(composer.waitForExistence(timeout: 10))
    composer.tap(); typeComposerText(draft)
    func startNewRecording() {
      app.buttons["voice-input"].tap()
      let stop = app.buttons["stop-dictation"]
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
      XCTAssertEqual(stop.label, "结束录音并转写", "Discarded audio must not replace a new recording")
      XCTAssertFalse(app.alerts.firstMatch.exists)
    }
    func discardWaiting() {
      let cancel = app.buttons["cancel-dictation"]
      XCTAssertTrue(cancel.waitForExistence(timeout: 5))
      XCTAssertEqual(cancel.label, "丢弃录音")
      cancel.tap()
      XCTAssertTrue(composer.waitForExistence(timeout: 5))
      XCTAssertEqual(composer.value as? String, draft)
    }
    func relaunch() {
      app.terminate()
      app.launchArguments = ["-ui-testing", "-restore-testing", "-voice-input-fixture", "-voice-transcription-failed"]
      app.launchChineseTest()
      XCTAssertTrue(composer.waitForExistence(timeout: 10))
      XCTAssertEqual(composer.value as? String, draft)
    }
    startNewRecording()
    app.buttons["stop-dictation"].tap()
    discardWaiting()
    startNewRecording()
    app.buttons["cancel-dictation"].tap()
    relaunch()
    startNewRecording()
    app.buttons["stop-dictation"].tap()
    // Leaving the app without pressing × must still retain recovery.
    relaunch()
    app.buttons["voice-input"].tap()
    XCTAssertTrue(app.staticTexts["voice-failure-title"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["voice-record-again"].exists)
    app.buttons["stop-dictation"].tap()
    discardWaiting()
    relaunch()
    startNewRecording()
    app.buttons["cancel-dictation"].tap()
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label == %@", draft)).firstMatch.exists)
    shot("discard-pending-recording-restart-unsent-draft", fullScreen: true)
  }

  func testDictationCancelFinishAndSend() {
    launchVoiceFixture()
    composer.tap()
    typeComposerText("original-draft")
    app.buttons["voice-input"].tap()
    let transcript = app.staticTexts["dictation-transcript"]
    XCTAssertTrue(transcript.waitForExistence(timeout: 3))
    XCTAssertEqual(transcript.label, "original-draft")
    XCTAssertFalse(app.staticTexts["dictation-status"].exists)
    shot("dictation-listening-light")
    app.buttons["cancel-dictation"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 3))
    XCTAssertEqual(composer.value as? String, "original-draft")
    app.buttons["voice-input"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: app.buttons["stop-dictation"])], timeout: 5), .completed)
    let tapStop = app.buttons["dictation-waveform"]
    XCTAssertEqual(tapStop.label, "轻触停止录音")
    XCTAssertTrue(tapStop.isHittable)
    XCTAssertGreaterThanOrEqual(tapStop.frame.height, 44)
    XCTAssertFalse(tapStop.frame.intersects(app.buttons["cancel-dictation"].frame))
    XCTAssertFalse(tapStop.frame.intersects(app.buttons["stop-dictation"].frame))
    tapStop.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "original-draft\n语音输入测试")
    XCTAssertFalse(app.staticTexts["original-draft\n语音输入测试"].exists)
    app.buttons["voice-input"].tap()
    XCTAssertFalse(app.buttons["send-dictation"].exists)
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: app.buttons["stop-dictation"])], timeout: 5), .completed)
    app.buttons["stop-dictation"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts["original-draft\n语音输入测试\n语音输入测试"].waitForExistence(timeout: 5))
    XCTAssertFalse((composer.value as? String ?? "").contains("original-draft"))
  }

  func testDictationStopHintEnglish() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-app-language", "en", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    composer.tap(); typeComposerText("retained-English-draft")
    app.buttons["voice-input"].tap()
    let tapStop = app.buttons["dictation-waveform"]
    XCTAssertTrue(tapStop.waitForExistence(timeout: 5))
    XCTAssertEqual(tapStop.label, "Tap to stop recording")
    XCTAssertTrue(tapStop.isHittable)
    shot("dictation-tap-stop-english")
    tapStop.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "retained-English-draft\n语音输入测试")
    XCTAssertFalse(app.buttons["dictation-waveform"].exists)
    XCTAssertFalse(app.staticTexts["retained-English-draft\n语音输入测试"].exists)
  }

  func testDictationSwitchAndBackgroundKeepOriginalTarget() {
    launchVoiceFixture()
    composer.tap()
    typeComposerText("mac-draft")
    app.buttons["voice-input"].tap()
    XCTAssertTrue(app.staticTexts["dictation-transcript"].waitForExistence(timeout: 3))
    switchClient("demo-studio")
    XCTAssertFalse(app.buttons["stop-dictation"].exists)
    XCTAssertFalse((composer.value as? String ?? "").contains("mac-draft"))
    composer.tap()
    typeComposerText("studio-draft")
    switchClient("demo-mac")
    XCTAssertEqual(composer.value as? String, "mac-draft")
    app.buttons["voice-input"].tap()
    XCTAssertTrue(app.buttons["stop-dictation"].waitForExistence(timeout: 3))
    XCUIDevice.shared.press(.home)
    app.activate()
    XCTAssertTrue(app.buttons["stop-dictation"].waitForExistence(timeout: 5))
    if app.alerts["无法完成操作"].exists { app.alerts["无法完成操作"].buttons["好"].tap() }
    app.buttons["stop-dictation"].tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "mac-draft\n语音输入测试")
    switchClient("demo-studio")
    XCTAssertEqual(composer.value as? String, "studio-draft")
  }

  func testDictationDeniedAndDarkAppearance() {
    launchVoiceFixture(denied: true)
    composer.tap()
    typeComposerText("retained-draft")
    app.buttons["voice-input"].tap()
    XCTAssertTrue(app.staticTexts["voice-failure-title"].waitForExistence(timeout: 3))
    XCTAssertEqual(app.staticTexts["voice-failure-title"].label, "录音未完成")
    XCTAssertTrue(app.staticTexts["voice-status"].label.contains("麦克风权限"))
    XCTAssertEqual(app.buttons["stop-dictation"].label, "重新录音")
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "retained-draft")
    shot("voice-preparation-failed-retains-draft", fullScreen: true)
    app.buttons["stop-dictation"].tap()
    XCTAssertTrue(app.staticTexts["voice-failure-title"].waitForExistence(timeout: 3))
    app.buttons["cancel-dictation"].tap()
    XCTAssertEqual(composer.value as? String, "retained-draft")
    XCTAssertTrue(app.buttons["voice-input"].isEnabled)
    launchVoiceFixture()
    app.buttons["open-sidebar"].tap()
    app.buttons["open-settings"].tap()
    app.descendants(matching: .any)["appearance-picker"].firstMatch.tap()
    app.buttons["深色"].tap()
    app.buttons["完成"].tap()
    app.buttons["voice-input"].tap()
    XCTAssertTrue(app.staticTexts["dictation-transcript"].waitForExistence(timeout: 3))
    let tapStop = app.buttons["dictation-waveform"]
    XCTAssertTrue(tapStop.waitForExistence(timeout: 5))
    XCTAssertEqual(tapStop.label, "轻触停止录音")
    XCTAssertTrue(tapStop.isHittable)
    shot("dictation-listening-dark")
    tapStop.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "语音输入测试")
  }

  func testDictationUnavailableAutomaticallyUsesLocal() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-voice-input-disabled"]
    app.launchChineseTest()
    composer.tap()
    typeComposerText("fallback-draft")
    app.buttons["voice-input"].tap()
    let stop = app.buttons["stop-dictation"]
    XCTAssertTrue(stop.waitForExistence(timeout: 5))
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertEqual(app.staticTexts["dictation-transcript"].label, "fallback-draft")
    XCTAssertTrue((app.otherElements["dictation-waveform"].value as? String ?? "").contains("backend=LocalSpeech"))
    XCTAssertEqual(app.otherElements["dictation-waveform"].label, "电脑转写暂不可用，正在本地听写")
    stop.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, "fallback-draft\n语音输入测试")
    XCTAssertFalse(app.staticTexts["fallback-draft\n语音输入测试"].exists)
    shot("dictation-unavailable-local-fallback")
  }

  func testDictationDoesNotWaitForPreflightOrChangeActiveBackend() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-voice-readiness-delayed"]
    app.launchChineseTest()
    let mic = app.buttons["voice-input"]
    XCTAssertTrue(mic.waitForExistence(timeout: 5))
    mic.tap()
    let stop = app.buttons["stop-dictation"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 3), .completed)
    let waveform = app.otherElements["dictation-waveform"]
    XCTAssertTrue((waveform.value as? String ?? "").contains("backend=LocalSpeech"))
    XCTAssertEqual(waveform.label, "电脑转写暂不可用，正在本地听写")
    // The background result arrives during this recording; its route stays fixed.
    RunLoop.current.run(until: Date().addingTimeInterval(9))
    XCTAssertTrue((waveform.value as? String ?? "").contains("backend=LocalSpeech"))
    app.buttons["cancel-dictation"].tap()
    for _ in 0..<2 {
      mic.tap()
      // Each preflight takes eight seconds; a click must use the prepared result.
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 3), .completed)
      XCTAssertTrue((waveform.value as? String ?? "").contains("backend=HostTranscription"))
      app.buttons["cancel-dictation"].tap()
    }
    switchClient("demo-studio")
    mic.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 3), .completed)
    XCTAssertTrue((waveform.value as? String ?? "").contains("backend=LocalSpeech"), "A different computer cannot reuse the previous preflight")
    app.buttons["cancel-dictation"].tap()
  }

  func testDictationDefaultSettingPersistsAndSelectsBackend() {
    launchVoiceFixture()
    func selectDefault(_ title: String) {
      app.buttons["open-sidebar"].tap()
      app.buttons["open-settings"].tap()
      let picker = app.descendants(matching: .any)["voice-transcription-picker"].firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 5)); picker.tap()
      app.buttons[title].tap()
      app.buttons["完成"].tap()
    }
    selectDefault("本地（手机）")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing", "-voice-input-fixture"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["voice-input"].waitForExistence(timeout: 10))
    app.buttons["voice-input"].tap()
    let stop = app.buttons["stop-dictation"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
    XCTAssertTrue((app.otherElements["dictation-waveform"].value as? String ?? "").contains("backend=LocalSpeech"))
    app.buttons["cancel-dictation"].tap()
    selectDefault("电脑端")
    app.buttons["voice-input"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: stop)], timeout: 5), .completed)
    XCTAssertTrue((app.otherElements["dictation-waveform"].value as? String ?? "").contains("backend=HostTranscription"))
    app.buttons["cancel-dictation"].tap()
  }

  func testSidebarSearchAndProjectSelection() {
    shot("compact-main")
    XCTAssertEqual(app.tabBars.count, 0)
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["thread-navigation"].waitForExistence(timeout: 3))
    shot("compact-sidebar")
    app.buttons["sidebar-search-toggle"].tap()
    let search = app.textFields["sidebar-search"]
    search.tap()
    search.typeText("查询")
    XCTAssertTrue(app.buttons["thread-query"].exists)
    XCTAssertFalse(app.buttons["thread-navigation"].exists)
    app.buttons["thread-query"].tap()
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    XCTAssertTrue(app.buttons["client-switch"].label.contains("api-service"))
    app.buttons["open-sidebar"].tap()
    app.buttons["sidebar-client-switch"].tap()
    app.buttons["client-demo-studio"].tap()
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["project-web"].exists)
    XCTAssertFalse(app.buttons["project-mobile"].exists)
    XCTAssertFalse(app.buttons["recent-thread-navigation"].exists)
  }

  @MainActor private func sidebarRefreshFixture() async throws -> LiveFixture {
    guard let result = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8797/reset")!) else {
      throw XCTSkip("Run scripts/sidebar-refresh-ios-fixture.mjs for list refresh acceptance")
    }
    return try JSONDecoder().decode(LiveFixture.self, from: result.0)
  }

  @MainActor private func readLifecycleState(_ mode: String) async throws {
    let (_, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/state?mode=" + mode)!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
  }
  @MainActor func testSuggestionDisplayLeaseExpiresAndRecoversWithoutSending() async throws {
    let root = "http://127.0.0.1:8826"
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: root + "/reset")!) else {
      throw XCTSkip("Run scripts/desktop-suggestion-stability-ios-fixture.mjs on an isolated simulator")
    }
    connectLive(try JSONDecoder().decode(LiveFixture.self, from: data), supportsCreation: false)
    let search = openDesktopSidebarSearch()
    search.tap(); search.typeText("Suggestion stability\n")
    let thread = app.buttons["recent-thread-suggestion-stability-thread"]
    XCTAssertTrue(thread.waitForExistence(timeout: 10)); thread.tap()
    let hint = app.descendants(matching: .any)["suggestion-text"].firstMatch
    let adopt = app.buttons["suggestion-adopt"]
    XCTAssertTrue(hint.waitForExistence(timeout: 10))
    let original = hint.label
    XCTAssertFalse(original.isEmpty)
    // Stay online past the original six-second lease: identical renewals must
    // retain the same visible text and available adoption action.
    for _ in 0..<8 {
      try await Task.sleep(for: .seconds(1))
      XCTAssertTrue(hint.exists)
      XCTAssertEqual(hint.label, original)
      XCTAssertTrue(adopt.isEnabled)
    }
    func state(_ mode: String) async throws {
      let (_, response) = try await URLSession.shared.data(from: URL(string: root + "/state?mode=" + mode)!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    for _ in 0..<2 {
      try await state("offline")
      XCTAssertTrue(hint.waitForNonExistence(timeout: 12), "An unavailable display cannot retain an expired lease")
      try await state("recovering")
      try await Task.sleep(for: .seconds(2))
      XCTAssertFalse(hint.exists, "A transport heartbeat cannot restore a display before a fresh owner snapshot")
      try await state("online")
      XCTAssertTrue(hint.waitForExistence(timeout: 15))
      let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: adopt)
      XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 15), .completed)
      XCTAssertEqual(hint.label, original)
    }
    XCTAssertTrue(normalizedSuggestionDraftText().isEmpty)
    try await state("new-turn")
    XCTAssertTrue(hint.waitForNonExistence(timeout: 10), "A real new turn must invalidate the old suggestion")
    print("SUGGESTION_DISPLAY_LEASE_RENEWAL_EXPIRY_RECOVERY_PASSED_NO_SEND")
  }
  @MainActor private func readLifecycleCounters() async throws -> [String: Int] {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/results")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Int])
  }
  @MainActor private func waitForReadLifecycleCounter(_ key: String, atLeast minimum: Int) async throws {
    for _ in 0..<80 {
      let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/results")!)
      let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Int])
      if counters[key, default: 0] >= minimum { return }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTFail("Read lifecycle counter did not reach boundary: " + key)
  }
  @MainActor private func assertReadLifecycleModelPending() async throws {
    try await waitForReadLifecycleCounter("modelPending", atLeast: 1)
    let counters = try await readLifecycleCounters()
    XCTAssertEqual(counters["modelResponses", default: 0], 0, "A completed response cannot prove request cancellation")
  }
  @MainActor private func connectReadLifecycleFixture() async throws {
    guard let (data, response) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/reset")!) else {
      throw XCTSkip("Run scripts/read-cancellation-ios-fixture.mjs")
    }
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    // Connection setup includes system password-offer and sidebar validation.
    // Start the delayed model read only after those unrelated UI waits finish.
    try await readLifecycleState("disable-models")
    connectLive(try JSONDecoder().decode(LiveFixture.self, from: data), supportsCreation: false)
    XCTAssertTrue(app.staticTexts["READ_LIFECYCLE_HISTORY"].waitForExistence(timeout: 10))
    let beforeModels = try await readLifecycleCounters()
    XCTAssertEqual(beforeModels["modelStarts", default: 0], 0)
    try await readLifecycleState("enable-models")
    try await waitForReadLifecycleCounter("modelStarts", atLeast: 1)
    try await assertReadLifecycleModelPending()
  }
  @MainActor func testReadCancellationOnCapabilityChangeAndClientSwitch() async throws {
    try await connectReadLifecycleFixture()
    try await readLifecycleState("disable-models")
    try await waitForReadLifecycleCounter("modelCancelled", atLeast: 1)
    XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 3), "Capabilities cancelled a read request, not a user operation")
    XCTAssertTrue(app.staticTexts["READ_LIFECYCLE_HISTORY"].exists)
    // Prepare the native picker before arming the next request, then exercise
    // the actual selection while the request is independently known in flight.
    app.buttons["client-switch"].tap()
    let demoClient = app.buttons["client-demo-mac"]
    XCTAssertTrue(demoClient.waitForExistence(timeout: 3))
    try await readLifecycleState("enable-models")
    try await waitForReadLifecycleCounter("modelStarts", atLeast: 2)
    try await assertReadLifecycleModelPending()
    demoClient.tap()
    try await waitForReadLifecycleCounter("modelCancelled", atLeast: 2)
    XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 3), "The previous client's cancelled model load must not alert on the new client")
    XCTAssertFalse(app.staticTexts["READ_LIFECYCLE_HISTORY"].exists)
    switchClient("read-cancellation-host")
    XCTAssertTrue(app.staticTexts["READ_LIFECYCLE_HISTORY"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("read-lifecycle-client-restored")
  }
  @MainActor func testSilentEventConnectionRecoversHistoryWithoutReplayingInput() async throws {
    try await connectReadLifecycleFixture()
    try await readLifecycleState("disable-models")
    try await waitForReadLifecycleCounter("modelCancelled", atLeast: 1)
    composer.tap(); composer.typeText("preserve-draft-during-silent-events")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let before = try await readLifecycleCounters()
    try await readLifecycleState("silent-completion")
    XCTAssertTrue(app.staticTexts["SILENT_EVENT_RECOVERED"].waitForExistence(timeout: 13))
    let after = try await readLifecycleCounters()
    XCTAssertGreaterThan(after["historyReads"] as? Int ?? 0, before["historyReads"] as? Int ?? 0)
    XCTAssertEqual(after["replies"] as? Int, 0)
    XCTAssertEqual(after["desktopOpens"] as? Int, 0)
    XCTAssertEqual(composer.value as? String, "preserve-draft-during-silent-events")
    XCTAssertFalse(app.alerts.firstMatch.exists)
    shot("silent-events-history-recovered-with-draft")
  }
  @MainActor func testTLSConnectionFailureShowsRecoveryGuidance() async throws {
    // The read-lifecycle fixture serves HTTP. Asking it for HTTPS produces a
    // real URLSession handshake failure without touching a saved computer.
    guard let _ = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/results")!) else {
      throw XCTSkip("Run scripts/read-cancellation-ios-fixture.mjs")
    }
    app.buttons["open-sidebar"].tap()
    app.buttons["client-switch"].tap()
    app.buttons["add-client"].tap()
    selectConnectionMethod("手动连接", agent: "codex")
    replaceText(app.textFields["client-address"], with: "https://127.0.0.1:8802")
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.buttons["connect-client"].tap()
    let message = app.staticTexts["client-form-error"]
    XCTAssertTrue(message.waitForExistence(timeout: 40))
    XCTAssertTrue(message.label.contains("安全连接未能建立"), message.label)
    XCTAssertTrue(message.label.contains("检查连接设置"), message.label)
    XCTAssertFalse(message.label.contains("TLS"))
    XCTAssertFalse(message.label.contains("127.0.0.1"))
    shot("connection-failure-friendly-guidance")
  }

  @MainActor func testReadCancellationOnBackgroundAndRealFailure() async throws {
    try await connectReadLifecycleFixture()
    try await readLifecycleState("disable-models")
    try await waitForReadLifecycleCounter("modelCancelled", atLeast: 1)
    XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 3))
    composer.tap(); typeComposerText("keep-read-lifecycle-draft")
    try await readLifecycleState("delay-history")
    try await waitForReadLifecycleCounter("historyDelayed", atLeast: 1)
    XCUIDevice.shared.press(.home)
    try await waitForReadLifecycleCounter("historyCancelled", atLeast: 1)
    app.activate()
    XCTAssertTrue(app.staticTexts["READ_LIFECYCLE_HISTORY"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 3), "Background cancellation is expected lifecycle cleanup")
    XCTAssertEqual(composer.value as? String, "keep-read-lifecycle-draft")
    try await readLifecycleState("fail-history")
    XCTAssertTrue(app.alerts["无法完成操作"].waitForExistence(timeout: 10), "Genuine current-client failures must remain visible")
    XCTAssertTrue(app.alerts.staticTexts["Fixture genuine history failure"].exists)
    app.alerts.buttons["好"].tap()
    XCTAssertEqual(composer.value as? String, "keep-read-lifecycle-draft")
    shot("read-lifecycle-real-failure-preserves-draft")
  }

  @MainActor func testHistoryReadWithoutDesktopNavigation() async throws {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/reset?desktop=1")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    try await readLifecycleState("disable-models")
    connectLive(try JSONDecoder().decode(LiveFixture.self, from: data), supportsCreation: false)
    let history = app.staticTexts["READ_LIFECYCLE_HISTORY"]
    XCTAssertTrue(history.waitForExistence(timeout: 15))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertFalse(app.staticTexts["readonly-note"].exists)
    XCTAssertFalse(app.staticTexts["发送时自动连接 Codex。"].exists)
    composer.tap(); typeComposerText("history-draft-preserved")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    try await waitForReadLifecycleCounter("historyReads", atLeast: 2)
    var counters = try await readLifecycleCounters()
    XCTAssertEqual(counters["desktopOpens"] as? Int, 0)
    XCTAssertTrue(history.exists)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    XCTAssertFalse(app.buttons["connect-desktop-thread"].exists)
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.alerts["无法完成操作"].waitForExistence(timeout: 10))
    app.alerts.buttons["好"].tap()
    XCTAssertTrue(history.exists)
    XCTAssertEqual(composer.value as? String, "history-draft-preserved")
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    try await waitForReadLifecycleCounter("historyReads", atLeast: 3)
    counters = try await readLifecycleCounters()
    XCTAssertEqual(counters["desktopOpens"] as? Int, 1, "Refreshing must not retry an uncertain desktop opening")
    XCTAssertEqual(counters["replies"] as? Int, 0, "A failed connection must not submit the input")
    XCTAssertFalse(app.alerts.firstMatch.exists)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(history.waitForExistence(timeout: 15))
    XCTAssertEqual(composer.value as? String, "history-draft-preserved")
    counters = try await readLifecycleCounters()
    XCTAssertEqual(counters["desktopOpens"] as? Int, 1, "Restoring the draft must not navigate the desktop")
    try await readLifecycleState("open-success")
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: app.buttons["send-message"])
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed)
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts["AUTOMATIC_CONNECTION_REPLY"].waitForExistence(timeout: 15))
    counters = try await readLifecycleCounters()
    XCTAssertEqual(counters["desktopOpens"] as? Int, 1, "The next Send must query the saved connection ID")
    XCTAssertEqual(counters["desktopOpenQueries"] as? Int, 2)
    XCTAssertEqual(counters["replies"] as? Int, 1)
    XCTAssertTrue(composer.value as? String == "" || composer.value as? String == "输入消息，长按语音输入")
    XCTAssertTrue(history.exists)
    shot("history-readable-and-send-connects-once")
  }

  @MainActor func testHistorySendWaitsForLateDesktopOwner() async throws {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802/reset?desktop=1&late=1")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    try await readLifecycleState("disable-models")
    connectLive(try JSONDecoder().decode(LiveFixture.self, from: data), supportsCreation: false)
    XCTAssertTrue(app.staticTexts["READ_LIFECYCLE_HISTORY"].waitForExistence(timeout: 15))
    composer.tap(); typeComposerText("wait-for-late-original-owner")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["AUTOMATIC_CONNECTION_REPLY"].waitForExistence(timeout: 20))
    XCTAssertFalse(app.alerts.firstMatch.exists, "A late owner must finish the same Send without an extra connection step")
    let counters = try await readLifecycleCounters()
    XCTAssertEqual(counters["desktopOpens"] as? Int, 1)
    XCTAssertEqual(counters["desktopOpenQueries"] as? Int, 2)
    XCTAssertEqual(counters["replies"] as? Int, 1)
    XCTAssertTrue(composer.value as? String == "" || composer.value as? String == "输入消息，长按语音输入")
    shot("late-desktop-owner-single-send")
  }

  @MainActor func testPhysicalHistoryReadAndAutomaticReply() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the paired user's physical iPhone and real desktop Host")
    #else
    let env = ProcessInfo.processInfo.environment
    let hostId = try XCTUnwrap(env["HISTORY_ACCEPTANCE_HOST_ID"])
    let threadId = try XCTUnwrap(env["HISTORY_ACCEPTANCE_THREAD_ID"])
    let title = try XCTUnwrap(env["HISTORY_ACCEPTANCE_TITLE"])
    let marker = try XCTUnwrap(env["HISTORY_ACCEPTANCE_MARKER"])
    switchClient(hostId)
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(title); search.typeText("\n")
    let row = app.buttons["recent-thread-" + threadId]
    XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
    let history = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
    XCTAssertTrue(history.waitForExistence(timeout: 60), "Real stored history must load without opening the desktop chat")
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertTrue((app.buttons["client-switch"].value as? String ?? "").contains("发送时自动连接"))
    XCTAssertFalse(app.buttons["connect-desktop-thread"].exists)
    shot("physical-history-read-without-desktop-opening")
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    XCTAssertFalse(app.alerts.firstMatch.waitForExistence(timeout: 5))
    XCTAssertTrue(history.exists)
    XCTAssertTrue((app.buttons["client-switch"].value as? String ?? "").contains("发送时自动连接"))
    if let reply = env["HISTORY_ACCEPTANCE_REPLY"] {
      let original = composer.value as? String ?? ""
      XCTAssertTrue(original.isEmpty || original == "输入消息，长按语音输入" || original == "输入消息…", "Preserve any existing user draft")
      focusLiveComposer()
      typeComposerText("仅原样回复 \(reply)，不要使用工具。")
      sendLiveComposer()
      XCTAssertTrue(app.staticTexts[reply].waitForExistence(timeout: 180))
      XCTAssertTrue(history.exists)
      XCTAssertFalse(app.alerts.firstMatch.exists)
      shot("physical-history-send-automatically-connects")
    }
    #endif
  }

  @MainActor func testPhysicalHistoryReadExistingModelReply() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the paired user's physical iPhone and a dedicated desktop acceptance chat")
    #else
    let env = ProcessInfo.processInfo.environment
    let hostId = try XCTUnwrap(env["HISTORY_ACCEPTANCE_HOST_ID"])
    let threadId = try XCTUnwrap(env["HISTORY_ACCEPTANCE_THREAD_ID"])
    let title = try XCTUnwrap(env["HISTORY_ACCEPTANCE_TITLE"])
    let reply = try XCTUnwrap(env["HISTORY_ACCEPTANCE_REPLY"])
    switchClient(hostId)
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(title); search.typeText("\n")
    let row = app.buttons["recent-thread-" + threadId]
    XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
    XCTAssertFalse(app.buttons["connect-desktop-thread"].exists)
    let original = composer.value as? String ?? ""
    XCTAssertTrue(original.isEmpty || original == "输入消息，长按语音输入" || original == "输入消息…", "Preserve any existing user draft")
    focusLiveComposer()
    typeComposerText("真实手机历史优化回复回归：仅原样回复 \(reply)，不要使用工具。")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts[reply].waitForExistence(timeout: 180))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    shot("physical-history-existing-desktop-model-reply")
    #endif
  }

  @MainActor func testOriginalDesktopReadLifecycle() async throws {
    guard let (data, response) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8803")!) else {
      throw XCTSkip("Run scripts/read-lifecycle-desktop-ios-acceptance.mjs")
    }
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    func waitConnected() {
      let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 30), .completed)
    }
    waitConnected()
    let marker = "READ_LIFECYCLE_REAL_" + fixture.nonce
    composer.tap(); typeComposerText(marker + "_draft")
    setDemoEnabledFromSettings(true)
    for _ in 0..<3 {
      switchClient(fixture.hostId)
      XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 2))
      XCTAssertEqual(composer.value as? String, marker + "_draft")
      XCUIDevice.shared.press(.home)
      app.activate()
      waitConnected()
      XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 2))
      XCTAssertEqual(composer.value as? String, marker + "_draft")
      switchClient("demo-mac")
      XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 2))
    }
    switchClient(fixture.hostId)
    setDemoEnabledFromSettings(false)
    replaceText(composer, with: "专用页面恢复验收：仅原样回复 " + marker + "，不要使用工具。")
    let send = app.buttons["send-message"]
    let writable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: send)
    XCTAssertEqual(XCTWaiter.wait(for: [writable], timeout: 30), .completed)
    send.tap()
    waitForDesktopAnswer(marker)
    app.terminate(); app.launchChineseTest()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 30))
    XCTAssertFalse(app.alerts["无法完成操作"].waitForExistence(timeout: 3))
    shot("read-lifecycle-real-desktop-restored")
  }

  @MainActor private func armSidebarRefresh(_ mode: String) async throws -> Int {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8797/arm?mode=" + mode)!)
    let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Int])
    return try XCTUnwrap(counters["historyCount"])
  }

  @MainActor private func pullSidebarRefresh() {
    let list = app.scrollViews["sidebar-list"]
    XCTAssertTrue(list.waitForExistence(timeout: 5))
    let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15))
    let end = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85))
    start.press(forDuration: 0.1, thenDragTo: end)
    // XCTest waits for the refresh to settle after the drag. The HTTP harness
    // captures the native spinner while the response is still pending.
  }

  @MainActor func testSidebarPullRefreshUpdatesListAndRetainsDraft() async throws {
    let fixture = try await sidebarRefreshFixture()
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_HISTORY"].waitForExistence(timeout: 10))
    composer.tap()
    typeComposerText("refresh-keeps-this-draft")
    app.buttons["open-sidebar"].tap()
    let project = app.buttons["project-refresh-project"]
    project.tap()
    XCTAssertEqual(project.value as? String, "已展开")
    let historyCount = try await armSidebarRefresh("success")
    pullSidebarRefresh()
    let updated = app.buttons["recent-thread-refresh-thread"]
    let refreshed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == 'Fixture refreshed conversation'"), object: updated)
    XCTAssertEqual(XCTWaiter.wait(for: [refreshed], timeout: 10), .completed)
    XCTAssertTrue(app.buttons["recent-thread-refresh-added"].exists)
    XCTAssertEqual(project.value as? String, "已展开")
    let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.scrollViews["sidebar-list"].activityIndicators.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 5), .completed)
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8797/results")!)
    let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Int])
    XCTAssertEqual(counters["refreshCount"], 1)
    XCTAssertEqual(counters["historyCount"], historyCount)
    app.buttons["close-sidebar"].tap()
    XCTAssertEqual(composer.value as? String, "refresh-keeps-this-draft")
  }

  @MainActor func testSidebarPullRefreshFailurePreservesCachedList() async throws {
    let fixture = try await sidebarRefreshFixture()
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_HISTORY"].waitForExistence(timeout: 10))
    app.buttons["open-sidebar"].tap()
    _ = try await armSidebarRefresh("error")
    pullSidebarRefresh()
    XCTAssertTrue(app.alerts["无法完成操作"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.alerts.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "刷新失败，请重试")).firstMatch.exists)
    shot("sidebar-refresh-error-feedback")
    app.alerts.buttons["好"].tap()
    XCTAssertEqual(app.buttons["recent-thread-refresh-thread"].label, "Fixture original conversation")
    XCTAssertFalse(app.buttons["recent-thread-refresh-added"].exists)
    // Automatic reconnect can remain active after a failed explicit refresh.
    // Only the refresh control inside this list must have finished.
    let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.scrollViews["sidebar-list"].activityIndicators.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 5), .completed)
    shot("sidebar-refresh-error-cache-retained")
  }

  @MainActor func testSidebarRefreshVisualHitRegionsStayAligned() async throws {
    // XCTest normally waits for an active refresh animation before injecting
    // touches. Only this test skips those waits; the App receives real taps.
    guard app.responds(to: NSSelectorFromString("setCurrentInteractionOptions:")) else {
      throw XCTSkip("This Xcode cannot inject touches during a refresh animation")
    }
    app.setValue(NSNumber(value: 3), forKey: "currentInteractionOptions")
    defer { app.setValue(NSNumber(value: 0), forKey: "currentInteractionOptions") }
    app.buttons["open-sidebar"].tap()
    pullSidebarRefresh()
    shot("sidebar-refresh-after-pull")
    let progress = app.scrollViews["sidebar-list"]
    let refreshing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == 'refreshing'"), object: progress)
    XCTAssertEqual(XCTWaiter.wait(for: [refreshing], timeout: 3), .completed)
    // Let the ordinary release bounce finish. The refresh remains pending.
    try await Task.sleep(for: .milliseconds(350))
    func tapVisible(_ text: String, button: XCUIElement) throws {
      let request = VNRecognizeTextRequest()
      request.recognitionLanguages = ["zh-Hans", "en-US"]
      request.recognitionLevel = .accurate
      let screenshot = XCUIScreen.main.screenshot()
      try VNImageRequestHandler(cgImage: try XCTUnwrap(screenshot.image.cgImage), options: [:]).perform([request])
      let normalized = text.replacingOccurrences(of: " ", with: "")
      let word = try XCTUnwrap(request.results?.first {
        $0.topCandidates(1).first?.string.replacingOccurrences(of: " ", with: "").contains(normalized) == true
      }, "Find the actual visible label in screenshot pixels")
      let point = CGPoint(x: word.boundingBox.midX * app.frame.width,
        y: (1 - word.boundingBox.midY) * app.frame.height)
      print("SIDEBAR_VISUAL_HIT", text, "visible", point, "hitRegion", button.frame)
      XCTAssertTrue(button.frame.insetBy(dx: -2, dy: -2).contains(point),
        "Visible text and the native hit region must agree during refresh")
      app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: point.x, dy: point.y)).tap()
    }
    let recent = app.buttons["sidebar-recent-toggle"]
    shot("sidebar-refresh-visual-hit-before-clicks")
    try tapVisible("最近", button: recent)
    XCTAssertEqual(recent.value as? String, "已折叠")
    try tapVisible("最近", button: recent)
    XCTAssertEqual(recent.value as? String, "已展开")
    XCTAssertEqual(progress.value as? String, "refreshing")
    try tapVisible("新聊天", button: app.buttons["sidebar-new"])
    XCTAssertTrue(app.buttons["choose-project-mobile"].waitForExistence(timeout: 3))
    app.buttons["取消"].tap()
    app.buttons["sidebar-client-switch"].tap()
    XCTAssertTrue(app.buttons["client-demo-mac"].waitForExistence(timeout: 3))
    app.buttons["完成"].tap()
    XCTAssertEqual(progress.value as? String, "refreshing")
    try tapVisible("优化查询性能", button: app.buttons["recent-thread-query"])
    XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 3))
    XCTAssertTrue(app.buttons["client-switch"].label.contains("api-service"))
    shot("sidebar-refresh-visual-hit-selected-correct-chat")
  }

  @MainActor func testSidebarStatePriorityPreservesPhoneUnread() async throws {
    let base = "http://127.0.0.1:8797"
    let (data, _) = try await URLSession.shared.data(from: URL(string: base + "/unread-reset")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_ANCHOR"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let recent = app.buttons["recent-thread-" + fixture.threadId]
    let project = app.buttons["thread-" + fixture.threadId]
    if !project.exists { app.buttons["project-refresh-project"].tap() }
    func expect(_ value: String) {
      for row in [recent, project] {
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        let matches = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [matches], timeout: 15), .completed)
      }
    }
    func state(_ value: String) async throws {
      var request = URLRequest(url: URL(string: base + "/unread-state?value=" + value)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    expect("未读")
    shot("sidebar-completed-unread-light")
    try await state("read"); expect("未读")
    try await state("running"); expect("执行中")
    try await state("approval"); expect("等待审批")
    try await state("unread"); expect("未读")
    try await state("legacy"); expect("未读")
    try await state("unread"); expect("未读")
    app.buttons["open-settings"].tap()
    let appearance = app.descendants(matching: .any)["appearance-picker"].firstMatch
    XCTAssertTrue(appearance.waitForExistence(timeout: 10))
    appearance.tap()
    app.buttons["深色"].tap()
    app.buttons["完成"].tap()
    let sidebarReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["open-sidebar"])
    XCTAssertEqual(XCTWaiter.wait(for: [sidebarReady], timeout: 10), .completed)
    app.buttons["open-sidebar"].tap()
    expect("未读")
    shot("sidebar-completed-unread-dark")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap()
    expect("未读")
  }

  @MainActor func testSidebarAsyncQuestionStatus() async throws {
    let base = "http://127.0.0.1:8797"
    let (data, _) = try await URLSession.shared.data(from: URL(string: base + "/async-status-reset")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    app.buttons["open-sidebar"].tap()
    let recent = app.buttons["recent-thread-" + fixture.threadId]
    let project = app.buttons["thread-" + fixture.threadId]
    if !project.exists { app.buttons["project-refresh-project"].tap() }
    func expect(_ value: String) {
      for row in [recent, project] {
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: row)], timeout: 15), .completed)
      }
    }
    func state(_ value: String) async throws {
      var request = URLRequest(url: URL(string: base + "/unread-state?value=" + value)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    expect("")
    try await state("async-running"); expect("执行中，2 个问题待回答")
    shot("sidebar-running-async-questions")
    try await state("approval-and-async"); expect("等待审批，1 个问题待回答")
    shot("sidebar-approval-and-async-question")
    try await state("approval"); expect("等待审批")
    try await state("async-idle"); expect("2 个问题待回答")
    shot("sidebar-idle-async-questions")
    try await state("async-unknown"); expect("2 个问题待回答")
    shot("sidebar-async-questions-owner-unavailable")
    try await state("async-idle"); expect("2 个问题待回答")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap()
    if !project.exists { app.buttons["project-refresh-project"].tap() }
    expect("2 个问题待回答")
    try await state("read"); expect("")
    try await state("legacy"); expect("")
    shot("sidebar-async-questions-cleared")
  }

  @MainActor func testMobileReadingClearsAndSurvivesRefresh() async throws {
    let base = "http://127.0.0.1:8804"
    let (bytes, _) = try await URLSession.shared.data(from: URL(string: base + "/reset")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: bytes)
    func control(_ path: String) async throws -> [String: Any] {
      var request = URLRequest(url: URL(string: base + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    func expect(_ value: String) {
      for prefix in ["recent-thread-", "thread-"] {
        let row = app.buttons[prefix + fixture.threadId]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        let match = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [match], timeout: 15), .completed)
      }
      expectMobileUnread(value.isEmpty ? 0 : 1)
    }
    func openReadingThread(_ id: String) {
      let row = app.buttons["recent-thread-" + id]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: row)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
      for _ in 0..<2 {
        row.tap()
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["close-sidebar"])
        if XCTWaiter.wait(for: [closed], timeout: 5) == .completed { return }
      }
      XCTFail("Reading fixture chat was not opened after the refresh gesture")
    }
    func openReadingSidebar() {
      let button = app.buttons["open-sidebar"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: button)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
      // Long scroll content makes XCTest's default point pick the rounded corner.
      button.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 10))
    }
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["READING_ANCHOR"].waitForExistence(timeout: 15))
    openReadingSidebar()
    if !app.buttons["thread-" + fixture.threadId].exists { app.buttons["project-reading-project"].tap() }
    expect("未读")
    _ = try await control("/desktop-read?unread=0")
    pullSidebarRefresh(); expect("未读")
    _ = try await control("/desktop-read?unread=1")
    openReadingThread(fixture.threadId)
    XCTAssertTrue(app.staticTexts["MOBILE_READ_1"].waitForExistence(timeout: 15))
    openReadingSidebar(); expect("")
    shot("mobile-reading-cleared")
    pullSidebarRefresh(); expect("")
    openReadingThread("reading-anchor")
    _ = try await control("/next")
    openReadingSidebar(); expect("未读")
    openReadingThread(fixture.threadId)
    XCTAssertTrue(app.staticTexts["MOBILE_READ_2"].waitForExistence(timeout: 15))
    let list = app.scrollViews["message-list"]
    list.swipeDown(); list.swipeDown(); list.swipeDown()
    XCTAssertTrue(app.buttons["跳转最新消息"].waitForExistence(timeout: 5))
    _ = try await control("/next")
    openReadingSidebar(); expect("未读")
    let results = try await control("/results")
    XCTAssertEqual(results["acknowledgements"] as? Int, 2, "A hidden new reply must stay unread")
    XCTAssertEqual(results["desktopUnread"] as? Bool, true)
    _ = try await control("/ack-failure?enabled=1")
    openReadingThread(fixture.threadId)
    XCTAssertTrue(app.buttons["跳转最新消息"].waitForExistence(timeout: 5))
    app.buttons["跳转最新消息"].tap()
    XCTAssertTrue(app.staticTexts["MOBILE_READ_3"].waitForExistence(timeout: 15))
    openReadingSidebar(); expect("")
    var attempts = 0
    for _ in 0..<20 {
      attempts = (try await control("/results"))["acknowledgementAttempts"] as? Int ?? 0
      if attempts >= 3 { break }
      try await Task.sleep(for: .milliseconds(250))
    }
    XCTAssertGreaterThanOrEqual(attempts, 3)
    app.terminate()
    _ = try await control("/ack-failure?enabled=0")
    app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    openReadingSidebar(); expect("")
    pullSidebarRefresh(); expect("")
    shot("mobile-reading-restored")
    let priorAccountReads = (try await control("/results"))["acknowledgements"] as? Int ?? 0
    _ = try await control("/account?subject=account-b")
    // Codex reading identity follows the machine/runtime, even when its login
    // account metadata changes. The same visible reply must remain read.
    pullSidebarRefresh(); expect("")
    openReadingThread(fixture.threadId)
    let currentReply = app.staticTexts["MOBILE_READ_3"]
    XCTAssertTrue(currentReply.waitForExistence(timeout: 15))
    // Codex account metadata changes preserve this machine's reader and viewport.
    // The same visible reply must not gain a new account-scoped unread mark.
    let latest = app.buttons["跳转最新消息"]
    if latest.exists { latest.tap() }
    let replyVisible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: currentReply)
    XCTAssertEqual(XCTWaiter.wait(for: [replyVisible], timeout: 15), .completed)
    shot("mobile-reading-new-account-visible")
    app.scrollViews["message-list"].swipeUp()
    XCTAssertTrue(currentReply.isHittable)
    shot("mobile-reading-new-account-at-end")
    openReadingSidebar(); pullSidebarRefresh(); expect("")
    let newAccountReads = (try await control("/results"))["acknowledgements"] as? Int ?? 0
    XCTAssertEqual(newAccountReads, priorAccountReads,
      "Codex account metadata changes must preserve the original machine reader")
    _ = try await control("/account?subject=account-a")
    pullSidebarRefresh(); expect("")
  }

  @MainActor func testMobileReadingWaitsForMatchingBodyAndShortChatLayout() async throws {
    let base = "http://127.0.0.1:8804"
    let (bytes, _) = try await URLSession.shared.data(from: URL(string: base + "/reset?compact=1")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: bytes)
    func control(_ path: String) async throws -> [String: Any] {
      var request = URLRequest(url: URL(string: base + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["READING_ANCHOR"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap()
    app.buttons["recent-thread-" + fixture.threadId].tap()
    XCTAssertTrue(app.staticTexts["MOBILE_READ_1"].waitForExistence(timeout: 15))
    expectMobileUnread(0)
    _ = try await control("/next?hold=1")
    expectMobileUnread(1)
    let waiting = try await control("/results")
    XCTAssertEqual(waiting["acknowledgements"] as? Int, 1,
      "A new summary must not mark the older displayed body read")
    _ = try await control("/release-body")
    XCTAssertTrue(app.staticTexts["MOBILE_READ_2"].waitForExistence(timeout: 15))
    expectMobileUnread(0)
    let read = try await control("/results")
    XCTAssertEqual(read["acknowledgements"] as? Int, 2)
    shot("mobile-short-reply-body-catches-up-read")
    app.buttons["open-sidebar"].tap()
    let row = app.buttons["recent-thread-" + fixture.threadId]
    XCTAssertEqual(row.value as? String, "")
    pullSidebarRefresh()
    XCTAssertEqual(row.value as? String, "")
    let refreshed = try await control("/results")
    XCTAssertEqual(refreshed["desktopUnread"] as? Bool, true)
  }

  @MainActor func testMobileReadingLegacyConnectionClearsDot() async throws {
    let base = "http://127.0.0.1:8804"
    let (bytes, _) = try await URLSession.shared.data(from: URL(string: base + "/reset?compact=1&legacy=1")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: bytes)
    func control(_ path: String) async throws -> [String: Any] {
      var request = URLRequest(url: URL(string: base + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, _) = try await URLSession.shared.data(for: request)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["READING_ANCHOR"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap(); expectMobileUnread(1)
    app.buttons["recent-thread-" + fixture.threadId].tap()
    XCTAssertTrue(app.staticTexts["MOBILE_READ_1"].waitForExistence(timeout: 15)); expectMobileUnread(0)
    app.buttons["open-sidebar"].tap(); pullSidebarRefresh(); expectMobileUnread(0)
    app.buttons["recent-thread-reading-anchor"].tap()
    _ = try await control("/next")
    app.buttons["open-sidebar"].tap()
    let row = app.buttons["recent-thread-" + fixture.threadId]
    row.tap()
    XCTAssertTrue(app.staticTexts["MOBILE_READ_2"].waitForExistence(timeout: 15)); expectMobileUnread(0)
    app.buttons["open-sidebar"].tap(); XCTAssertEqual(row.value as? String, "")
    shot("mobile-legacy-connection-read-dot-cleared")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    app.buttons["open-sidebar"].tap(); pullSidebarRefresh(); expectMobileUnread(0)
    let results = try await control("/results")
    XCTAssertEqual(results["acknowledgements"] as? Int, 0)
    XCTAssertEqual(results["desktopUnread"] as? Bool, true)
  }

  @MainActor func testOriginalDesktopMobileReadingIsolation() async throws {
    let base = "http://127.0.0.1:8805"
    let (data, _) = try await URLSession.shared.data(from: URL(string: base)!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    func control(_ path: String) async throws -> [String: Any] {
      var request = URLRequest(url: URL(string: base + path)!); request.timeoutInterval = 240
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (bytes, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    if app.buttons["onboarding-add"].waitForExistence(timeout: 3) { app.buttons["onboarding-add"].tap() }
    else { app.buttons["client-switch"].tap(); app.buttons["add-client"].tap() }
    selectConnectionMethod("手动连接", agent: "codex")
    let address = app.textFields["client-address"]
    XCTAssertTrue(address.waitForExistence(timeout: 10)); replaceText(address, with: fixture.endpoint)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    app.secureTextFields["client-token"].tap(); app.secureTextFields["client-token"].typeText(fixture.token)
    if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    let connect = app.buttons["connect-client"]
    let connectReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: connect)
    XCTAssertEqual(XCTWaiter.wait(for: [connectReady], timeout: 15), .completed)
    connect.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.secureTextFields["client-token"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 30), .completed)
    XCTAssertTrue(app.staticTexts[fixture.nonce + "_1"].waitForExistence(timeout: 90))
    enableDEVBadgePermission()
    func expect(_ value: String) {
      let row = app.buttons["recent-thread-" + fixture.threadId]
      XCTAssertTrue(row.waitForExistence(timeout: 10))
      let match = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", value), object: row)
      XCTAssertEqual(XCTWaiter.wait(for: [match], timeout: 30), .completed)
      expectMobileUnread(value.isEmpty ? 0 : 1)
    }
    app.buttons["open-sidebar"].tap(); expect("")
    pullSidebarRefresh(); expect("")
    let before = try await control("/results")
    XCTAssertGreaterThanOrEqual(before["acknowledgements"] as? Int ?? 0, 1)
    _ = try await control("/next"); expect("未读")
    shot("real-desktop-new-reply-unread")
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    print("MOBILE_UNREAD_SYSTEM_ICON", springboard.icons.matching(identifier: "Agent Hub").firstMatch.value as Any)
    shot("real-desktop-system-unread-badge", fullScreen: true)
    app.activate()
    app.buttons["recent-thread-" + fixture.threadId].tap()
    let sidebarClosed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["sidebar-client-switch"])
    if XCTWaiter.wait(for: [sidebarClosed], timeout: 5) != .completed {
      app.buttons["close-sidebar"].tap()
    }
    XCTAssertTrue(app.staticTexts[fixture.nonce + "_2"].waitForExistence(timeout: 60))
    expectMobileUnread(0)
    app.buttons["open-sidebar"].tap(); expect("")
    shot("real-desktop-mobile-read")
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30))
    app.buttons["open-sidebar"].tap(); expect("")
    pullSidebarRefresh(); expect("")
  }

  @MainActor private func expectMobileUnread(_ count: Int) {
    let selector = app.buttons["sidebar-client-switch"].exists ? app.buttons["sidebar-client-switch"] : app.buttons["client-switch"]
    let expected = "手机共\(count)个未读对话"
    let match = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", expected), object: selector)
    XCTAssertEqual(XCTWaiter.wait(for: [match], timeout: 25), .completed, "The app icon count and phone reading ledger must agree")
  }
  @MainActor func testConnectionMethodDropdownShowsOnlySelectedForm() {
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    XCTAssertTrue(app.buttons["codex-connection-method"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.textFields["client-address"].exists)
    XCTAssertFalse(app.textFields["codex-qr-description"].exists)
    selectConnectionMethod("扫码连接", agent: "codex")
    XCTAssertTrue(app.textFields["codex-qr-description"].exists)
    XCTAssertFalse(app.textFields["client-address"].exists)
    shot("client-codex-qr-menu")
    selectConnectionMethod("手动连接", agent: "codex")
    XCTAssertTrue(app.textFields["client-address"].exists)
    XCTAssertFalse(app.textFields["codex-qr-description"].exists)
    shot("client-codex-manual-menu")
    selectConnectionMethod("自动发现", agent: "codex")
    XCTAssertFalse(app.textFields["client-address"].exists)
    app.buttons["EasyWork"].firstMatch.tap()
    XCTAssertTrue(app.buttons["easywork-connection-method"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.textFields["easywork-manual-address"].exists)
    selectConnectionMethod("扫码连接", agent: "easywork")
    XCTAssertTrue(app.textFields["easywork-qr-description"].exists)
    XCTAssertFalse(app.textFields["easywork-manual-address"].exists)
    shot("client-easywork-qr-menu")
    selectConnectionMethod("手动连接", agent: "easywork")
    XCTAssertTrue(app.textFields["easywork-manual-address"].exists)
    XCTAssertFalse(app.textFields["easywork-qr-description"].exists)
    selectConnectionMethod("通过中继连接", agent: "easywork")
    XCTAssertTrue(app.textFields["easywork-relay-url"].exists)
    XCTAssertFalse(app.textFields["easywork-manual-address"].exists)
    shot("client-easywork-relay-menu")
    selectConnectionMethod("自动发现", agent: "easywork")
    XCTAssertFalse(app.textFields["easywork-relay-url"].exists)
    XCTAssertFalse(app.textFields["easywork-qr-description"].exists)
  }
  @MainActor func testMobileUnreadBadgeAggregatesEveryComputer() async throws {
    let base = ProcessInfo.processInfo.environment["UNREAD_FIXTURE_URL"] ?? "http://127.0.0.1:8806"
    let (data, _) = try await URLSession.shared.data(from: URL(string: base + "/reset")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    func control(_ path: String) async throws {
      var request = URLRequest(url: URL(string: base + path)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
    func select(_ id: String) {
      let selector = app.buttons["sidebar-client-switch"].exists ? app.buttons["sidebar-client-switch"] : app.buttons["client-switch"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: selector)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 25), .completed)
      selector.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let row = app.buttons["client-" + id]
      XCTAssertTrue(row.waitForExistence(timeout: 10))
      for _ in 0..<6 {
        if row.isHittable && row.frame.maxY <= app.frame.maxY - 30 { break }
        let list = app.collectionViews.firstMatch
        if list.exists { list.swipeUp() } else { app.swipeUp() }
      }
      let visible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: row)
      XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 10), .completed)
      XCTAssertLessThanOrEqual(row.frame.maxY, app.frame.maxY - 30)
      row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10))
      let name = id == fixture.hostId ? "Fixture A" : "Fixture B"
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", name), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 15), .completed)
    }
    func expectClientCounts(_ first: Int, _ second: Int) {
      let selector = app.buttons["sidebar-client-switch"].exists ? app.buttons["sidebar-client-switch"] : app.buttons["client-switch"]
      selector.tap()
      for (id, count) in [(fixture.hostId, first), (fixture.secondHostId, second)] {
        let row = app.buttons["client-" + id]
        let match = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value BEGINSWITH %@", "\(count)个未读对话"), object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [match], timeout: 15), .completed, app.debugDescription)
      }
      shot("client-unread-counts-\(first)-\(second)")
      app.buttons["完成"].tap()
    }
    func readTarget(_ hostId: String) {
      if !app.buttons["sidebar-client-switch"].exists { app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      let row = app.buttons["recent-thread-" + fixture.threadId]
      XCTAssertTrue(row.waitForExistence(timeout: 15)); row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "BADGE_REPLY_" + hostId + "_")).firstMatch.waitForExistence(timeout: 15))
    }
    connectLive(fixture, supportsCreation: false, nickname: "Fixture A"); expectMobileUnread(1)
    connectLive(fixture, second: true, supportsCreation: false, nickname: "Fixture B"); expectMobileUnread(2)
    expectClientCounts(1, 1)
    // A fresh launch has no network connection yet; reconnect without an error modal.
    composer.tap(); typeComposerText("retained-connection-draft")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    try await control("/connection?online=0")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    XCTAssertTrue(app.otherElements["connection-status"].waitForExistence(timeout: 10)
      || app.descendants(matching: .any).matching(identifier: "connection-status").firstMatch.exists)
    XCTAssertFalse(app.alerts.firstMatch.waitForExistence(timeout: 4), app.debugDescription)
    XCTAssertEqual(composer.value as? String, "retained-connection-draft")
    expectMobileUnread(2); expectClientCounts(1, 1)
    shot("client-cold-launch-reconnecting-no-alert")
    try await control("/connection?online=1")
    let recovered = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS '实时连接' OR value CONTAINS '已连接'"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [recovered], timeout: 45), .completed, app.debugDescription)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    // Saved clients remain in discovery even when their old entry has no Bonjour record.
    app.buttons["client-switch"].tap()
    let add = app.buttons["add-client"]
    for _ in 0..<6 { if add.isHittable { break }; app.swipeUp() }
    XCTAssertTrue(add.waitForExistence(timeout: 10)); add.tap()
    for id in [fixture.hostId, fixture.secondHostId] {
      let row = app.buttons["codex-discovered-" + id]
      XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
      XCTAssertTrue(row.label.contains("已连接"), app.debugDescription)
    }
    shot("client-discovery-merges-saved-connected-hosts")
    app.buttons["cancel-client-form"].tap(); app.buttons["完成"].tap()
    _ = try await control("/desktop-read"); expectMobileUnread(2)
    select(fixture.hostId); expectMobileUnread(2)
    readTarget(fixture.hostId); expectMobileUnread(1)
    expectClientCounts(0, 1)
    select(fixture.secondHostId); expectMobileUnread(1)
    readTarget(fixture.secondHostId); expectMobileUnread(0)
    expectClientCounts(0, 0)
    enableDEVBadgePermission()
    if app.buttons["sidebar-client-switch"].exists { app.buttons["close-sidebar"].tap() }
    _ = try await control("/next?host=a"); expectMobileUnread(1)
    _ = try await control("/next?host=a"); expectMobileUnread(1)
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20)); expectMobileUnread(1)
    XCUIDevice.shared.press(.home)
    try await Task.sleep(for: .seconds(2))
    shot("multi-computer-system-unread-badge", fullScreen: true); app.activate()
    select(fixture.hostId); readTarget(fixture.hostId); expectMobileUnread(0)
    select(fixture.secondHostId)
    _ = try await control("/approval"); expectMobileUnread(1)
    select(fixture.hostId); readTarget(fixture.hostId)
    XCTAssertTrue(app.buttons["approval-accept"].waitForExistence(timeout: 15))
    expectMobileUnread(0)
    XCTAssertTrue(app.buttons["approval-accept"].exists, "Reading a request must preserve its pending action")
    shot("approval-read-unread-cleared-action-retained")
  }
  @MainActor private func enableDEVBadgePermission() {
    if !app.buttons["sidebar-client-switch"].exists { app.buttons["open-sidebar"].tap() }
    app.buttons["open-settings"].tap(); app.buttons["通知"].tap()
    let toggle = app.switches["notifications-enabled"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 10))
    if toggle.value as? String == "0" {
      toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
      let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
      if springboard.alerts.firstMatch.waitForExistence(timeout: 4) {
        let allow = springboard.buttons.matching(NSPredicate(format: "label == 'Allow' OR label == '允许'")).firstMatch
        XCTAssertTrue(allow.exists); allow.tap()
      }
    }
    let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: toggle)
    XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 10), .completed)
    // Dismiss a simulator registration error before navigating out of settings.
    if app.alerts["通知"].waitForExistence(timeout: 4) { app.alerts["通知"].buttons["好"].tap() }
    let back = app.navigationBars["通知"].buttons.firstMatch
    if back.exists && back.isHittable { back.tap() }
    let done = app.buttons["完成"].firstMatch
    if done.waitForExistence(timeout: 3) && done.isHittable { done.tap() }
  }

  @MainActor func testPhysicalAgentHubNotificationRegistrationUnreadBadge() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the existing signed Agent Hub on a physical iPhone")
    #else
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 25))
    try await Task.sleep(for: .seconds(15))
    let selector = app.buttons["sidebar-client-switch"].exists ? app.buttons["sidebar-client-switch"] : app.buttons["client-switch"]
    let value = try XCTUnwrap(selector.value as? String)
    let expression = try NSRegularExpression(pattern: "手机共([0-9]+)个未读对话")
    let match = try XCTUnwrap(expression.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)))
    let range = try XCTUnwrap(Range(match.range(at: 1), in: value))
    let count = try XCTUnwrap(Int(value[range]))
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let icon = springboard.icons.matching(identifier: "Agent Hub").firstMatch
    XCTAssertTrue(icon.waitForExistence(timeout: 10))
    let badge = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      let values = springboard.icons.matching(identifier: "Agent Hub").allElementsBoundByIndex.compactMap { $0.value as? String }
      let numbers = values.compactMap { text -> Int? in
        let pattern = try! NSRegularExpression(pattern: "[0-9]+")
        guard let item = pattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let range = Range(item.range, in: text) else { return nil }
        return Int(text[range])
      }
      return count == 0 ? numbers.allSatisfy { $0 == 0 } : numbers.contains(count)
    }, object: icon)
    XCTAssertEqual(XCTWaiter.wait(for: [badge], timeout: 15), .completed)
    print("PHYSICAL_MOBILE_UNREAD_BADGE", count)
    shot("physical-mobile-unread-system-icon", fullScreen: true)
    app.activate()
    #endif
  }

  @MainActor func testOriginalDesktopSidebarRefreshShowsUnopenedRunningChat() async throws {
    let control = "http://127.0.0.1:8799"
    let (data, response) = try await URLSession.shared.data(from: URL(string: control)!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    func refreshSidebar() async throws {
      func count() async throws -> Int {
        let (bytes, _) = try await URLSession.shared.data(from: URL(string: control + "/refresh-count")!)
        let result = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Int])
        return try XCTUnwrap(result["workspaceRequests"])
      }
      let before = try await count()
      for _ in 0..<3 {
        pullSidebarRefresh()
        if try await count() > before { return }
        app.scrollViews["sidebar-list"].swipeDown()
        if try await count() > before { return }
      }
      XCTFail("The native refresh gesture did not request a workspace")
    }
    // Use ordinary storage and launch arguments in a dedicated simulator.
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    if app.buttons["onboarding-add"].waitForExistence(timeout: 3) {
      app.buttons["onboarding-add"].tap()
      selectConnectionMethod("手动连接", agent: "codex")
      let address = app.textFields["client-address"]
      XCTAssertTrue(address.waitForExistence(timeout: 5))
      replaceText(address, with: fixture.endpoint)
      // The keyboard accessory can cover the next form row in a medium sheet.
      app.buttons["完成输入"].tap()
      let keyboardHidden = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
      XCTAssertEqual(XCTWaiter.wait(for: [keyboardHidden], timeout: 5), .completed)
      let token = app.secureTextFields["client-token"]
      let tokenReady = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "isHittable == true"), object: token)
      XCTAssertEqual(XCTWaiter.wait(for: [tokenReady], timeout: 10), .completed)
      token.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let focused = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "hasKeyboardFocus == true"), object: token)
      XCTAssertEqual(XCTWaiter.wait(for: [focused], timeout: 5), .completed)
      token.typeText(fixture.token)
      app.buttons["connect-client"].tap()
      let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.secureTextFields["client-token"])
      XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 35), .completed)
    } else {
      connectLive(fixture, supportsCreation: false)
    }
    let mainReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["open-sidebar"])
    XCTAssertEqual(XCTWaiter.wait(for: [mainReady], timeout: 25), .completed)
    swipeSidebar(from: 0.1, to: 0.8)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 10))
    if !app.textFields["sidebar-search"].exists { app.buttons["sidebar-search-toggle"].tap() }
    let search = app.textFields["sidebar-search"]
    XCTAssertTrue(search.waitForExistence(timeout: 5))
    search.tap()
    search.typeText(String(try XCTUnwrap(fixture.threadTitle).prefix(32)))
    search.typeText("\n")
    let row = app.buttons["recent-thread-" + fixture.threadId]
    XCTAssertTrue(row.waitForExistence(timeout: 10))
    XCTAssertTrue(["", "未读"].contains(row.value as? String ?? "unknown"),
      "An idle target can already have an unread desktop reply")
    // The harness submits one real task to the original owner.
    // Never tap the target row or fetch its history from the phone.
    let (_, startResponse) = try await URLSession.shared.data(from: URL(string: control + "/start")!)
    guard (startResponse as? HTTPURLResponse)?.statusCode == 200 else {
      XCTFail("Real task submission failed; do not wait or replay")
      return
    }
    // A real desktop task may update the title used by the search filter.
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    if fixture.verifyUnread != true {
      try await refreshSidebar()
      XCTAssertTrue(row.waitForExistence(timeout: 10))
      let executing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '执行中'"), object: row)
      XCTAssertEqual(XCTWaiter.wait(for: [executing], timeout: 15), .completed)
      shot("sidebar-unopened-real-running")
    }
    var completed = false
    for _ in 0..<120 {
      let (status, _) = try await URLSession.shared.data(from: URL(string: control + "/status")!)
      let result = try XCTUnwrap(JSONSerialization.jsonObject(with: status) as? [String: Bool])
      if result["completed"] == true { completed = true; break }
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertTrue(completed)
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    try await refreshSidebar()
    XCTAssertTrue(row.waitForExistence(timeout: 10))
    let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != '执行中' AND value != '等待审批'"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 15), .completed)
    if fixture.verifyUnread == true {
      let unread = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '未读'"), object: row)
      XCTAssertEqual(XCTWaiter.wait(for: [unread], timeout: 15), .completed)
      let projectRow = app.buttons["thread-" + fixture.threadId]
      if projectRow.exists { XCTAssertEqual(projectRow.value as? String, "未读") }
      shot("sidebar-unopened-real-completed-unread")
    }
    XCTAssertTrue(app.buttons["close-sidebar"].exists)
    shot("sidebar-unopened-real-completed")
    swipeSidebar(from: 0.8, to: 0.1)
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
    swipeSidebar(from: 0.1, to: 0.8)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    XCTAssertTrue(row.exists)
    shot("sidebar-real-swipe-reopened")
  }

  @MainActor func testSidebarHistoryUpdatesStateBeforeEvents() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8797/runtime-reset")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_HISTORY"].waitForExistence(timeout: 10))
    app.buttons["open-sidebar"].tap()
    XCTAssertEqual(app.buttons["recent-thread-refresh-thread"].value as? String, "执行中")
    shot("sidebar-history-updated-without-events")
  }

  func testSidebarSwipeOpenCloseKeepsDraft() {
    let draft = "swipe-keeps-my-draft"
    composer.tap()
    typeComposerText(draft)
    swipeSidebar(from: 0.1, to: 0.8)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.keyboards.firstMatch.exists)
    shot("sidebar-swipe-open")

    let list = app.scrollViews["sidebar-list"]
    let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.25))
    let finish = list.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.25))
    start.press(forDuration: 0.05, thenDragTo: finish)
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    XCTAssertEqual(composer.value as? String, draft)
    shot("sidebar-swipe-closed")

    // The uncovered strip also accepts a closing swipe.
    swipeSidebar(from: 0.1, to: 0.8)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    swipeSidebar(from: 0.97, to: 0.7)
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
    XCTAssertEqual(composer.value as? String, draft)

    // Search focus is dismissed by a closing swipe outside the text field.
    app.buttons["open-sidebar"].tap()
    app.buttons["sidebar-search-toggle"].tap()
    let search = app.textFields["sidebar-search"]
    search.tap()
    search.typeText("Agent")
    let searchList = app.scrollViews["sidebar-list"]
    let searchStart = searchList.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5))
    let searchFinish = searchList.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
    searchStart.press(forDuration: 0.05, thenDragTo: searchFinish)
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    XCTAssertEqual(composer.value as? String, draft)
    shot("sidebar-search-swipe-closed")
    let keyboardHidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [keyboardHidden], timeout: 5), .completed)
    XCTAssertEqual(composer.value as? String, draft)
  }

  func testSidebarSwipeDirectionAndVerticalScrolling() {
    let timeline = app.scrollViews["message-list"]
    timeline.swipeUp()
    timeline.swipeDown()
    swipeSidebar(from: 0.8, to: 0.1)
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    let shortStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.28))
    shortStart.press(forDuration: 0.05, thenDragTo: shortStart.withOffset(CGVector(dx: 18, dy: 0)))
    XCTAssertFalse(app.buttons["close-sidebar"].exists)

    swipeSidebar(from: 0.1, to: 0.8)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    let list = app.scrollViews["sidebar-list"]
    list.swipeUp()
    list.swipeDown()
    swipeSidebar(from: 0.1, to: 0.7)
    XCTAssertTrue(app.buttons["close-sidebar"].exists)
    app.buttons["open-settings"].tap()
    XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
    // Stay inside the actual sheet; its uncovered backdrop accepts native dismissal.
    let settingsBar = app.navigationBars["设置"]
    settingsBar.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
      .press(forDuration: 0.05, thenDragTo: settingsBar.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
    XCTAssertTrue(app.navigationBars["设置"].exists)
    app.buttons["完成"].tap()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["close-sidebar"].exists)

    app.buttons["new-chat"].tap()
    app.buttons["choose-project-mobile"].tap()
    XCTAssertTrue(app.staticTexts["有什么可以帮忙的？"].waitForExistence(timeout: 5))
    swipeSidebar(from: 0.1, to: 0.8)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    app.buttons["close-sidebar"].tap()
  }

  private func swipeSidebar(from startX: CGFloat, to finishX: CGFloat) {
    let start = app.coordinate(withNormalizedOffset: CGVector(dx: startX, dy: 0.28))
    let finish = app.coordinate(withNormalizedOffset: CGVector(dx: finishX, dy: 0.28))
    start.press(forDuration: 0.05, thenDragTo: finish)
  }

  func testSidebarContinuousDragAndReversal() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-sidebar-performance-fixture"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10))
    let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.28))
    // Hold a partial reveal below the commit threshold, then let it return closed.
    start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 75, dy: 0)),
      withVelocity: .slow, thenHoldForDuration: 0.6)
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 275, dy: 0)),
      withVelocity: .slow, thenHoldForDuration: 0.3)
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
    let close = app.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.28))
    close.press(forDuration: 0.05, thenDragTo: close.withOffset(CGVector(dx: -75, dy: 0)),
      withVelocity: .slow, thenHoldForDuration: 0.6)
    XCTAssertTrue(app.buttons["close-sidebar"].exists)
    close.press(forDuration: 0.05, thenDragTo: close.withOffset(CGVector(dx: -275, dy: 0)),
      withVelocity: .slow, thenHoldForDuration: 0.3)
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    shot("sidebar-continuous-drag-closed")
  }

  @MainActor func testPhysicalSidebarInteractionDEV() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the existing signed Agent Hub on a physical iPhone")
    #else
    app.terminate()
    app.launchArguments = ["-sidebar-performance-fixture"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 25))
    if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    let client = app.buttons["client-switch"].label
    let originalDraft = composer.value as? String
    // Start in the edge gutter so a real chat's horizontal code/table scroll keeps its gesture.
    let partial = app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.28))
    partial.press(forDuration: 0.05, thenDragTo: partial.withOffset(CGVector(dx: 75, dy: 0)),
      withVelocity: .slow, thenHoldForDuration: 0.6)
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
    for _ in 0..<3 {
      swipeSidebar(from: 0.02, to: 0.85)
      XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
      XCTAssertTrue(app.scrollViews["sidebar-list"].exists)
      swipeSidebar(from: 0.97, to: 0.3)
      let closed = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "exists == false"), object: app.buttons["close-sidebar"])
      XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed)
    }
    app.buttons["open-sidebar"].tap()
    let projects = app.buttons["sidebar-projects-toggle"]
    for _ in 0..<6 where !projects.isHittable { app.scrollViews["sidebar-list"].swipeUp() }
    XCTAssertTrue(projects.isHittable && projects.isEnabled)
    let value = projects.value as? String
    projects.tap()
    XCTAssertNotEqual(projects.value as? String, value)
    projects.tap()
    XCTAssertEqual(projects.value as? String, value)
    let folder = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'project-'" )).firstMatch
    for _ in 0..<6 where !folder.isHittable { app.scrollViews["sidebar-list"].swipeUp() }
    XCTAssertTrue(folder.isHittable && folder.isEnabled)
    let expanded = folder.value as? String
    folder.tap()
    XCTAssertNotEqual(folder.value as? String, expanded)
    folder.tap()
    XCTAssertEqual(folder.value as? String, expanded)
    shot("sidebar-optimized-physical", fullScreen: true)
    app.buttons["close-sidebar"].tap()
    XCTAssertEqual(app.buttons["client-switch"].label, client)
    XCTAssertEqual(composer.value as? String, originalDraft)
    #endif
  }

  @MainActor func testSidebarPinnedConversations() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8797/pinned-reset")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    app.buttons["open-sidebar"].tap()
    let toggle = app.buttons["sidebar-pinned-toggle"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 15))
    let pins = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pinned-thread-'"))
    XCTAssertEqual(pins.count, 2)
    XCTAssertEqual(pins.element(boundBy: 0).identifier, "pinned-thread-pinned-older")
    XCTAssertEqual(pins.element(boundBy: 1).identifier, "pinned-thread-refresh-thread")
    XCTAssertLessThan(toggle.frame.minY, app.buttons["sidebar-recent-toggle"].frame.minY)
    XCTAssertFalse(app.buttons["recent-thread-refresh-thread"].exists)
    XCTAssertTrue(app.buttons["recent-thread-unpinned-recent"].exists)
    shot("sidebar-pinned-above-recent")
    toggle.tap()
    XCTAssertEqual(toggle.value as? String, "已折叠")
    XCTAssertEqual(pins.count, 0)
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(toggle.waitForExistence(timeout: 10))
    XCTAssertEqual(toggle.value as? String, "已折叠")
    app.buttons["sidebar-search-toggle"].tap()
    let search = app.textFields["sidebar-search"]
    search.tap(); search.typeText("Older pinned\n")
    XCTAssertTrue(app.buttons["pinned-thread-pinned-older"].waitForExistence(timeout: 5))
    XCTAssertEqual(toggle.value as? String, "已展开")
    app.buttons["清除搜索"].tap()
    XCTAssertEqual(toggle.value as? String, "已折叠")
    toggle.tap()
    app.buttons["pinned-thread-refresh-thread"].tap()
    XCTAssertTrue(app.staticTexts["REFRESH_FIXTURE_HISTORY"].waitForExistence(timeout: 10))
    app.buttons["open-sidebar"].tap()
    var request = URLRequest(url: URL(string: fixture.endpoint + "/pinned-state?empty=1")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    _ = try await URLSession.shared.data(for: request)
    app.scrollViews["sidebar-list"].swipeDown()
    let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: toggle)
    XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 15), .completed)
    XCTAssertTrue(app.buttons["recent-thread-refresh-thread"].waitForExistence(timeout: 10), app.debugDescription)
    XCTAssertTrue(app.buttons["recent-thread-pinned-older"].exists, app.debugDescription)
    shot("sidebar-unpinned-refresh")
  }

  func testSidebarRecentAndProjectExpansion() {
    for index in 1...6 {
      app.buttons["new-chat"].tap()
      app.buttons["choose-project-mobile"].tap()
      composer.tap()
      typeComposerText("Sidebar-\(index)-long-conversation-title")
      app.buttons["send-message"].tap()
      XCTAssertTrue(app.staticTexts["Sidebar-\(index)-long-conversation-title"].waitForExistence(timeout: 5))
    }
    app.buttons["open-sidebar"].tap()
    let recent = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recent-thread-'"))
    let projectThreads = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'thread-'"))
    XCTAssertEqual(recent.count, 5)
    XCTAssertTrue(recent.element(boundBy: 0).label.hasPrefix("Sidebar-6-"))
    // The Task Hub entry reduces the list viewport. Materialize the fifth lazy
    // project row through actual scrolling before checking the five-row limit.
    for _ in 0..<3 where projectThreads.count < 5 { app.scrollViews["sidebar-list"].swipeUp() }
    let rows = XCTAttachment(string: app.debugDescription)
    rows.name = "sidebar-row-accessibility"; rows.lifetime = .keepAlways; add(rows)
    shot("sidebar-row-limit-diagnostic")
    XCTAssertEqual(projectThreads.count, 5)
    shot("sidebar-five-recent-and-project")
    for _ in 0..<4 where !app.buttons["sidebar-more-recent"].isHittable { app.scrollViews["sidebar-list"].swipeDown() }
    app.buttons["sidebar-more-recent"].tap()
    XCTAssertEqual(recent.count, 10)
    app.buttons["sidebar-more-recent"].tap()
    XCTAssertEqual(recent.count, 5)

    let more = app.buttons["sidebar-more-project-mobile"]
    for _ in 0..<4 where !more.isHittable { app.scrollViews["sidebar-list"].swipeUp() }
    XCTAssertTrue(more.isHittable)
    more.tap()
    // LazyVStack can release the first two rows when expanding from the bottom.
    // Collect actual accessible IDs across both scroll directions instead of
    // mistaking one viewport's materialization count for the complete list.
    var expandedProjectIds = Set(projectThreads.allElementsBoundByIndex.map(\.identifier))
    for _ in 0..<4 where expandedProjectIds.count < 8 {
      app.scrollViews["sidebar-list"].swipeUp()
      expandedProjectIds.formUnion(projectThreads.allElementsBoundByIndex.map(\.identifier))
    }
    for _ in 0..<4 where expandedProjectIds.count < 8 {
      app.scrollViews["sidebar-list"].swipeDown()
      expandedProjectIds.formUnion(projectThreads.allElementsBoundByIndex.map(\.identifier))
    }
    XCTAssertEqual(expandedProjectIds.count, 8, app.debugDescription)
    XCTAssertTrue(expandedProjectIds.contains("thread-navigation") && expandedProjectIds.contains("thread-pairing"), app.debugDescription)
    for _ in 0..<4 where !more.isHittable { app.scrollViews["sidebar-list"].swipeUp() }
    more.tap()
    XCTAssertEqual(projectThreads.count, 5)
    let folder = app.buttons["project-mobile"]
    for _ in 0..<4 where !folder.isHittable { app.scrollViews["sidebar-list"].swipeDown() }
    folder.tap()
    XCTAssertEqual(projectThreads.count, 0)
    XCTAssertEqual(recent.count, 5)
    folder.tap()
    XCTAssertEqual(projectThreads.count, 5)
    app.buttons["new-in-mobile"].tap()
    XCTAssertTrue(app.staticTexts["有什么可以帮忙的？"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["client-switch"].label.contains("Agent Hub"))
    XCTAssertFalse(app.buttons["close-sidebar"].exists)
  }

  func testClientSwitchKeepsDraftOnOriginalTarget() {
    XCTAssertFalse(app.staticTexts["connection-mode"].exists)
    composer.tap()
    typeComposerText("draft-for-mac")
    switchClient("demo-studio")
    XCTAssertTrue(app.buttons["client-switch"].label.contains("工作室 Mac"))
    XCTAssertTrue(app.buttons["client-switch"].label.contains("web-studio"))
    XCTAssertFalse((composer.value as? String ?? "").contains("draft-for-mac"))
    composer.tap()
    typeComposerText("studio-draft")
    switchClient("demo-mac")
    XCTAssertEqual(composer.value as? String, "draft-for-mac")
    switchClient("demo-studio")
    XCTAssertEqual(composer.value as? String, "studio-draft")
    XCTAssertFalse(app.staticTexts["connection-mode"].exists)
    shot("client-draft-isolation")
  }

  func testNewConversationCreatedOnFirstSend() {
    app.buttons["new-chat"].tap()
    XCTAssertTrue(app.buttons["choose-project-mobile"].waitForExistence(timeout: 3))
    app.buttons["choose-project-mobile"].tap()
    XCTAssertTrue(app.staticTexts["有什么可以帮忙的？"].waitForExistence(timeout: 3))
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    composer.tap()
    typeComposerText("native-first-message")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts["native-first-message"].waitForExistence(timeout: 3))
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "这是界面示例回复"))
        .firstMatch.waitForExistence(timeout: 3))
    app.buttons["open-sidebar"].tap()
    XCTAssertEqual(
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recent-thread-' AND label == %@", "native-first-message"))
        .count, 1)
    XCTAssertEqual(
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'thread-' AND label == %@", "native-first-message"))
        .count, 1)
    shot("first-send-new-thread")
  }

  func testOfflineClientKeepsEditableDraftAndDisablesSend() {
    switchClient("demo-home")
    composer.tap()
    typeComposerText("offline-draft")
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    XCTAssertFalse(app.buttons["new-chat"].isEnabled)
    switchClient("demo-mac")
    switchClient("demo-home")
    XCTAssertEqual(composer.value as? String, "offline-draft")
    XCTAssertFalse(app.staticTexts["connection-mode"].exists)
    shot("offline-draft")
  }

  func testUnsentNewDraftSurvivesClientSwitchAndRelaunch() {
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-mobile"].tap()
    composer.tap()
    typeComposerText("unsent-new-draft")
    switchClient("demo-studio")
    switchClient("demo-mac")
    XCTAssertTrue(app.staticTexts["有什么可以帮忙的？"].exists)
    XCTAssertEqual(composer.value as? String, "unsent-new-draft")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertEqual(composer.value as? String, "unsent-new-draft")
  }

  @MainActor func testSystemAttachmentPickers() {
    quickAttachmentPanel()
    shot("quick-attachment-panel")
    quickAttachmentControl("选择文件").tap()
    XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 5))
    shot("system-file-picker")
    let cancel = app.buttons.matching(
      NSPredicate(format: "identifier == 'Cancel' OR label == '取消'")
    ).firstMatch
    XCTAssertTrue(cancel.waitForExistence(timeout: 5))
    cancel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    quickAttachmentPanel()
    quickAttachmentControl("选择图片").tap()
    if app.scrollViews["attachment-photo-grid"].waitForExistence(timeout: 3) {
      shot("native-photo-grid")
      app.buttons["attachment-photo-library-back"].tap()
      quickAttachmentClosePanel()
      return
    }
    let photoCancel = app.buttons.matching(
      NSPredicate(format: "identifier == 'Cancel' OR label == '取消'")
    ).firstMatch
    XCTAssertTrue(photoCancel.waitForExistence(timeout: 5))
    // Photos first presents a loading placeholder. Its close control moves when
    // the remote picker arrives, so wait for that UI before choosing a touch point.
    let photoTab = app.buttons.matching(
      NSPredicate(format: "label == '照片' OR label == 'Photos'")
    ).firstMatch
    XCTAssertTrue(photoTab.waitForExistence(timeout: 15), app.debugDescription)
    let ready = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "isHittable == true"), object: photoCancel)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 5), .completed)
    shot("system-photo-picker")
    photoCancel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let returned = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "isHittable == true"),
      object: app.buttons["open-sidebar"])
    XCTAssertEqual(XCTWaiter.wait(for: [returned], timeout: 5), .completed)
  }

  @MainActor func testFloatingChatControlsAndExpandingDraft() {
    XCUIDevice.shared.orientation = .portrait
    let timeline = app.scrollViews["message-list"]
    let sidebar = app.buttons["open-sidebar"]
    let permission = app.buttons["permission-settings"]
    let generation = app.buttons["generation-settings"]
    XCTAssertTrue(timeline.waitForExistence(timeout: 10))
    XCTAssertTrue(permission.exists)
    XCTAssertTrue(generation.label.contains("推理强度"))
    XCTAssertTrue(generation.label.contains("速度"))
    assertComposerToolbarFits()
    let header = ["open-sidebar", "client-switch", "new-chat", "chat-menu"].map { app.buttons[$0].frame }
    for (index, frame) in header.enumerated() {
      XCTAssertGreaterThanOrEqual(frame.height, 44)
      XCTAssertGreaterThanOrEqual(frame.width, 44)
      if index > 0 { XCTAssertGreaterThanOrEqual(frame.minX, header[index - 1].maxX) }
    }
    // The timeline fills the page behind both sets of floating controls.
    XCTAssertLessThanOrEqual(timeline.frame.minY, sidebar.frame.minY)
    XCTAssertGreaterThanOrEqual(timeline.frame.maxY, app.buttons["send-message"].frame.maxY)
    let singleLineHeight = composer.frame.height
    XCTAssertLessThanOrEqual(singleLineHeight, 60)
    shot("floating-chat-light")

    let draft = "First line\nSecond line\nThird line\nFourth line"
    composer.tap()
    typeComposerText(draft)
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertGreaterThan(composer.frame.height, singleLineHeight + 30)
    assertComposerToolbarFits()
    if let copy = app.buttons.matching(identifier: "copy-reply").allElementsBoundByIndex.last {
      XCTAssertLessThan(copy.frame.maxY, permission.frame.minY, "Expanding the draft above the keyboard must leave the latest reply actions visible")
      XCTAssertTrue(copy.isHittable)
    }
    shot("floating-chat-keyboard-multiline")
    app.buttons["composer-dismiss-keyboard"].tap()
    permission.tap()
    XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 5))
    app.buttons["generation-done"].tap()
    XCTAssertEqual(composer.value as? String, draft)
    setComposerPlanMode(true)
    XCTAssertTrue(app.buttons["plan-mode-toggle"].waitForExistence(timeout: 5))
    assertComposerToolbarFits()
    XCUIDevice.shared.orientation = .landscapeLeft
    XCTAssertTrue(generation.waitForExistence(timeout: 5))
    assertComposerToolbarFits()
    shot("floating-chat-landscape", fullScreen: true)
    XCUIDevice.shared.orientation = .portrait
    app.buttons["plan-mode-toggle"].tap()
    XCTAssertEqual(app.buttons["plan-mode-toggle"].value as? String, "已关闭")
    app.buttons["open-sidebar"].tap()
    app.buttons["open-settings"].tap()
    app.descendants(matching: .any)["appearance-picker"].firstMatch.tap()
    app.buttons["深色"].tap()
    app.buttons["完成"].tap()
    assertComposerToolbarFits()
    shot("floating-chat-dark")
    app.buttons["send-message"].tap()
    let reply = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "这是界面示例回复")).firstMatch
    XCTAssertTrue(reply.waitForExistence(timeout: 5))
    XCTAssertLessThan(reply.frame.maxY, permission.frame.minY, "The latest reply must remain above the floating settings")
    if let copy = app.buttons.matching(identifier: "copy-reply").allElementsBoundByIndex.last {
      XCTAssertLessThan(copy.frame.maxY, permission.frame.minY)
      XCTAssertTrue(copy.isHittable)
    }
    shot("floating-chat-dark-latest")
  }

  @MainActor func testFloatingJumpToLatestClearsComposer() {
    app.terminate()
    app.launchArguments = ["-ui-testing", "-composer-performance-fixture"]
    app.launchChineseTest()
    let timeline = app.scrollViews["message-list"]
    XCTAssertTrue(timeline.waitForExistence(timeout: 10))
    timeline.swipeDown()
    let jump = app.buttons["跳转最新消息"]
    XCTAssertTrue(jump.waitForExistence(timeout: 5))
    XCTAssertLessThan(jump.frame.maxY, app.buttons["permission-settings"].frame.minY)
    XCTAssertTrue(jump.isHittable)
    jump.tap()
    let hidden = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: jump)
    XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
    if let copy = app.buttons.matching(identifier: "copy-reply").allElementsBoundByIndex.last {
      XCTAssertLessThan(copy.frame.maxY, app.buttons["permission-settings"].frame.minY)
      XCTAssertTrue(copy.isHittable)
    }
    shot("floating-chat-jump-latest")
  }

  func testDarkAppearance() {
    app.buttons["open-sidebar"].tap()
    app.buttons["open-settings"].tap()
    XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
    app.descendants(matching: .any)["appearance-picker"].firstMatch.tap()
    app.buttons["深色"].tap()
    app.buttons["完成"].tap()
    XCTAssertFalse(app.staticTexts["connection-mode"].exists)
    shot("compact-dark")
    XCTAssertTrue(app.buttons["client-switch"].exists)
  }

  @MainActor func testPhysicalAgentHubNotificationRegistration() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the formal Agent Hub installation on the paired physical iPhone")
    #endif
    // Activate the user's ordinary installation without fixture arguments or resetting data.
    if !app.switches["notifications-enabled"].exists {
      if app.buttons["完成"].firstMatch.isHittable { app.buttons["完成"].firstMatch.tap() }
      if !app.buttons["通知"].firstMatch.exists {
        XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
        app.buttons["open-sidebar"].tap()
        app.buttons["open-settings"].tap()
      }
      XCTAssertTrue(app.buttons["通知"].firstMatch.waitForExistence(timeout: 10))
      app.buttons["通知"].firstMatch.tap()
    }
    XCTAssertTrue(app.switches["notifications-enabled"].waitForExistence(timeout: 10))
    shot("physical-agenthub-notification-registration")
    XCTAssertEqual(app.switches["notifications-enabled"].value as? String, "1")
    let permission = app.staticTexts["notification-permission"]
    XCTAssertTrue(permission.label.contains("系统通知已允许"), permission.label)
    print("AGENTHUB_NOTIFICATION_PERMISSION:" + permission.label)
    if app.staticTexts["notification-apns-registration"].exists {
      print("AGENTHUB_APNS_REGISTRATION:" + app.staticTexts["notification-apns-registration"].label)
    }
    let hostId = "25d82f80-4d26-4e37-a71a-30aa3f5bd8bf"
    let hostStatus = app.staticTexts["notification-status-" + hostId]
    for _ in 0..<8 {
      if hostStatus.exists { break }
      app.swipeUp()
    }
    let deadline = Date().addingTimeInterval(45)
    while hostStatus.exists && hostStatus.label != "后台推送已连接" && Date() < deadline {
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertTrue(hostStatus.exists)
    print("AGENTHUB_HOST_STATUS:" + hostStatus.label)
    let reason = app.staticTexts["notification-reason-" + hostId]
    if reason.exists { print("AGENTHUB_HOST_REASON:" + reason.label) }
    shot("physical-agenthub-host-registration")
    XCTAssertEqual(hostStatus.label, "后台推送已连接")
  }

  @MainActor func testCompletedCreationNotificationOpensDurableReceipt() async throws {
    let base = "http://127.0.0.1:8816"
    let (data, _) = try await URLSession.shared.data(from: URL(string: base)!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    enableDEVBadgePermission()
    if !app.buttons["sidebar-client-switch"].exists { app.buttons["open-sidebar"].tap() }
    app.buttons["open-settings"].tap(); app.buttons["通知"].tap()
    let preview = app.switches["notification-content-preview"]
    for _ in 0..<16 {
      if preview.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(preview.isHittable)
    if preview.value as? String == "0" { preview.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap() }
    app.navigationBars["通知"].buttons.firstMatch.tap()
    app.buttons["完成"].firstMatch.tap()
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    app.buttons["new-chat"].tap()
    XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10))
    app.buttons["choose-projectless"].tap()
    let draft = "NOTIFICATION_DRAFT_" + fixture.nonce
    if composer.value as? String != draft {
      XCTAssertTrue((composer.value as? String ?? "").isEmpty || composer.value as? String == "输入消息")
      focusLiveComposer(); composer.typeText(draft)
    }
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    var request = URLRequest(url: URL(string: base + "/notice")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (_, response) = try await URLSession.shared.data(for: request)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    // Open the actual system notification; no direct route invocation or local creation receipt.
    try await Task.sleep(for: .seconds(6))
    let screen = app.frame
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    // SpringBoard can expose pixel dimensions while the foreground app uses points.
    let start = springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: 1))
    let finish = springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: screen.height * 0.85))
    start.press(forDuration: 0.1, thenDragTo: finish)
    let notice = springboard.staticTexts.matching(NSPredicate(format: "label == %@", fixture.nonce)).firstMatch
    XCTAssertTrue(notice.waitForExistence(timeout: 15))
    shot("completed-creation-system-notice", fullScreen: true)
    func tapNotice() {
      let frame = notice.frame
      let scale = frame.midY > screen.height ? springboard.frame.width / screen.width : 1
      print("CREATION_NOTICE_TAP_FRAMES", screen, springboard.frame, frame)
      springboard.coordinate(withNormalizedOffset: .zero)
        .withOffset(CGVector(dx: frame.midX / scale, dy: frame.midY / scale)).tap()
    }
    tapNotice()
    if !app.staticTexts[fixture.nonce].waitForExistence(timeout: 3), notice.exists { tapNotice() }
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
    XCTAssertTrue(app.staticTexts[fixture.nonce].waitForExistence(timeout: 30), "The Host receipt must open the exact completed chat")
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("completed-creation-notice-resolved")
    app.buttons["new-chat"].tap()
    XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10))
    app.buttons["choose-projectless"].tap()
    XCTAssertEqual(composer.value as? String, draft, "Notification navigation must preserve the newer draft")
  }

  @MainActor func testPhysicalAgentHubNotificationRegistrationTapFromNotificationCenter() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires a real APNs notice from the paired physical iPhone")
    #endif
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let start = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.01))
    let finish = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.85))
    start.press(forDuration: 0.1, thenDragTo: finish)
    let computer = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Mac mini 24G")).firstMatch
    guard computer.waitForExistence(timeout: 10) else {
      let attachment = XCTAttachment(screenshot: springboard.screenshot())
      attachment.name = "physical-agenthub-notification-center-no-unconsumed-notice"
      attachment.lifetime = .keepAlways; add(attachment)
      throw XCTSkip("The real notification must still be present in Notification Center")
    }
    XCTAssertTrue(springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "待审批")).firstMatch.exists)
    let center = XCTAttachment(screenshot: springboard.screenshot())
    center.name = "physical-agenthub-apns-notification-center"; center.lifetime = .keepAlways; add(center)
    computer.tap()
    if !app.wait(for: .runningForeground, timeout: 5), computer.exists { computer.tap() }
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
    let client = app.buttons["client-switch"]
    XCTAssertTrue(client.waitForExistence(timeout: 20))
    XCTAssertTrue(client.label.contains("Mac mini 24G"), "Notification must open its original computer")
    let approval = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "approval-")).firstMatch
    XCTAssertTrue(approval.waitForExistence(timeout: 20), "The latest pending approval must be visible")
    shot("physical-agenthub-notification-tap-pending-approval")
    print("AGENTHUB_NOTIFICATION_TAP:original-computer-and-pending-approval-visible")
  }

  @MainActor func testPhysicalAgentHubNotificationRegistrationEnableContentPreview() {
    if !app.switches["notifications-enabled"].exists {
      if app.buttons["完成"].firstMatch.isHittable { app.buttons["完成"].firstMatch.tap() }
      if !app.buttons["通知"].firstMatch.exists {
        XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
        // iOS may expose the fixed toolbar button with an invalid automatic hit point.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.093)).tap()
        XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout: 10))
        app.buttons["open-settings"].tap()
      }
      XCTAssertTrue(app.buttons["通知"].firstMatch.waitForExistence(timeout: 10))
      app.buttons["通知"].firstMatch.tap()
    }
    let preview = app.switches["notification-content-preview"]
    for _ in 0..<16 {
      if preview.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(preview.isHittable)
    if preview.value as? String == "0" {
      preview.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }
    let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: preview)
    XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 10), .completed)
    shot("physical-agenthub-content-preview-enabled")
    print("AGENTHUB_CONTENT_PREVIEW:enabled-for-user-requested-conversation-summary")
  }

  @MainActor func testPhysicalAgentHubNotificationRegistrationExpandedContent() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires a real APNs notice from the paired physical iPhone")
    #endif
    struct Expected: Decodable {
      let title: String
      let summary: String
      let computer: String
      let kind: String
    }
    guard let input = ProcessInfo.processInfo.environment["NOTIFICATION_EXPECTATIONS"] else {
      throw XCTSkip("Provide the independently verified real desktop notification")
    }
    let expected = try JSONDecoder().decode(Expected.self, from: Data(input.utf8))
    XCTAssertFalse(expected.title.isEmpty); XCTAssertFalse(expected.summary.isEmpty)
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let start = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.01))
    let finish = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.85))
    start.press(forDuration: 0.1, thenDragTo: finish)
    let title = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected.title)).firstMatch
    guard title.waitForExistence(timeout: 15) else {
      let missing = XCTAttachment(screenshot: springboard.screenshot())
      missing.name = "physical-agenthub-summary-card-notice-missing"; missing.lifetime = .keepAlways; add(missing)
      XCTFail("The independently verified real notice must remain in Notification Center")
      return
    }
    title.press(forDuration: 1.2)
    let summary = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected.summary)).firstMatch
    XCTAssertTrue(summary.waitForExistence(timeout: 10))
    let computer = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected.computer)).firstMatch
    let kind = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected.kind)).firstMatch
    XCTAssertTrue(computer.exists); XCTAssertTrue(kind.exists)
    XCTAssertGreaterThan(computer.frame.minX, title.frame.minX)
    XCTAssertGreaterThan(kind.frame.minX, summary.frame.minX)
    let card = XCTAttachment(screenshot: springboard.screenshot())
    card.name = "physical-agenthub-real-summary-card"; card.lifetime = .keepAlways; add(card)
    print("AGENTHUB_SUMMARY_CARD:real-apns-title-summary-and-right-side-computer-kind")
  }

  @MainActor func testTaskNotificationPreferencesAndPermissionDenied() {
    app.terminate(); app.launchArguments = ["-ui-testing", "-reset-notification-settings"]; app.launchChineseTest()
    func openSettings() {
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 10)); app.buttons["open-sidebar"].tap()
      app.buttons["open-settings"].tap(); app.buttons["通知"].firstMatch.tap()
      XCTAssertTrue(app.switches["notifications-enabled"].waitForExistence(timeout: 5))
    }
    openSettings()
    XCTAssertEqual(app.switches["notifications-enabled"].value as? String, "0")
    for kind in ["approval", "question", "plan", "paused", "interrupted", "failed", "uncertain"] {
      let toggle = app.switches["notification-kind-" + kind]
      while !toggle.isHittable { app.swipeUp() }
      XCTAssertEqual(toggle.value as? String, "1")
    }
    for kind in ["completed", "connection", "stopped"] {
      let toggle = app.switches["notification-kind-" + kind]
      while !toggle.isHittable { app.swipeUp() }
      XCTAssertEqual(toggle.value as? String, "0")
    }
    app.switches["notification-kind-completed"].tap()
    let notifications = app.switches["notifications-enabled"]
    let notificationList = app.collectionViews.firstMatch
    XCTAssertTrue(notificationList.waitForExistence(timeout: 5))
    // Stop as soon as the control is reachable; an extra whole-sheet swipe can dismiss it.
    for _ in 0..<6 {
      if notifications.isHittable { break }
      notificationList.swipeDown()
    }
    XCTAssertTrue(notifications.isHittable, app.debugDescription)
    notifications.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
    let system = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
    let alert = system.waitForExistence(timeout: 5) ? system : app.alerts.firstMatch
    if alert.waitForExistence(timeout: 5) { alert.buttons.element(boundBy: 0).tap() }
    if app.alerts["通知"].waitForExistence(timeout: 4) { app.alerts["通知"].buttons["好"].tap() }
    XCTAssertEqual(app.switches["notifications-enabled"].value as? String, "0")
    let permission = app.staticTexts["notification-permission"]
    XCTAssertTrue(permission.label.contains("关闭"), permission.label + "\n" + app.debugDescription)
    shot("notifications-denied-and-defaults")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest(); openSettings()
    let completed = app.switches["notification-kind-completed"]
    while !completed.isHittable { app.swipeUp() }
    XCTAssertEqual(completed.value as? String, "1")
    let stopped = app.switches["notification-kind-stopped"]
    while !stopped.isHittable { app.swipeUp() }
    XCTAssertEqual(stopped.value as? String, "0")
    shot("notifications-preferences-restored")
  }

  @MainActor func testAddClientValidationAndRealLocalHistory() async throws {
    let available = try? await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:8788/healthz")!)
    guard let data = available?.0,
      let health = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      (health["capabilities"] as? [String])?.contains("history:read") == true
    else { throw XCTSkip("Start the local history service to run real history acceptance") }
    app.buttons["client-switch"].tap()
    app.buttons["add-client"].tap()
    selectConnectionMethod("手动连接", agent: "codex")
    XCTAssertTrue(app.textFields["client-name"].waitForExistence(timeout: 3))
    let name = app.textFields["client-name"]
    name.tap()
    name.typeText(
      String(
        repeating: XCUIKeyboardKey.delete.rawValue,
        count: (name.value as? String ?? "").count))
    app.buttons["connect-client"].tap()
    XCTAssertTrue(app.staticTexts["client-form-error"].waitForExistence(timeout: 3))
    name.tap()
    name.typeText("Local Mac")
    app.buttons["connect-client"].tap()
    XCTAssertTrue(app.staticTexts["readonly-note"].waitForExistence(timeout: 25))
    XCTAssertFalse(app.buttons["new-chat"].isEnabled)
    composer.tap()
    typeComposerText("live-read-only-draft")
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    app.buttons["open-sidebar"].tap()
    XCTAssertGreaterThan(
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'project-' ")).count, 0)
    XCTAssertGreaterThan(
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'recent-thread-' ")).count, 0)
    XCTAssertTrue(app.buttons["sidebar-client-switch"].label.contains("桌面历史 · 只读"))
    let workspaceResponse = try await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:8788/v1/workspace")!)
    let workspace = try XCTUnwrap(
      JSONSerialization.jsonObject(with: workspaceResponse.0) as? [String: Any])
    let projects = try XCTUnwrap(workspace["projects"] as? [[String: Any]])
    let threads = try XCTUnwrap(workspace["threads"] as? [[String: Any]])
    let projectId = try XCTUnwrap(projects.compactMap { $0["id"] as? String }.first { id in
      threads.contains {
        ($0["projectId"] as? String) == id || ($0["projectIds"] as? [String])?.contains(id) == true
      }
    })
    let project = app.buttons["project-" + projectId]
    if project.value as? String == "已折叠" { project.tap() }
    XCTAssertGreaterThan(
      app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'thread-' ")).count, 0)
    if let sharedThread = threads.first(where: {
      (($0["projectIds"] as? [String])?.count ?? 0) > 1
    }) {
      let sharedThreadId = try XCTUnwrap(sharedThread["id"] as? String)
      let sharedProjectId = try XCTUnwrap((sharedThread["projectIds"] as? [String])?.first)
      let sharedProjectName = try XCTUnwrap(
        projects.first { ($0["id"] as? String) == sharedProjectId }?["name"] as? String)
      let sharedProject = app.buttons["project-" + sharedProjectId]
      if sharedProject.value as? String == "已折叠" { sharedProject.tap() }
      app.buttons["thread-" + sharedThreadId].tap()
      XCTAssertTrue(app.buttons["client-switch"].label.contains(sharedProjectName))
      XCTAssertFalse(app.buttons["send-message"].isEnabled)
      app.terminate()
      app.launchArguments = ["-ui-testing", "-restore-testing"]
      app.launchChineseTest()
      XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 10))
      XCTAssertTrue(app.buttons["client-switch"].label.contains(sharedProjectName))
    } else {
      app.buttons["close-sidebar"].tap()
    }
    // No screenshots or conversation bodies from the user's real history are exported.
    switchClient("demo-mac")
    XCTAssertFalse((composer.value as? String ?? "").contains("live-read-only-draft"))
  }

  @MainActor func testMobileGenerationSettings() async throws {
    guard let result = try? await URLSession.shared.data(from: URL(string: ProcessInfo.processInfo.environment["GENERATION_FIXTURE_ENDPOINT"] ?? "http://127.0.0.1:8797")!),
      let fixture = try? JSONDecoder().decode(LiveFixture.self, from: result.0)
    else { throw XCTSkip("Run the generation settings iOS fixture harness") }
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let settings = app.buttons["generation-settings"]
    func openSettings() {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: settings)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed)
      settings.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 10))
    }
    XCTAssertTrue(settings.waitForExistence(timeout: 10))
    XCTAssertTrue(settings.label.contains("Fixture Quality"))
    XCTAssertTrue(app.buttons["plan-mode-toggle"].exists)
    openSettings()
    let model = app.descendants(matching: .any)["model-picker"].firstMatch
    XCTAssertTrue(model.waitForExistence(timeout: 5))
    model.tap()
    app.buttons["Fixture Fast"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let slider = app.sliders["effort-slider"]
    XCTAssertTrue(slider.waitForExistence(timeout: 5))
    slider.adjust(toNormalizedSliderPosition: 1)
    let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
    effort.tap()
    XCTAssertFalse(app.buttons["极高"].exists)
    let low = app.buttons["低"].firstMatch
    for _ in 0..<2 {
      var previous = CGRect.null
      let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard low.exists, low.isHittable else { return false }
        let frame = low.frame
        defer { previous = frame }
        return !frame.isEmpty && !frame.isNull && frame == previous
      }, object: low)
      XCTAssertEqual(XCTWaiter.wait(for: [stable], timeout: 10), .completed)
      low.tap()
      if low.waitForNonExistence(timeout: 2) { break }
    }
    XCTAssertTrue(low.waitForNonExistence(timeout: 5), "Effort menu must close after the actual low selection")
    let fast = app.switches["fast-mode-toggle"]
    XCTAssertTrue(fast.waitForExistence(timeout: 5))
    if !fast.isHittable { app.swipeUp() }
    XCTAssertTrue(fast.isHittable); XCTAssertTrue(fast.isEnabled)
    shot("mobile-generation-speed-before")
    print("GENERATION_SPEED_CONTROL", fast.frame, fast.value as Any)
    // SwiftUI exposes the whole labeled row as a Switch. Target the visible
    // off-state thumb rather than the row center or trailing track edge.
    fast.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: fast.frame.width - 54, dy: fast.frame.height / 2)).tap()
    let speedEnabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: fast)
    let speedResult = XCTWaiter.wait(for: [speedEnabled], timeout: 8)
    shot("mobile-generation-speed-after")
    XCTAssertEqual(speedResult, .completed, app.debugDescription)
    XCTAssertEqual(fast.value as? String, "1")
    let full = app.buttons["permission-full-access"]
    if !full.isHittable { app.swipeUp() }
    full.tap()
    shot("mobile-generation-settings")
    app.buttons["generation-done"].tap()
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    XCTAssertTrue(settings.label.contains("低"))
    XCTAssertTrue(app.buttons["permission-settings"].label.contains("完全访问"))
    let secondarySearch = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    secondarySearch.tap()
    secondarySearch.typeText("Generation secondary\n")
    let secondary = app.buttons["recent-thread-generation-secondary"]
    XCTAssertTrue(secondary.waitForExistence(timeout: 10))
    secondary.tap()
    XCTAssertTrue(settings.waitForExistence(timeout: 10))
    XCTAssertTrue(settings.label.contains("Fixture Quality"))
    XCTAssertTrue(app.buttons["permission-settings"].label.contains("请求批准"))
    openDesktopTestConversation(fixture)
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(settings.waitForExistence(timeout: 15))
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    XCTAssertTrue(app.buttons["permission-settings"].label.contains("完全访问"))
    focusLiveComposer()
    typeComposerText("Settings first")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["SETTINGS_APPLIED"].waitForExistence(timeout: 10))
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    XCTAssertTrue(app.buttons["plan-mode-toggle"].exists)
    assertComposerToolbarFits()
    chooseDesktopProject(nil)
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    XCTAssertTrue(settings.label.contains("低"))
    XCTAssertTrue(app.buttons["permission-settings"].label.contains("完全访问"))
    openSettings()
    XCTAssertTrue(fast.waitForExistence(timeout: 5))
    XCTAssertEqual(fast.value as? String, "1")
    app.buttons["generation-done"].tap()
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(settings.waitForExistence(timeout: 15))
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    focusLiveComposer()
    typeComposerText("Remembered fast")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["CREATED_generation-created-1"].waitForExistence(timeout: 15))
    chooseDesktopProject("generation-project")
    XCTAssertTrue(settings.label.contains("Fixture Fast"))
    XCTAssertTrue(app.buttons["permission-settings"].label.contains("完全访问"))
    openSettings()
    XCTAssertTrue(fast.waitForExistence(timeout: 5))
    XCTAssertEqual(fast.value as? String, "1")
    if !fast.isHittable { app.swipeUp() }
    fast.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    app.buttons["generation-done"].tap()
    focusLiveComposer()
    typeComposerText("Remembered standard")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["CREATED_generation-created-2"].waitForExistence(timeout: 15))
    chooseDesktopProject(nil)
    openSettings()
    XCTAssertTrue(fast.waitForExistence(timeout: 5))
    XCTAssertEqual(fast.value as? String, "0")
    app.buttons["generation-done"].tap()
    openDesktopTestConversation(fixture)
    shot("mobile-generation-composer-compact")
    let compactModelWidth = settings.frame.width
    XCUIDevice.shared.orientation = .landscapeLeft
    XCTAssertTrue(settings.waitForExistence(timeout: 10))
    assertComposerToolbarFits()
    XCTAssertGreaterThan(settings.frame.width, compactModelWidth)
    XCTAssertGreaterThan(app.buttons["permission-settings"].frame.width, 44)
    shot("mobile-generation-composer-wide", fullScreen: true)
    XCUIDevice.shared.orientation = .portrait

    var request = URLRequest(url: URL(string: fixture.endpoint + "/readonly")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    _ = try await URLSession.shared.data(for: request)
    let disabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == false"), object: settings)
    XCTAssertEqual(XCTWaiter.wait(for: [disabled], timeout: 10), .completed)
    XCTAssertFalse(app.buttons["permission-settings"].isEnabled)
  }

  @MainActor func testOriginalDesktopGenerationPreferences() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8805")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let ultra = fixture.generationUltrafast == true
    let modelName = ultra ? "GPT-6.1-Sol" : "GPT-6-Sol"
    connectLive(fixture)
    chooseDesktopProject(nil)
    let settings = app.buttons["generation-settings"]
    func openSettings() {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: settings)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed)
      settings.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 10))
    }
    openSettings()
    let model = app.descendants(matching: .any)["model-picker"].firstMatch
    XCTAssertTrue(model.waitForExistence(timeout: 15))
    model.tap()
    let chosen = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", modelName)).firstMatch
    XCTAssertTrue(chosen.waitForExistence(timeout: 15))
    chosen.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
    app.buttons["低"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let fast = app.switches["fast-mode-toggle"]
    func selectSpeed(_ label: String) {
      let picker = app.descendants(matching: .any)["speed-picker"].firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 10))
      if !picker.isHittable { app.swipeUp() }
      picker.tap()
      app.buttons[label].tap()
    }
    if ultra { selectSpeed("极速") }
    else {
      XCTAssertTrue(fast.waitForExistence(timeout: 10))
      if !fast.isHittable { app.swipeUp() }
      fast.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
      XCTAssertEqual(fast.value as? String, "1")
    }
    let full = app.buttons["permission-full-access"]
    if !full.isHittable { app.swipeUp() }
    full.tap()
    shot("original-desktop-speed-settings")
    app.buttons["generation-done"].tap()
    func sendMarker(_ marker: String) {
      focusLiveComposer()
      typeComposerText("只原样回复 \(marker)，不要使用工具。")
      sendLiveComposer()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch.waitForExistence(timeout: 120))
      let finished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
      XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 45), .completed)
    }
    sendMarker((ultra ? "GEN_ULTRA_ONE_" : "GEN_FAST_ONE_") + fixture.nonce)
    chooseDesktopProject(try XCTUnwrap(fixture.creationProjectId))
    XCTAssertTrue(settings.label.contains(modelName))
    XCTAssertTrue(settings.label.contains("低"))
    XCTAssertTrue(app.buttons["permission-settings"].label.contains("完全访问"))
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(settings.waitForExistence(timeout: 20))
    XCTAssertTrue(settings.label.contains(modelName))
    openSettings()
    if ultra {
      let picker = app.descendants(matching: .any)["speed-picker"].firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 10))
      XCTAssertTrue(picker.label.contains("极速") || (picker.value as? String)?.contains("极速") == true)
    } else {
      XCTAssertTrue(fast.waitForExistence(timeout: 10))
      XCTAssertEqual(fast.value as? String, "1")
    }
    app.buttons["generation-done"].tap()
    sendMarker((ultra ? "GEN_ULTRA_TWO_" : "GEN_FAST_TWO_") + fixture.nonce)
    chooseDesktopProject(nil)
    openSettings()
    if ultra {
      let picker = app.descendants(matching: .any)["speed-picker"].firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 10))
      XCTAssertTrue(picker.label.contains("极速") || (picker.value as? String)?.contains("极速") == true)
    } else {
      XCTAssertTrue(fast.waitForExistence(timeout: 10))
      XCTAssertEqual(fast.value as? String, "1")
    }
    if ultra {
      selectSpeed("快速")
      app.buttons["generation-done"].tap()
      sendMarker("GEN_FAST_" + fixture.nonce)
      chooseDesktopProject(nil)
      openSettings()
      selectSpeed("标准")
    } else {
      if !fast.isHittable { app.swipeUp() }
      fast.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }
    app.buttons["generation-done"].tap()
    sendMarker("GEN_STANDARD_" + fixture.nonce)
    chooseDesktopProject(nil)
    openSettings()
    if ultra {
      let picker = app.descendants(matching: .any)["speed-picker"].firstMatch
      XCTAssertTrue(picker.waitForExistence(timeout: 10))
      XCTAssertTrue(picker.label.contains("标准") || (picker.value as? String)?.contains("标准") == true)
    } else {
      XCTAssertTrue(fast.waitForExistence(timeout: 10))
      XCTAssertEqual(fast.value as? String, "0")
    }
    shot("original-desktop-speed-standard-remembered")
    app.buttons["generation-done"].tap()
  }

  @MainActor func testThreadModelSettingsPhysicalDEV() async throws {
    struct Fixture: Decodable {
      let bundleId: String
      let connection: LiveFixture
      let second: LiveFixture
      let alternateModelName: String
    }
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["THREAD_MODEL_SETTINGS_FIXTURE"])
    let fixture = try JSONDecoder().decode(Fixture.self, from: Data(raw.utf8))
    XCTAssertTrue(fixture.bundleId.hasPrefix("com.easyai.agenthub.modelsettings."))
    connectLive(fixture.connection, supportsCreation: false, nickname: "模型同步 DEV")
    func openThread(_ target: LiveFixture) async throws {
      let row = app.buttons["recent-thread-" + target.threadId]
      for _ in 0..<5 {
        if row.exists && row.isHittable { break }
        let close = app.buttons["close-sidebar"]
        if !close.exists {
          let open = app.buttons["open-sidebar"]
          if open.exists && open.isHittable { open.tap() }
          try await Task.sleep(for: .seconds(1))
        }
        if row.exists && row.isHittable { break }
        let search = app.textFields["sidebar-search"]
        if !search.exists {
          let toggle = app.buttons["sidebar-search-toggle"]
          if toggle.exists && toggle.isHittable { toggle.tap() }
          try await Task.sleep(for: .seconds(1))
        }
        if search.exists && search.isHittable {
          search.tap()
          let old = search.value as? String ?? ""
          search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + (target.threadTitle ?? target.threadId) + "\n")
          try await Task.sleep(for: .seconds(2))
        }
      }
      XCTAssertTrue(row.isHittable, app.debugDescription)
      row.tap()
      try await Task.sleep(for: .seconds(1))
    }
    func matches(_ model: String, _ effort: String, _ phase: String) {
      let button = app.buttons["generation-settings"]
      XCTAssertTrue(button.waitForExistence(timeout: 25))
      // This independent wildcard-signed DEV App has no production App Group.
      // Close only its specific unavailable inbox notice before checking pixels.
      let inboxNotice = app.alerts["无法完成操作"]
      if inboxNotice.waitForExistence(timeout: 1),
        inboxNotice.staticTexts["暂时无法读取共享文件，请稍后重试。"].exists {
        print("MODEL_SETTINGS_DEV_INBOX_NOTICE " + phase)
        inboxNotice.buttons["好"].tap()
      }
      XCTAssertFalse(app.alerts.firstMatch.exists, app.debugDescription)
      let expected = XCTNSPredicateExpectation(predicate: NSPredicate(format:
        "label CONTAINS %@ AND label CONTAINS %@", model, "推理强度，" + effort), object: button)
      XCTAssertEqual(XCTWaiter.wait(for: [expected], timeout: 35), .completed, button.label)
      print("MODEL_SETTINGS_NATIVE_PASS " + phase + " " + button.label)
      shot("model-settings-" + phase)
    }
    func control(_ phase: String) async throws {
      // The App's real HTTPS reads remain on the phone. Desktop-only actions
      // are triggered by the Mac driver; a separate Runner needs no network grant.
      print("MODEL_SETTINGS_CONTROL " + phase)
      try await Task.sleep(for: .seconds(3))
    }
    try await openThread(fixture.connection)
    matches("6.1", "极高", "desktop-xhigh")
    app.buttons["generation-settings"].tap()
    XCTAssertTrue(app.staticTexts["电脑当前设置"].waitForExistence(timeout: 10))
    app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
    app.buttons["低"].tap()
    app.buttons["generation-done"].tap()
    matches("6.1", "极高", "draft-low-does-not-replace-owner")
    try await openThread(fixture.second)
    matches(fixture.alternateModelName, "高", "other-thread")
    try await openThread(fixture.connection)
    matches("6.1", "极高", "return-keeps-owner")
    try await control("desktop-low")
    matches("6.1", "低", "desktop-effort-changed")
    try await control("desktop-alternate")
    matches(fixture.alternateModelName, "高", "desktop-model-changed")
    try await control("desktop-xhigh")
    matches("6.1", "极高", "desktop-restored")
    try await control("drop-full-snapshot")
    try await openThread(fixture.second)
    try await openThread(fixture.connection)
    matches("6.1", "极高", "missing-snapshot-real-owner-settings")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    matches("6.1", "极高", "restart-retains-correct-owner")
    try await control("restore-full-snapshot")
    matches("6.1", "极高", "reconnected")
  }

  @MainActor func testOriginalDesktopPermissionDefaults() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let probe = try XCTUnwrap(fixture.permissionProbePath)
    connectLive(fixture)
    chooseDesktopProject(nil)
    for (index, mode) in ["", "read-only", "full-access", "guardian-approvals"].enumerated() {
      let settings = app.buttons["generation-settings"]
      XCTAssertTrue(settings.waitForExistence(timeout: 20))
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: settings)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 20), .completed)
      settings.tap()
      let permission = app.buttons[mode.isEmpty ? "permission-default" : "permission-" + mode]
      if !permission.isHittable { app.swipeUp() }
      XCTAssertTrue(permission.waitForExistence(timeout: 10))
      permission.tap()
      app.buttons["generation-done"].tap()
      let marker = "PERMISSION_\(index)_" + fixture.nonce
      focusLiveComposer()
      if index == 0 || index == 2 {
        typeComposerText("这是已授权的权限验收，只修改指定验收文件。请使用 exec_command 实际执行 printf '\(marker)' > '\(probe)' && cat '\(probe)'，必须使用当前默认权限，不另行征求确认。完成后仅原样回复 \(marker)。")
      } else if index == 3 {
        typeComposerText("请实际使用 exec_command 执行 printf '\(marker)'，使用当前默认权限。完成后仅原样回复 \(marker)。")
      } else {
        typeComposerText("只原样回复 \(marker)，不要使用工具。")
      }
      sendLiveComposer()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch.waitForExistence(timeout: 150))
      let finished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
      XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 45), .completed)
      XCTAssertFalse(app.buttons["approval-accept"].exists)
      let label = index == 1 ? "请求批准" : index == 3 ? "帮我批准" : "完全访问"
      XCTAssertTrue(app.buttons["permission-settings"].label.contains(label))
    }
    shot("original-desktop-permission-defaults")
  }

  @MainActor private func assertComposerToolbarFits(file: StaticString = #filePath, line: UInt = #line) {
    let tolerance: CGFloat = 0.01
    let rows = [["permission-settings", "plan-mode-toggle", "generation-settings"], ["attachment-menu", "voice-input", "send-message"]]
    var settingsBottom: CGFloat?
    for ids in rows {
      let elements = ids.filter { app.buttons[$0].exists }
      let frames = elements.map { app.buttons[$0].frame }
      for (index, frame) in frames.enumerated() {
        XCTAssertGreaterThanOrEqual(frame.width + tolerance, 44, elements[index], file: file, line: line)
        XCTAssertGreaterThanOrEqual(frame.height + tolerance, 44, elements[index], file: file, line: line)
        XCTAssertEqual(frame.midY, frames[0].midY, accuracy: 1, elements[index], file: file, line: line)
        XCTAssertGreaterThanOrEqual(frame.minX + tolerance, app.frame.minX, elements[index], file: file, line: line)
        XCTAssertLessThanOrEqual(frame.maxX, app.frame.maxX + tolerance, elements[index], file: file, line: line)
        if index > 0 { XCTAssertGreaterThanOrEqual(frame.minX + tolerance, frames[index - 1].maxX, elements[index], file: file, line: line) }
      }
      if ids[0] == "permission-settings" { settingsBottom = frames.map(\.maxY).max() }
      else if let settingsBottom, let inputTop = frames.map(\.minY).min() {
        XCTAssertLessThanOrEqual(settingsBottom, inputTop + tolerance, file: file, line: line)
      }
    }
  }

  @MainActor func testMobilePlanModeAndPlanActions() async throws {
    guard let result = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8796")!),
      let fixture = try? JSONDecoder().decode(LiveFixture.self, from: result.0)
    else { throw XCTSkip("Run the plan mode iOS fixture harness") }
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let badge = app.buttons["plan-mode-toggle"]
    XCTAssertTrue(badge.waitForExistence(timeout: 15))
    let plan = app.descendants(matching: .any)["proposed-plan-message"].firstMatch
    XCTAssertTrue(plan.waitForExistence(timeout: 10))
    plan.tap()
    XCTAssertTrue(app.staticTexts["Inspect input, then implement."].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["implement-plan"].isEnabled)
    shot("mobile-plan-review")
    app.buttons["adjust-plan"].tap()
    let feedback = app.descendants(matching: .any)["plan-feedback"].firstMatch
    XCTAssertTrue(feedback.waitForExistence(timeout: 5))
    feedback.tap()
    feedback.typeText("Focus on the mobile UI first.")
    tapAISharingAction(app.buttons["submit-plan-feedback"])
    let adjusted = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: feedback)
    XCTAssertEqual(XCTWaiter.wait(for: [adjusted], timeout: 10), .completed)
    XCTAssertTrue(app.buttons["implement-plan"].waitForExistence(timeout: 10))
    tapAISharingAction(app.buttons["implement-plan"])
    XCTAssertTrue(app.staticTexts["PLAN_ACTION_COMPLETED"].waitForExistence(timeout: 10))
    XCTAssertEqual(badge.value as? String, "已关闭")
    XCTAssertFalse(app.buttons["implement-plan"].exists)
    setComposerPlanMode(true)
    XCTAssertTrue(badge.waitForExistence(timeout: 5))
    composer.tap()
    typeComposerText("Plan from the phone.")
    shot("mobile-composer-plan-mode")
    tapAISharingAction(app.buttons["send-message"])
    XCTAssertTrue(app.buttons["implement-plan"].waitForExistence(timeout: 10))
    badge.tap()
    XCTAssertEqual(badge.value as? String, "已关闭")
    composer.tap()
    typeComposerText("Continue in default mode.")
    tapAISharingAction(app.buttons["send-message"])
    XCTAssertTrue(app.staticTexts["PLAN_ACTION_COMPLETED"].waitForExistence(timeout: 10))
    var request = URLRequest(url: URL(string: fixture.endpoint + "/readonly")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    _ = try await URLSession.shared.data(for: request)
    let readOnly = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isEnabled == false"), object: app.buttons["implement-plan"])
    XCTAssertEqual(XCTWaiter.wait(for: [readOnly], timeout: 10), .completed)
    XCTAssertTrue(app.buttons["implement-plan"].waitForExistence(timeout: 10))
    XCTAssertFalse(app.buttons["implement-plan"].isEnabled)
    XCTAssertFalse(app.buttons["adjust-plan"].isEnabled)
    // Read-only execution still permits editing the local attachment draft.
    XCTAssertTrue(app.buttons["attachment-menu"].isEnabled)
    XCTAssertTrue(badge.exists)
    XCTAssertFalse(badge.isEnabled)
    quickAttachmentPanel()
    XCTAssertFalse(app.buttons["select-plan-mode"].exists)
    quickAttachmentClosePanel()
    shot("mobile-plan-readonly")
  }


  @MainActor private func nativeReadFixture(font: String = "default") async throws -> LiveFixture {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8817/fixture?font=" + font)!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return try JSONDecoder().decode(LiveFixture.self, from: data)
  }

  @MainActor private func revealNativeReadElement(_ element: XCUIElement, upwards: Bool) {
    let list = app.scrollViews["message-list"]
    for _ in 0..<14 where !element.isHittable { if upwards { list.swipeUp() } else { list.swipeDown() } }
    XCTAssertTrue(element.isHittable)
  }

  @MainActor func testNativeReadMarkdownToolAndDiffParity() async throws {
    let fixture = try await nativeReadFixture()
    connectLive(fixture, supportsCreation: false, nickname: "合成历史电脑")
    openDesktopTestConversation(fixture)
    let group = app.buttons["tool-group-read-command"]
    revealNativeReadElement(group, upwards: false)
    XCTAssertEqual(group.label, "已编辑文件、运行命令")
    group.tap()
    let command = app.buttons["tool-item-read-command"]
    XCTAssertTrue(command.isHittable); command.tap()
    XCTAssertTrue(app.buttons["复制命令"].waitForExistence(timeout: 10))
    shot("native-read-command-light")
    let diff = app.buttons["tool-item-read-diff"]
    revealNativeReadElement(diff, upwards: true); diff.tap()
    XCTAssertTrue(app.buttons["复制工具详情"].waitForExistence(timeout: 10))
    shot("native-read-diff-light")
    let table = app.scrollViews["markdown-table"]
    revealNativeReadElement(table, upwards: true)
    shot("native-read-markdown-table-light")
    let copy = app.buttons["copy-code"].firstMatch
    revealNativeReadElement(copy, upwards: true); copy.tap()
    shot("native-read-markdown-code-light")
    XCTAssertTrue(app.buttons["copy-reply"].exists)
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
  }

  @MainActor func testNativeReadDarkLandscapeAndLargeFontParity() async throws {
    let fixture = try await nativeReadFixture()
    connectLive(fixture, supportsCreation: false, nickname: "合成历史电脑")
    openDesktopTestConversation(fixture)
    setChatAppearance("深色")
    _ = try await nativeReadFixture(font: "large")
    shot("native-read-large-font-dark-portrait")
    XCUIDevice.shared.orientation = .landscapeLeft
    defer { XCUIDevice.shared.orientation = .portrait }
    var previousFrame = CGRect.null
    let landscape = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      let frame = self.app.windows.firstMatch.frame
      let stable = frame == previousFrame && frame.width > frame.height && self.app.buttons["open-sidebar"].isHittable
      previousFrame = frame
      return stable
    }, object: app)
    XCTAssertEqual(XCTWaiter.wait(for: [landscape], timeout: 10), .completed)
    shot("native-read-large-font-dark-landscape", fullScreen: true)
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.scrollViews["sidebar-list"].waitForExistence(timeout: 10))
    shot("native-read-large-font-dark-drawer", fullScreen: true)
  }

  @MainActor private func historyPagingFixture(_ mode: String) async throws -> LiveFixture {
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8814/reset?mode=" + mode)!) else {
      throw XCTSkip("Run scripts/history-pagination-ios-fixture.mjs")
    }
    return try JSONDecoder().decode(LiveFixture.self, from: data)
  }

  @MainActor func testHistoryPagingOriginalDesktopDEV() async throws {
    struct Metadata: Decodable { let fixture: LiveFixture; let earliestUserText: String }
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8815")!) else {
      throw XCTSkip("Provide independently verified original desktop history metadata")
    }
    let metadata = try JSONDecoder().decode(Metadata.self, from: data)
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    connectLive(metadata.fixture, supportsCreation: false)
    openDesktopTestConversation(metadata.fixture)
    let control = scrollToHistoryControl("history-local-more")
    XCTAssertLessThanOrEqual(control.frame.height, 50)
    shot("history-paging-real-desktop-compact")
    pullEarlierHistory()
    XCTAssertFalse(metadata.earliestUserText.isEmpty)
    let earliest = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", String(metadata.earliestUserText.prefix(32)))).firstMatch
    for _ in 0..<8 where !earliest.exists {
      _ = scrollToHistoryControl("history-local-more")
      pullEarlierHistory()
    }
    XCTAssertTrue(earliest.waitForExistence(timeout: 15), "Real earlier input must enter the rendered window")
    for _ in 0..<25 where !earliest.isHittable { app.scrollViews["message-list"].swipeDown() }
    XCTAssertTrue(earliest.isHittable)
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("history-paging-real-desktop-earliest")
  }

  @MainActor func testPhysicalAgentHubHistoryPagingDEV() throws {
    let values = ProcessInfo.processInfo.environment
    guard let hostId = values["HISTORY_PAGING_HOST_ID"], let threadId = values["HISTORY_PAGING_THREAD_ID"],
      let title = values["HISTORY_PAGING_THREAD_TITLE"], let earliestText = values["HISTORY_PAGING_EARLIEST_TEXT"] else {
      throw XCTSkip("Provide independently verified live history through the private test-run environment")
    }
    // Use the phone's existing connection and ordinary cache; never reset its
    // session, replace credentials, or send a message to the selected chat.
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    switchClient(hostId)
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(title + "\n")
    let thread = app.buttons["recent-thread-" + threadId]
    XCTAssertTrue(thread.waitForExistence(timeout: 25)); thread.tap()
    let control = scrollToHistoryControl("history-local-more")
    XCTAssertLessThanOrEqual(control.frame.height, 50)
    shot("history-paging-physical-compact", fullScreen: true)
    pullEarlierHistory()
    XCTAssertFalse(earliestText.isEmpty)
    let earliest = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", String(earliestText.prefix(32)))).firstMatch
    for _ in 0..<8 where !earliest.exists {
      _ = scrollToHistoryControl("history-local-more")
      pullEarlierHistory()
    }
    XCTAssertTrue(earliest.waitForExistence(timeout: 15))
    for _ in 0..<30 where !earliest.isHittable { app.scrollViews["message-list"].swipeDown() }
    XCTAssertTrue(earliest.isHittable)
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("history-paging-physical-earliest", fullScreen: true)
  }

  @MainActor private func historyPagingReads() async throws -> Int {
    let result = try await historyPagingResults()
    return try XCTUnwrap(result["pageReads"] as? Int)
  }
  @MainActor private func historyPagingResults() async throws -> [String: Any] {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8814/results")!)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }

  @MainActor private func scrollToHistoryControl(_ identifier: String) -> XCUIElement {
    let control = app.buttons[identifier]
    for _ in 0..<30 where !control.isHittable { app.scrollViews["message-list"].swipeDown(velocity: .fast) }
    XCTAssertTrue(control.isHittable, app.debugDescription)
    return control
  }

  @MainActor private func pullEarlierHistory(distance: CGFloat = 180) {
    let list = app.scrollViews["message-list"]
    let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
    start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: distance)))
  }

  @MainActor func testHistoryPagingRemoteSecondLayoutKeepsAnchor() async throws {
    let fixture = try await historyPagingFixture("remote-layout")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_039"].waitForExistence(timeout: 10))
    let control = scrollToHistoryControl("history-more")
    let anchor = app.staticTexts["HISTORY_PAGING_020"]
    let y = anchor.frame.minY
    control.tap()
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_000"].waitForExistence(timeout: 15))
    // A transient 112pt tail settles to 56pt below the old row after the first layout.
    try await Task.sleep(for: .milliseconds(250))
    XCTAssertTrue(anchor.isHittable)
    XCTAssertEqual(anchor.frame.minY, y, accuracy: 3,
      "A second native layout must not invalidate the reading-position compensation")
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 1)
    shot("history-remote-second-layout-anchor")
  }

  @MainActor private func holdLatestHistoryForPaging() async throws -> URL {
    let fixture = try await historyPagingFixture("queued")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_023"].waitForExistence(timeout: 10))
    let base = URL(string: "http://127.0.0.1:8814")!
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("hold-latest"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    var held = false
    for _ in 0..<40 {
      let result = try await historyPagingResults()
      if (result["heldLatest"] as? Int ?? 0) > 0 { held = true; break }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(held)
    return base
  }

  @MainActor func testHistoryPagingRemoteDuringLatestRead() async throws {
    let base = try await holdLatestHistoryForPaging()
    let control = scrollToHistoryControl("history-more")
    XCTAssertTrue(control.isEnabled, "An explicit earlier-page action must queue behind the latest read")
    guard control.isEnabled else {
      _ = try await URLSession.shared.data(from: base.appendingPathComponent("release-latest"))
      return
    }
    let anchor = app.staticTexts["HISTORY_PAGING_020"]
    let y = anchor.frame.minY
    control.tap()
    let pending = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "正在加载"), object: app.buttons["history-more"])
    XCTAssertEqual(XCTWaiter.wait(for: [pending], timeout: 5), .completed)
    let before = try await historyPagingReads()
    XCTAssertEqual(before, 0, "The earlier read must wait rather than overlap the latest read")
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("release-latest"))
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_000"].waitForExistence(timeout: 15))
    XCTAssertEqual(anchor.frame.minY, y, accuracy: 3)
    let after = try await historyPagingReads()
    XCTAssertEqual(after, 1, "One explicit action must issue exactly one earlier-page read")
    let result = try await historyPagingResults()
    XCTAssertEqual(result["maxHistoryReads"] as? Int, 1)

    shot("history-remote-paging-queued-behind-latest")
  }

  @MainActor func testHistoryPagingQueuedSwitchDiscardsRequest() async throws {
    let base = try await holdLatestHistoryForPaging()
    let control = scrollToHistoryControl("history-more")
    XCTAssertTrue(control.isEnabled)
    control.tap()
    let pending = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "正在加载"), object: app.buttons["history-more"])
    XCTAssertEqual(XCTWaiter.wait(for: [pending], timeout: 5), .completed)
    app.buttons["open-sidebar"].tap()
    let other = app.buttons["recent-thread-other-thread"]
    XCTAssertTrue(other.waitForExistence(timeout: 5)); other.tap()
    XCTAssertTrue(app.staticTexts["OTHER_THREAD_HISTORY"].waitForExistence(timeout: 10))
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("release-latest"))
    try await Task.sleep(for: .milliseconds(300))
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 0, "An earlier read queued for the old navigation must never be submitted")
    XCTAssertTrue(app.staticTexts["OTHER_THREAD_HISTORY"].isHittable)
    XCTAssertFalse(app.staticTexts["HISTORY_PAGING_000"].exists)
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
  }

  @MainActor func testHistoryPagingLocalDuringLatestRead() async throws {
    let fixture = try await historyPagingFixture("local")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_071"].waitForExistence(timeout: 10))
    let base = URL(string: "http://127.0.0.1:8814")!
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("hold-latest"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    var held = false
    for _ in 0..<40 {
      let result = try await historyPagingResults()
      if (result["heldLatest"] as? Int ?? 0) > 0 { held = true; break }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(held, "A real latest-history request must remain in flight")
    let control = scrollToHistoryControl("history-local-more")
    XCTAssertTrue(control.isEnabled, "Cached earlier messages must remain available during a latest-page read")
    let anchor = app.staticTexts["HISTORY_PAGING_052"]
    let y = anchor.frame.minY
    control.tap()
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_008"].waitForExistence(timeout: 10))
    XCTAssertEqual(anchor.frame.minY, y, accuracy: 3)
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("release-latest"))
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_008"].exists)
    XCTAssertEqual(anchor.frame.minY, y, accuracy: 3)
    shot("history-local-paging-during-latest-read")
  }

  @MainActor func testHistoryPagingLocalWindow() async throws {
    let fixture = try await historyPagingFixture("local")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_071"].waitForExistence(timeout: 10))
    let control = scrollToHistoryControl("history-local-more")
    XCTAssertLessThanOrEqual(control.frame.height, 50)
    XCTAssertFalse(app.buttons["history-more"].exists)
    shot("history-paging-compact-local")
    // The production initial window is 20 messages, so 72 cached rows start at 52.
    let boundaryY = app.staticTexts["HISTORY_PAGING_052"].frame.minY
    control.tap()
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_052"].isHittable, "Keep the previous reading boundary on screen")
    XCTAssertEqual(app.staticTexts["HISTORY_PAGING_052"].frame.minY, boundaryY, accuracy: 3,
      "The final cached page must preserve the position, including the end control height")
    for _ in 0..<6 where !app.staticTexts["HISTORY_PAGING_000"].isHittable { app.scrollViews["message-list"].swipeDown() }
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_000"].isHittable)
    XCTAssertTrue(app.staticTexts["history-start"].exists)
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 0, "Revealing cached messages must not fetch another page")
    shot("history-paging-local-exhausted")
  }

  @MainActor func testHistoryPagingLocalPullWindow() async throws {
    let fixture = try await historyPagingFixture("local-multiple")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_131"].waitForExistence(timeout: 10))
    _ = scrollToHistoryControl("history-local-more")
    shot("history-paging-before-local-pull")
    pullEarlierHistory(distance: 40)
    XCTAssertFalse(app.staticTexts["HISTORY_PAGING_052"].exists,
      "A small bounce must not load history")
    // 132 - 20 = 112 before expansion; 132 - (20 + 60) = 52 after one pull.
    let boundary = app.staticTexts["HISTORY_PAGING_112"]
    let boundaryY = boundary.frame.minY
    pullEarlierHistory()
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_052"].waitForExistence(timeout: 5),
      "An ordinary top pull must reveal the next cached window")
    XCTAssertFalse(app.staticTexts["HISTORY_PAGING_000"].exists,
      "One pull must reveal only one window")
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_112"].isHittable,
      "Pull expansion must keep the previous reading boundary")
    XCTAssertGreaterThan(boundary.frame.minY, boundaryY + 20,
      "Prepending must retain the released pull instead of snapping back to the resting top")
    _ = scrollToHistoryControl("history-local-more")
    let secondBoundaryY = app.staticTexts["HISTORY_PAGING_052"].frame.minY
    pullEarlierHistory(distance: 500)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_000"].waitForExistence(timeout: 5))
    XCTAssertGreaterThan(app.staticTexts["HISTORY_PAGING_052"].frame.minY, secondBoundaryY + 20,
      "A large pull must retain the released position rather than snapping back")
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 0)
    shot("history-paging-after-local-pull")
  }

  @MainActor func testHistoryPagingPullInMiddleDoesNotExpand() async throws {
    let fixture = try await historyPagingFixture("local")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_071"].waitForExistence(timeout: 10))
    pullEarlierHistory()
    XCTAssertFalse(app.staticTexts["HISTORY_PAGING_000"].exists,
      "Scrolling through history must not expand the earlier window")
    XCTAssertTrue(app.buttons["history-local-more"].exists)
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 0)
  }

  @MainActor func testHistoryPagingPullFailureRetryAndExhaustion() async throws {
    let fixture = try await historyPagingFixture("remote")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_023"].waitForExistence(timeout: 10))
    _ = scrollToHistoryControl("history-more")
    pullEarlierHistory()
    XCTAssertTrue(app.alerts["无法完成操作"].waitForExistence(timeout: 10))
    let failedReads = try await historyPagingReads()
    XCTAssertEqual(failedReads, 1)
    app.alerts.buttons["好"].tap()
    let retry = app.buttons["history-more"]
    XCTAssertTrue(retry.isEnabled)
    let boundaryY = app.staticTexts["HISTORY_PAGING_020"].frame.minY
    pullEarlierHistory()
    let pulledBoundaryY = app.staticTexts["HISTORY_PAGING_020"].frame.minY
    XCTAssertGreaterThan(pulledBoundaryY, boundaryY + 20,
      "A pending page must hold the released pull without an upward bounce")
    let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: retry)
    XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 10), .completed)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_020"].isHittable, "Remote prepend must retain the previous boundary")
    XCTAssertEqual(app.staticTexts["HISTORY_PAGING_020"].frame.minY, pulledBoundaryY, accuracy: 3,
      "A delayed page must fill above the held pull without moving the old content")
    for _ in 0..<12 where !app.staticTexts["history-start"].isHittable { app.scrollViews["message-list"].swipeDown() }
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_000"].isHittable)
    XCTAssertTrue(app.staticTexts["history-start"].isHittable)
    XCTAssertEqual(app.staticTexts.matching(identifier: "HISTORY_PAGING_020").count, 1)
    pullEarlierHistory()
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 2, "Exhausted pulls must not re-fetch the latest page")
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("history-paging-remote-exhausted")
  }

  @MainActor func testHistoryPagingThreadSwitchDiscardsLatePage() async throws {
    let fixture = try await historyPagingFixture("switch")
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["HISTORY_PAGING_023"].waitForExistence(timeout: 10))
    _ = scrollToHistoryControl("history-more")
    pullEarlierHistory()
    app.buttons["open-sidebar"].tap()
    let other = app.buttons["recent-thread-other-thread"]
    XCTAssertTrue(other.waitForExistence(timeout: 5)); other.tap()
    XCTAssertTrue(app.staticTexts["OTHER_THREAD_HISTORY"].waitForExistence(timeout: 10))
    try await Task.sleep(for: .seconds(7))
    XCTAssertTrue(app.staticTexts["OTHER_THREAD_HISTORY"].isHittable)
    XCTAssertFalse(app.staticTexts["HISTORY_PAGING_000"].exists)
    XCTAssertFalse(app.buttons["history-more"].exists)
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    let reads = try await historyPagingReads()
    XCTAssertEqual(reads, 1)
    shot("history-paging-switch-isolated")
  }

  @MainActor func testComputerFileTransferRealDesktopDEV() async throws {
    let metadata = URL(string: "http://127.0.0.1:8821")!
    let (data, response) = try await URLSession.shared.data(from: metadata)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["sidebar-file-transfers"].waitForExistence(timeout: 10))
    shot("file-transfer-sidebar-entry")
    app.buttons["sidebar-file-transfers"].tap()
    let select = app.buttons["choose-transfer-files"]
    let available = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: select)
    XCTAssertEqual(XCTWaiter.wait(for: [available], timeout: 30), .completed)
    XCTAssertTrue(app.staticTexts["file-transfer-destination"].exists)
    shot("file-transfer-empty")
    select.tap()
    let browse = app.buttons.matching(NSPredicate(format: "label == 'Browse' OR label == '浏览'")).firstMatch
    if browse.waitForExistence(timeout: 3) { browse.tap() }
    let phone = app.staticTexts.matching(NSPredicate(format: "label == 'On My iPhone' OR label == '我的 iPhone' OR label == '我的iPhone'")).firstMatch
    if phone.waitForExistence(timeout: 3) { phone.tap() }
    let folder = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Agent Hub'")).firstMatch
    if folder.waitForExistence(timeout: 3) { folder.tap() }
    let file = app.collectionViews.cells.matching(NSPredicate(format: "label CONTAINS 'transfer-input'")).firstMatch
    XCTAssertTrue(file.waitForExistence(timeout: 10), app.debugDescription)
    file.tap()
    let open = app.buttons.matching(NSPredicate(format: "label == 'Open' OR label == '打开'")).firstMatch
    if open.waitForExistence(timeout: 2), open.isEnabled {
      open.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    let completed = app.descendants(matching: .any)["completed-transfer-transfer-input.txt"].firstMatch
    // Files can keep its confirmation sheet after the first tap while its
    // transition settles. Retry only the picker action, before any upload.
    for _ in 0..<2 {
      let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: open)
      if XCTWaiter.wait(for: [dismissed], timeout: 3) == .completed { break }
      if open.exists, open.isEnabled, !completed.exists {
        open.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      }
    }
    XCTAssertTrue(completed.waitForExistence(timeout: 45), app.debugDescription)
    XCTAssertTrue(app.buttons["copy-transfer-path-transfer-input.txt"].exists)
    shot("file-transfer-completed")
    app.buttons["完成"].tap()
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["sidebar-file-transfers"].waitForExistence(timeout: 10))
    app.buttons["sidebar-file-transfers"].tap()
    XCTAssertTrue(completed.waitForExistence(timeout: 30))
    shot("file-transfer-relaunch-receipt")
    app.buttons["完成"].tap()
    let (verified, _) = try await URLSession.shared.data(from: metadata.appendingPathComponent("verified"))
    let value = try XCTUnwrap(try JSONSerialization.jsonObject(with: verified) as? [String: String])
    let path = try XCTUnwrap(value["path"]), expected = try XCTUnwrap(value["content"])
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    chooseDesktopProject(nil)
    composer.tap()
    composer.typeText("文件上传真实验收 IOS_TRANSFER_\(fixture.nonce)：使用工具只读电脑文件 \(path)，完整回复文件正文，不修改文件。")
    sendLiveComposer()
    let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expected)).firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 240))
    shot("file-transfer-original-desktop-model-read")
  }

  @MainActor func testPhysicalComputerFileTransfer() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the existing Agent Hub and system Files on the physical iPhone")
    #else
    let environment = ProcessInfo.processInfo.environment
    let fileName = try XCTUnwrap(environment["TRANSFER_IPHONE_FILENAME"])
    let clients = try XCTUnwrap(environment["TRANSFER_IPHONE_HOST_IDS"]).split(separator: ",").map(String.init)
    let restoreId = try XCTUnwrap(environment["TRANSFER_IPHONE_RESTORE_HOST_ID"])
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 25))
    if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
    for clientId in clients {
      if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
      switchClient(clientId)
      let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["client-" + clientId])
      XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 20), .completed)
      let toolbar = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["open-sidebar"])
      XCTAssertEqual(XCTWaiter.wait(for: [toolbar], timeout: 20), .completed)
      let draft = composer.value as? String
      shot("physical-files-before-sidebar-" + clientId, fullScreen: true)
      app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let entry = app.buttons["sidebar-file-transfers"]
      XCTAssertTrue(entry.waitForExistence(timeout: 15)); entry.tap()
      let select = app.buttons["choose-transfer-files"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: select)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed, app.debugDescription)
      let completedFiles = app.descendants(matching: .any).matching(identifier: "completed-transfer-" + fileName)
      let completedBefore = completedFiles.count
      shot("physical-files-entry-" + clientId, fullScreen: true)
      select.tap()
      let browse = app.buttons.matching(NSPredicate(format: "label == 'Browse' OR label == '浏览'")).firstMatch
      if browse.waitForExistence(timeout: 3) { browse.tap() }
      let phone = app.staticTexts.matching(NSPredicate(format: "label == 'On My iPhone' OR label == '我的 iPhone' OR label == '我的iPhone'")).firstMatch
      if phone.waitForExistence(timeout: 3) { phone.tap() }
      let folder = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Agent Hub'")).firstMatch
      if folder.waitForExistence(timeout: 3) { folder.tap() }
      let file = app.collectionViews.cells.matching(NSPredicate(format: "label CONTAINS %@", fileName.replacingOccurrences(of: ".txt", with: ""))).firstMatch
      XCTAssertTrue(file.waitForExistence(timeout: 15), app.debugDescription); file.tap()
      let open = app.buttons.matching(NSPredicate(format: "label == 'Open' OR label == '打开'")).firstMatch
      if open.waitForExistence(timeout: 2), open.isEnabled {
        open.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      }
      let completed = app.descendants(matching: .any)["completed-transfer-" + fileName].firstMatch
      let anotherCompleted = XCTNSPredicateExpectation(predicate: NSPredicate(format: "count > %d", completedBefore), object: completedFiles)
      XCTAssertEqual(XCTWaiter.wait(for: [anotherCompleted], timeout: 90), .completed, app.debugDescription)
      XCTAssertTrue(app.buttons.matching(identifier: "copy-transfer-path-" + fileName).firstMatch.exists)
      shot("physical-files-completed-" + clientId, fullScreen: true)
      app.buttons["完成"].tap()
      if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
      XCTAssertEqual(composer.value as? String, draft)
      app.terminate(); app.launchArguments = []; app.launchChineseTest()
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 25))
      app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.buttons["sidebar-file-transfers"].waitForExistence(timeout: 15))
      app.buttons["sidebar-file-transfers"].tap()
      XCTAssertTrue(completed.waitForExistence(timeout: 60))
      shot("physical-files-relaunch-" + clientId, fullScreen: true)
      app.buttons["完成"].tap(); app.buttons["close-sidebar"].tap()
      XCTAssertEqual(composer.value as? String, draft)
    }
    switchClient(restoreId)
    #endif
  }

  private struct LiveFixture: Decodable {
    let verifyUnread: Bool?
    var verifyQuestionNotifications: Bool? = nil
    var asyncQuestionTaskPrestarted: Bool? = nil
    let endpoint: String
    let token: String
    let hostId: String
    let threadId: String
    let threadTitle: String?
    var historyThreadArchived: Bool? = nil
    let nonce: String
    let secondEndpoint: String
    let secondToken: String
    let secondHostId: String
    let delayedEndpoint: String
    var switchThreads: [SwitchThread]?
    var creationProjectId: String?
    var creationProjectName: String?
    var emptyProjectId: String?
    var unloadedThreadId: String?
    var unloadedThreadTitle: String?
    var attachmentMarker: String?
    var permissionProbePath: String?
    var failureTurnId: String?
    var chatActionsPartial: Bool?
    var chatActionsActivePin: Bool? = nil
    var effortPosition: Double?
    var notificationSummary: String?
    var notificationKind: String?
    var reuseDeliveredNotification: Bool?
    var suggestionText: String?
    var generationUltrafast: Bool?
    var originalReplyId: String?
    var sharedExistingOutcome: String?
    var sharedLifecycleDone: Bool?
    var sharedApprovalDone: Bool?
    var sharedOriginalDesktop: Bool?
    var sharedNavigationThreadId: String?
    var attachmentOutputDirectory: String?
    var queueHoldSeconds: Int?
    var sharedFollowupDone: Bool?
  }
  private struct SwitchThread: Decodable { let id: String; let title: String }
  @MainActor private func desktopFixture() async throws -> LiveFixture {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return try JSONDecoder().decode(LiveFixture.self, from: data)
  }
  @MainActor private func setChatAppearance(_ appearance: String) {
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["open-settings"].waitForExistence(timeout: 10))
    app.buttons["open-settings"].tap()
    app.descendants(matching: .any)["appearance-picker"].firstMatch.tap()
    app.buttons[appearance].tap()
    app.buttons["完成"].tap()
  }
  @MainActor func testOriginalDesktopHistoricalFailureNotice() async throws {
    guard let (data, response) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8804")!) else {
      throw XCTSkip("Run scripts/receipt-notices-desktop-ios-acceptance.mjs")
    }
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 30), .completed)
    XCTAssertFalse(app.buttons["dismiss-receipt-" + fixture.nonce].exists)
    let marker = "RECEIPT_LOCAL_DRAFT_" + fixture.nonce
    let originalDraft = composer.value as? String ?? ""
    let draft = (originalDraft == "输入消息，长按语音输入" ? "" : originalDraft) + marker
    composer.tap(); typeComposerText(marker)
    XCUIDevice.shared.press(.home)
    app.activate()
    XCTAssertEqual(composer.value as? String, draft)
    app.terminate(); app.launchChineseTest()
    XCTAssertTrue(composer.waitForExistence(timeout: 15))
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(app.buttons["dismiss-receipt-" + fixture.nonce].exists)
    let history = app.descendants(matching: .any)["task-failure-history-turn-failure-" + (fixture.failureTurnId ?? "")].firstMatch
    for _ in 0..<64 {
      if history.exists { break }
      let more = app.buttons["history-local-more"]
      if more.exists { more.tap() }
      else { app.scrollViews["message-list"].swipeDown() }
    }
    XCTAssertTrue(history.exists, "The original failure must remain at its historical turn")
    XCTAssertFalse(app.buttons["dismiss-receipt-" + fixture.nonce].exists)
    shot("historical-failure-real-desktop")
  }
  @MainActor func testReceiptNoticesAndQuestionAppearance() async throws {
    guard let response = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8802")!) else {
      throw XCTSkip("Run scripts/receipt-notices-ios-fixture.mjs; this is a synthetic presentation check")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: response.0)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    func state(_ value: String) async throws {
      var request = URLRequest(url: URL(string: fixture.endpoint + "/state?value=" + value)!)
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      _ = try await URLSession.shared.data(for: request)
      XCTAssertTrue(app.staticTexts["RECEIPT_STATE_" + value].waitForExistence(timeout: 10))
    }
    XCTAssertTrue(app.staticTexts["消息提交失败"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.staticTexts["本次提交被拒绝"].exists)
    try await state("submitted")
    XCTAssertFalse(app.buttons["dismiss-receipt-failed"].exists)
    try await state("task-failed")
    XCTAssertTrue(app.staticTexts["任务执行失败"].exists)
    XCTAssertFalse(app.staticTexts["消息提交失败"].exists)
    XCTAssertTrue(app.staticTexts["Remote compaction connection failed"].exists)
    app.buttons["dismiss-receipt-failed"].tap()
    XCTAssertFalse(app.staticTexts["任务执行失败"].exists)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["RECEIPT_STATE_task-failed"].waitForExistence(timeout: 15))
    XCTAssertFalse(app.buttons["dismiss-receipt-failed"].exists)
    let secondarySearch = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    secondarySearch.tap(); secondarySearch.typeText("receipt-secondary\n")
    app.buttons["recent-thread-receipt-secondary"].tap()
    XCTAssertTrue(app.buttons["dismiss-receipt-failed"].waitForExistence(timeout: 10), "Closing a notice must be scoped to its full target")
    openDesktopTestConversation(fixture)
    try await state("desktop-continued")
    XCTAssertFalse(app.buttons["dismiss-receipt-old-task"].exists)
    XCTAssertTrue(app.staticTexts["任务失败记录"].exists)
    XCTAssertTrue(app.staticTexts["Remote compaction connection failed"].exists)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["RECEIPT_STATE_desktop-continued"].waitForExistence(timeout: 15))
    XCTAssertFalse(app.buttons["dismiss-receipt-old-task"].exists)
    try await state("uncertain-desktop-continued")
    XCTAssertTrue(app.staticTexts["消息执行结果待确认"].exists)
    XCTAssertFalse(app.buttons["dismiss-receipt-old-task"].exists)
    try await state("uncertain-with-success")
    XCTAssertTrue(app.staticTexts["消息执行结果待确认"].exists)
    XCTAssertFalse(app.buttons["dismiss-receipt-unknown"].exists)
    XCTAssertFalse(app.buttons["dismiss-receipt-failed"].exists)
    try await state("new-failed")
    XCTAssertTrue(app.staticTexts["消息提交失败"].exists)
    XCTAssertTrue(app.buttons["dismiss-receipt-new-failure"].exists)
    try await state("question")
    let submit = app.buttons["answer-question"]
    let option = app.buttons["question-option-format-简洁版"]
    let custom = app.descendants(matching: .any)["question-custom-format"].firstMatch
    XCTAssertFalse(submit.isEnabled)
    setChatAppearance("深色")
    shot("approval-dark-disabled")
    option.tap()
    XCTAssertTrue(submit.isEnabled)
    custom.tap(); custom.typeText("补充说明")
    app.scrollViews["message-list"].swipeDown()
    shot("approval-dark-enabled-custom")
    setChatAppearance("浅色")
    // Resign the custom answer field before tapping the button behind the
    // floating composer. XCTest reports that covered button as hittable.
    focusLiveComposer()
    app.buttons["composer-dismiss-keyboard"].tap()
    XCTAssertFalse(app.keyboards.firstMatch.waitForExistence(timeout: 1))
    app.scrollViews["message-list"].swipeUp()
    shot("approval-light-enabled-custom")
    XCTAssertTrue(submit.isEnabled)
    tapAISharingAction(submit)
    XCTAssertTrue(app.staticTexts["QUESTION_ANSWERED"].waitForExistence(timeout: 10))
    XCTAssertFalse(submit.exists)
  }
  @MainActor func testOriginalDesktopQuestionAppearance() async throws {
    let fixture = try await desktopFixture()
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let marker = "QUESTION_UI_" + fixture.nonce
    focusLiveComposer()
    typeComposerText("专项手机提问验收：请先用 request_user_input 工具询问我一个问题，id 为 format，header 为交付格式，question 为请选择交付格式，提供简洁版和详细版两个选项。必须实际调用提问工具，不要用普通文字代替。收到回答后仅回复 \(marker)，不要执行其他工具。")
    // The upstream synchronous question tool requires plan mode.
    app.buttons["composer-dismiss-keyboard"].tap()
    setComposerPlanMode(true)
    sendLiveComposer()
    let submit = app.buttons["answer-question"]
    XCTAssertTrue(submit.waitForExistence(timeout: 180), app.debugDescription)
    XCTAssertFalse(submit.isEnabled)
    setChatAppearance("深色")
    shot("real-desktop-question-dark-disabled")
    let option = app.buttons["question-option-format-简洁版"]
    XCTAssertTrue(option.exists)
    option.tap()
    let custom = app.descendants(matching: .any)["question-custom-format"].firstMatch
    XCTAssertTrue(custom.exists)
    custom.tap(); custom.typeText("补充说明")
    app.scrollViews["message-list"].swipeDown()
    XCTAssertTrue(submit.isEnabled)
    shot("real-desktop-question-dark-enabled")
    setChatAppearance("浅色")
    shot("real-desktop-question-light-enabled")
    submit.tap()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 180))
    XCTAssertFalse(submit.exists)
    XCTAssertFalse(app.staticTexts["消息提交失败"].exists)
    shot("real-desktop-question-completed")
  }
  @MainActor private func liveFixture() async throws -> LiveFixture {
    guard
      let result = try? await URLSession.shared.data(
        from: URL(string: "http://127.0.0.1:8791")!),
      let fixture = try? JSONDecoder().decode(LiveFixture.self, from: result.0)
    else {
      throw XCTSkip("Run the live iOS acceptance harness")
    }
    return fixture
  }
  @MainActor private func selectConnectionMethod(_ title: String, agent: String) {
    let segments = app.segmentedControls[agent + "-connection-method"]
    // The sheet can still be presenting after Add Client returns. Give its
    // current segmented control time to appear before trying the legacy menu.
    if segments.waitForExistence(timeout: 10) {
      let option = segments.buttons[title]
      XCTAssertTrue(option.waitForExistence(timeout: 10))
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: option)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
      option.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let formFieldId: String? = title == "手动连接"
        ? (agent == "easywork" ? "easywork-manual-address" : "client-address")
        : (title == "扫码连接" && agent == "codex" ? "codex-qr-description" : nil)
      if let formFieldId {
        if !app.textFields[formFieldId].waitForExistence(timeout: 3), !option.isSelected {
          // Discovery can finish while the connection sheet is presenting.
          // Selecting this same local form again does not submit a connection.
          print("CONNECTION_FORM_RESELECT", agent, title)
          option.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(app.textFields[formFieldId].waitForExistence(timeout: 10))
      }
      return
    }
    let picker = app.buttons[agent + "-connection-method"].firstMatch
    XCTAssertTrue(picker.waitForExistence(timeout: 10), app.debugDescription)
    let choice = app.buttons[title].firstMatch
    for _ in 0..<2 {
      if !choice.exists { picker.tap() }
      // The native menu can move while discovery rows arrive. A hittable
      // frame alone does not prove its presentation has finished.
      var previous = CGRect.null
      let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard choice.exists, choice.isHittable, choice.isEnabled else { return false }
        let frame = choice.frame
        defer { previous = frame }
        return !frame.isNull && !frame.isInfinite && !frame.isEmpty && frame == previous
      }, object: choice)
      XCTAssertEqual(XCTWaiter.wait(for: [stable], timeout: 10), .completed)
      choice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard !choice.exists, picker.label.contains(title) else { return false }
        if title == "手动连接" {
          let addressId = agent == "easywork" ? "easywork-manual-address" : "client-address"
          return self.app.textFields[addressId].exists
        }
        return true
      }, object: picker)
      if XCTWaiter.wait(for: [selected], timeout: 10) == .completed { return }
    }
    shot("connection-method-selection-failure", fullScreen: true)
    XCTFail("The named connection method and its selected form must appear")
  }
  @MainActor private func dismissLiveQuestionPresentation(wait: TimeInterval = 0) {
    let close = app.buttons["close-async-questions"]
    guard wait > 0 ? close.waitForExistence(timeout: wait) : close.exists else { return }
    let window = app.windows.firstMatch
    func visibleCloseFrame() -> (control: CGRect, window: CGRect)? {
      guard close.exists, close.isEnabled, window.exists else { return nil }
      let frame = close.frame, bounds = window.frame
      guard [frame.minX, frame.minY, frame.width, frame.height,
        bounds.minX, bounds.minY, bounds.width, bounds.height].allSatisfy({ $0.isFinite }),
        frame.width > 1, frame.height > 1, bounds.width > 1, bounds.height > 1,
        bounds.contains(frame) else { return nil }
      return (frame, bounds)
    }
    // SwiftUI may expose the sheet's final AX frame while its presentation is
    // still moving. The close button's hittability query can itself fail in AX;
    // require a stable visible frame, then tap its center from the window.
    for _ in 0..<2 {
      var previous = CGRect.null
      let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard let (frame, _) = visibleCloseFrame() else { previous = .null; return false }
        defer { previous = frame }
        return !previous.isNull && previous == frame
      }, object: close)
      let settled = XCTWaiter.wait(for: [stable], timeout: 10)
      guard settled == .completed else { XCTAssertEqual(settled, .completed); return }
      guard let (frame, bounds) = visibleCloseFrame(), frame == previous else { continue }
      window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        .withOffset(CGVector(dx: frame.midX - bounds.midX, dy: frame.midY - bounds.midY)).tap()
      let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: close)
      if XCTWaiter.wait(for: [dismissed], timeout: 3) == .completed { return }
    }
    shot("desktop-question-presentation-dismissal-failure")
    XCTFail("Close only the question presentation before navigating; never answer or skip")
  }
  @MainActor private func connectLive(
    _ fixture: LiveFixture, second: Bool = false, endpoint: String? = nil, supportsCreation: Bool = true,
    fromSettings: Bool = false, onConnecting: (() -> Void)? = nil, onConnected: (() -> Void)? = nil, nickname: String? = nil,
    afterConnectTap: (() -> Void)? = nil
  ) {
    if app.alerts["无法完成操作"].exists { app.alerts["无法完成操作"].buttons["好"].tap() }
    dismissLiveQuestionPresentation()
    if fromSettings {
      app.buttons["open-sidebar"].tap(); app.buttons["open-settings"].tap()
      XCTAssertTrue(app.buttons["settings-clients"].waitForExistence(timeout: 10)); app.buttons["settings-clients"].tap()
    } else { app.buttons["client-switch"].tap() }
    XCTAssertTrue(app.buttons["完成"].waitForExistence(timeout: 10))
    let addClient = app.buttons["add-client"]
    for _ in 0..<6 {
      if addClient.exists && addClient.isHittable { break }
      app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.88)).press(forDuration: 0.05,
        thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.58)))
    }
    XCTAssertTrue(addClient.waitForExistence(timeout: 10))
    addClient.tap()
    selectConnectionMethod("手动连接", agent: "codex")
    shot("codex-manual-connection-form")
    if name.contains("testPhysicalAgentHubFeature"), let title = fixture.threadTitle {
      let nickname = app.textFields["client-name"]
      XCTAssertTrue(nickname.waitForExistence(timeout: 10)); nickname.tap()
      nickname.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (nickname.value as? String ?? "").count) + title)
    }
    let address = app.textFields["client-address"]
    // A conditional Form can publish the picker selection before its fields.
    // Wait for the selected form before scrolling, which can hit the picker.
    XCTAssertTrue(address.waitForExistence(timeout: 15), app.debugDescription)
    for _ in 0..<6 {
      if address.exists && address.isHittable { break }
      let form = app.collectionViews.firstMatch
      if form.exists { form.swipeUp() } else { app.swipeUp() }
    }
    XCTAssertTrue(address.waitForExistence(timeout: 15))
    let addressReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: address)
    XCTAssertEqual(XCTWaiter.wait(for: [addressReady], timeout: 20), .completed)
    if let nickname {
      let name = app.textFields["client-name"]
      name.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (name.value as? String ?? "").count))
      name.typeText(nickname)
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
    }
    address.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    if !app.keyboards.firstMatch.waitForExistence(timeout: 5) { address.tap() }
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    address.typeText(
      String(
        repeating: XCUIKeyboardKey.delete.rawValue,
        count: (address.value as? String ?? "").count))
    address.typeText(endpoint ?? (second ? fixture.secondEndpoint : fixture.endpoint))
    if app.buttons["完成输入"].exists {
      app.buttons["完成输入"].tap()
      let keyboardHidden = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
      if XCTWaiter.wait(for: [keyboardHidden], timeout: 5) != .completed {
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed)
      }
    }
    let token = app.secureTextFields["client-token"]
    let tokenReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: token)
    XCTAssertEqual(XCTWaiter.wait(for: [tokenReady], timeout: 10), .completed)
    token.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    token.typeText(second ? fixture.secondToken : fixture.token)
    if app.buttons["完成输入"].exists {
      app.buttons["完成输入"].tap()
      let keyboardHidden = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
      if XCTWaiter.wait(for: [keyboardHidden], timeout: 5) != .completed {
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed)
      }
    }
    let connect = app.buttons["connect-client"]
    let connectReady = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "isHittable == true"), object: connect)
    XCTAssertEqual(XCTWaiter.wait(for: [connectReady], timeout: 10), .completed)
    onConnecting?()
    connect.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    afterConnectTap?()
    let connected = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"),
      object: app.secureTextFields["client-token"])
    if XCTWaiter.wait(for: [connected], timeout: 90) != .completed {
      let formError = app.staticTexts["client-form-error"].firstMatch
      shot("desktop-live-connection-failure")
      XCTFail("Live connection failed: \(formError.exists ? formError.label : "连接仍在处理中")")
      return
    }
    onConnected?()
    if name.contains("testPhysicalAgentHubFeature") || name.contains("testOriginalDesktopReadOnlySuggestionsDEV") {
      dismissPhysicalPasswordPrompt()
    }
    XCTAssertTrue(app.buttons["new-chat"].waitForExistence(timeout: 25))
    dismissSystemPasswordOffer()
    // A live connection can initially select an unrelated running thread.
    // Only dismiss its presentation; never answer or skip its pending question.
    dismissLiveQuestionPresentation(wait: 2)
    // The full-height history can obscure the floating button's AX hit-test
    // frame on iOS 27. Verify its actual navigation instead of that AX flag.
    let closeSidebar = app.buttons["close-sidebar"]
    if !closeSidebar.exists {
      let sidebar = app.buttons["open-sidebar"]
      var previousFrame = CGRect.null
      let mainReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        guard sidebar.exists, sidebar.isEnabled else { return false }
        let frame = sidebar.frame
        guard !frame.isNull, !frame.isInfinite, !frame.isEmpty,
          self.app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
        defer { previousFrame = frame }
        return frame == previousFrame
      }, object: sidebar)
      XCTAssertEqual(XCTWaiter.wait(for: [mainReady], timeout: 25), .completed)
      sidebar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(closeSidebar.waitForExistence(timeout: 10))
    }
    closeSidebar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let sidebarClosed = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"), object: closeSidebar)
    XCTAssertEqual(XCTWaiter.wait(for: [sidebarClosed], timeout: 10), .completed)
    if !supportsCreation { return }
    let enabled = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "isEnabled == true"), object: app.buttons["new-chat"])
    XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 20), .completed)
  }

  @MainActor private func dismissSystemPasswordOffer() {
    // Host keys already live in the app's Keychain. Never save synthetic keys
    // to Passwords; only handle this specific system offer, not other alerts.
    let roots = [XCUIApplication(bundleIdentifier: "com.apple.springboard"), app!]
    let title = NSPredicate(format: "label CONTAINS '保存密码' OR label CONTAINS 'Save Password'")
    var presenter: XCUIApplication?
    // AutoFill is a system presentation and can arrive after the connection
    // sheet has disappeared. The app's empty remote window hides its controls.
    let offered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      presenter = roots.first { $0.staticTexts.matching(title).firstMatch.exists }
      return presenter != nil
    }, object: app!)
    if XCTWaiter.wait(for: [offered], timeout: 15) == .completed, let presenter {
      let offer = presenter.staticTexts.matching(title).firstMatch
      let later = presenter.buttons.matching(NSPredicate(
        format: "label == '以后' OR label == 'Not Now'" )).firstMatch
      // AutoFill can publish a hittable frame before its presentation settles.
      // Only retry this named system dismissal, never a business command.
      for _ in 0..<2 {
        var previousFrame = CGRect.null
        let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
          guard later.exists, later.isHittable, later.isEnabled else { return false }
          let frame = later.frame
          let unchanged = frame == previousFrame && !frame.isEmpty
          previousFrame = frame
          return unchanged
        }, object: later)
        XCTAssertEqual(XCTWaiter.wait(for: [stable], timeout: 10), .completed)
        later.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let dismissed = XCTNSPredicateExpectation(
          predicate: NSPredicate(format: "exists == false"), object: offer)
        if XCTWaiter.wait(for: [dismissed], timeout: 3) == .completed { return }
      }
      shot("system-password-offer-dismissal-failed", fullScreen: true)
      XCTFail("The system Save Password offer must close before continuing")
    }
  }

  @MainActor private func focusLiveComposer(requireSoftwareKeyboard: Bool = true) {
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed, app.debugDescription)
    composer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    if requireSoftwareKeyboard {
      if !app.keyboards.firstMatch.waitForExistence(timeout: 5) { composer.tap() }
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), app.debugDescription)
    }
  }
  @MainActor private func sendLiveComposer(attachmentNonce: String? = nil) {
    let send = app.buttons["send-message"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: send)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed, app.debugDescription)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let hittable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: send)
    XCTAssertEqual(XCTWaiter.wait(for: [hittable], timeout: 15), .completed, app.debugDescription)
    if let attachmentNonce {
      print("QUICK_ATTACHMENT_SEND_NATIVE_AUTHORIZED " + attachmentNonce); fflush(stdout)
    }
    send.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    if allowTestAISharingIfNeeded() {
      XCTAssertTrue(send.waitForExistence(timeout: 10))
      send.tap()
    }
  }

  @MainActor private func tapAISharingAction(_ action: XCUIElement) {
    action.tap()
    // Allowing is a separate UI step and must not submit the captured action.
    // Retry only after this exact permission sheet was explicitly accepted.
    if allowTestAISharingIfNeeded() {
      XCTAssertTrue(action.waitForExistence(timeout: 10))
      XCTAssertTrue(action.isEnabled)
      action.tap()
    }
  }
  @MainActor private func allowTestAISharingIfNeeded() -> Bool {
    let allow = app.buttons["ai-sharing-allow"]
    guard allow.waitForExistence(timeout: 2) else { return false }
    for _ in 0..<6 where !allow.isHittable {
      if app.collectionViews.firstMatch.exists { app.collectionViews.firstMatch.swipeUp() }
      else { app.swipeUp() }
    }
    XCTAssertTrue(allow.isHittable, app.debugDescription)
    allow.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: allow)], timeout: 10), .completed)
    return true
  }
  @MainActor func testEasyWorkPinnedSidebarDEV() async throws {
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8807")!) else {
      throw XCTSkip("Requires the actual EasyWork desktop LAN descriptor and a temporary desktop pin")
    }
    let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
    app.terminate()
    app.launchArguments = ["-ui-testing", "-easywork-local-dev"]
    app.launchEnvironment = ["EASYWORK_DEV_LAN_OFFER": try XCTUnwrap(metadata["pairingUri"])]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    app.buttons["EasyWork"].firstMatch.tap(); selectConnectionMethod("扫码连接", agent: "easywork")
    let pair = app.buttons["easywork-pair-lan"]
    XCTAssertTrue(pair.waitForExistence(timeout: 15)); pair.tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "EasyWork"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 90), .completed, app.debugDescription)
    if app.navigationBars["客户端"].exists { app.navigationBars["客户端"].buttons["完成"].tap() }
    app.buttons["open-sidebar"].tap()
    let toggle = app.buttons["sidebar-pinned-toggle"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 30), app.debugDescription)
    if toggle.value as? String == "已折叠" { toggle.tap() }
    let pins = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pinned-thread-'"))
    XCTAssertEqual(pins.count, 1)
    XCTAssertEqual(pins.firstMatch.label, try XCTUnwrap(metadata["title"]))
    shot("easywork-real-desktop-pin-synced")
    toggle.tap(); XCTAssertEqual(toggle.value as? String, "已折叠")
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchEnvironment = [:]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20)); app.buttons["open-sidebar"].tap()
    XCTAssertTrue(toggle.waitForExistence(timeout: 30)); XCTAssertEqual(toggle.value as? String, "已折叠")
    toggle.tap(); XCTAssertEqual(pins.count, 1)
    XCTAssertEqual(pins.firstMatch.label, try XCTUnwrap(metadata["title"]))
    shot("easywork-real-desktop-pin-relaunch")
  }

  @MainActor func testOriginalDesktopPinnedSidebarDEV() async throws {
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806")!) else {
      throw XCTSkip("Run scripts/pinned-receipts-desktop-ios-acceptance.mjs")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    let pins = try XCTUnwrap(metadata["pinnedThreadIds"] as? [String])
    XCTAssertFalse(pins.isEmpty, "Select an environment with actual desktop pins")
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    app.buttons["open-sidebar"].tap()
    let toggle = app.buttons["sidebar-pinned-toggle"]
    XCTAssertTrue(toggle.waitForExistence(timeout: 30))
    if toggle.value as? String == "已折叠" { toggle.tap() }
    let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pinned-thread-'"))
    XCTAssertEqual(rows.count, pins.count)
    for (index, id) in pins.enumerated() { XCTAssertEqual(rows.element(boundBy: index).identifier, "pinned-thread-" + id) }
    XCTAssertLessThan(toggle.frame.minY, app.buttons["sidebar-recent-toggle"].frame.minY)
    shot("real-desktop-pinned-sidebar")
    toggle.tap(); XCTAssertEqual(toggle.value as? String, "已折叠")
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20)); app.buttons["open-sidebar"].tap()
    XCTAssertTrue(toggle.waitForExistence(timeout: 30)); XCTAssertEqual(toggle.value as? String, "已折叠")
    app.buttons["close-sidebar"].tap()
    openDesktopTestConversation(fixture)
    let generation = app.buttons["generation-settings"]
    XCTAssertTrue(generation.waitForExistence(timeout: 30)); generation.tap()
    let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
    XCTAssertTrue(effort.waitForExistence(timeout: 15)); effort.tap()
    app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
    focusLiveComposer()
    let marker = "PIN_RECEIPT_" + fixture.nonce
    composer.typeText("专用置顶及回执验收：仅原样回复 \(marker)，不要使用工具，不读取或修改文件。")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 180))
    let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 45), .completed)
    XCTAssertFalse(app.staticTexts["消息执行结果待确认"].exists)
    shot("real-desktop-pinned-model-completed")
  }

  @MainActor func testSendDuringSlowBackgroundSync() async throws {
    let base = "http://127.0.0.1:8897"
    func control(_ path: String) async throws -> [String: Any] {
      let (data, _) = try await URLSession.shared.data(from: URL(string: base + path)!)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    let (data, _) = try await URLSession.shared.data(from: URL(string: base)!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let baseline = try await control("/sync/status")["baseline"] as? Bool == true
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["SLOW_SYNC_READY"].waitForExistence(timeout: 30))
    focusLiveComposer(); composer.typeText("single-tap-during-slow-sync")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    _ = try await control("/sync/arm")
    var active = false
    for _ in 0..<40 {
      if try await control("/sync/status")["active"] as? Bool == true { active = true; break }
      try await Task.sleep(for: .milliseconds(200))
    }
    XCTAssertTrue(active, "The slow history response must be in flight at the click")
    let send = app.buttons["send-message"]
    print("SLOW_SYNC_BUTTON", send.isEnabled, "SPINNER", app.descendants(matching: .any)["history-loading"].firstMatch.exists)
    if baseline {
      XCTAssertFalse(send.isEnabled, "Reproduce the original send lock during background sync")
      XCTAssertTrue(app.descendants(matching: .any)["history-loading"].firstMatch.exists)
      _ = try await control("/sync/release")
      return
    }
    XCTAssertTrue(send.isEnabled, "Receiving history must not lock an already ready composer")
    XCTAssertFalse(app.descendants(matching: .any)["history-loading"].firstMatch.exists)
    send.tap()
    if allowTestAISharingIfNeeded() { send.tap() }
    var submitted = false
    for _ in 0..<25 {
      if try await control("/sync/status")["replies"] as? Int == 1 { submitted = true; break }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(submitted, "One click submits without waiting for the slow history response")
    let afterSubmit = try await control("/sync/status")
    XCTAssertTrue(afterSubmit["active"] as? Bool == true)
    _ = try await control("/sync/release")
    XCTAssertTrue(app.staticTexts["SLOW_SYNC_DONE_1"].waitForExistence(timeout: 20))
    XCTAssertFalse(app.alerts.firstMatch.exists)
  }

  private var slowSyncBaseURL: String { ProcessInfo.processInfo.environment["SYNC_FIXTURE_URL"] ?? "http://127.0.0.1:8795" }
  private func slowSyncRequest(_ path: String) async throws -> Data {
    var request = URLRequest(url: URL(string: slowSyncBaseURL + path)!)
    if let secret = ProcessInfo.processInfo.environment["SYNC_FIXTURE_SECRET"] { request.setValue(secret, forHTTPHeaderField: "X-Sync-Fixture-Secret") }
    let (data, response) = try await URLSession.shared.data(for: request)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return data
  }
  private func slowSyncControl(_ path: String) async throws -> [String: Any] {
    let data = try await slowSyncRequest(path)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }
  @MainActor func testOriginalDesktopSendDuringSlowSync() async throws {
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: await slowSyncRequest("/fixture"))
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    try await runSlowSyncAcceptance(fixture, physical: false)
  }
  @MainActor func testPhysicalSlowNetworkSendDEV() async throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["SYNC_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(encoded.utf8))
    app.terminate(); app.activate()
    app.buttons["client-switch"].tap()
    let original = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND isSelected == true", "client-")).firstMatch
    for _ in 0..<8 {
      if original.exists { break }
      app.swipeUp()
    }
    XCTAssertTrue(original.exists, "The original selected client must be captured before adding a temporary connection")
    let originalId = ProcessInfo.processInfo.environment["SYNC_ORIGINAL_CLIENT_ID"] ?? String(original.identifier.dropFirst("client-".count))
    print("SLOW_SYNC_PHYSICAL_ORIGINAL_CLIENT", originalId)
    app.buttons["完成"].tap()
    try await runSlowSyncAcceptance(fixture, physical: true)
    restoreSlowNetworkClient(temporaryId: fixture.hostId, originalId: originalId)
  }
  @MainActor func testPhysicalSlowNetworkRestoreDEV() throws {
    let environment = ProcessInfo.processInfo.environment
    let temporaryId = try XCTUnwrap(environment["SYNC_TEMPORARY_CLIENT_ID"])
    let originalId = try XCTUnwrap(environment["SYNC_ORIGINAL_CLIENT_ID"])
    app.terminate(); app.activate()
    restoreSlowNetworkClient(temporaryId: temporaryId, originalId: originalId)
    app.terminate(); app.activate()
    app.buttons["client-switch"].tap()
    let restored = app.buttons["client-" + originalId]
    for _ in 0..<8 {
      if restored.exists && restored.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(restored.isSelected, "The original client must remain selected after an ordinary restart")
    XCTAssertFalse(app.buttons["client-" + temporaryId].exists)
    app.buttons["完成"].tap()
    print("SLOW_SYNC_PHYSICAL_RESTORE_RESTART passed")
  }

  private struct ReadOnlySuggestionFixture: Decodable {
    let endpoint: String
    let token: String
    let hostId: String
    let threadAId: String
    let threadATitle: String
    let sourceATurnId: String
    let textA: String
    let threadBId: String
    let threadBTitle: String
    let sourceBTurnId: String
    let textB: String
    var originalClientId: String?
    var nonce: String?
    var adoptionVerification: Bool?
    var recoverFirstDraftText: String?
    var existingConnection: Bool?
    var bundleId: String?
    var tracePhoneIsolatedBaseline: TracePhoneUIBaseline?

    func connection(threadId: String, title: String) throws -> LiveFixture {
      try JSONDecoder().decode(LiveFixture.self, from: JSONSerialization.data(withJSONObject: [
        "endpoint": endpoint, "token": token, "hostId": hostId, "threadId": threadId,
        "threadTitle": title, "nonce": nonce ?? "read-only-suggestions", "secondEndpoint": "",
        "secondToken": "", "secondHostId": "", "delayedEndpoint": "",
      ]))
    }
  }

  // TRACEPHONE_ISOLATED_UI_BASELINE_V1: ordinary App UI only; no persistence hook.
  private struct TracePhoneUIBaseline: Decodable {
    struct Draft: Decodable {
      let clientId: String
      let threadId: String
      let textSha256: String
      let localOnly: Bool
    }
    struct MainM4: Decodable {
      let endpoint: String
      let token: String
      let hostId: String
      let threadId: String
      let threadTitle: String
      let draftText: String

      func connection(taskId: String) throws -> LiveFixture {
        try JSONDecoder().decode(LiveFixture.self, from: JSONSerialization.data(withJSONObject: [
          "endpoint": endpoint, "token": token, "hostId": hostId, "threadId": threadId,
          "threadTitle": threadTitle, "nonce": taskId, "secondEndpoint": "",
          "secondToken": "", "secondHostId": "", "delayedEndpoint": "",
        ]))
      }
    }
    let schemaVersion: Int
    let contract: String
    let bundleId: String
    let taskId: String
    let provenance: String
    let clientIds: [String]
    let m4ClientId: String
    let qa5ffClientId: String
    let drafts: [Draft]
    let snapshotSha256: String
    let mainM4: MainM4
  }

  private func tracePhoneUIHash(_ text: String) -> String {
    SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  @MainActor private func tracePhonePrepareUIBaseline(_ baseline: TracePhoneUIBaseline, fixture: ReadOnlySuggestionFixture) throws {
    XCTAssertEqual(fixture.bundleId, baseline.bundleId)
    XCTAssertNotNil(baseline.bundleId.range(of: #"^com\.easyai\.agenthub\.traceacceptance(?:\.[a-z0-9]+)*$"#, options: .regularExpression))
    XCTAssertEqual(baseline.schemaVersion, 1)
    XCTAssertEqual(baseline.contract, "TRACEPHONE_ISOLATED_BASELINE_V1")
    XCTAssertEqual(baseline.provenance, "dedicated-private-fixture-started-empty")
    XCTAssertFalse(baseline.taskId.isEmpty)
    XCTAssertEqual(fixture.originalClientId, baseline.m4ClientId)
    XCTAssertEqual(fixture.hostId, baseline.qa5ffClientId)
    XCTAssertNotEqual(baseline.m4ClientId, baseline.qa5ffClientId)
    XCTAssertEqual(baseline.clientIds, [baseline.m4ClientId], "Only the new task-owned M4 belongs to this fresh UI baseline")
    XCTAssertEqual(baseline.mainM4.hostId, baseline.m4ClientId)
    XCTAssertNotNil(UUID(uuidString: baseline.m4ClientId))
    XCTAssertNotNil(UUID(uuidString: baseline.qa5ffClientId))
    XCTAssertNotNil(UUID(uuidString: baseline.mainM4.threadId))
    XCTAssertFalse(baseline.mainM4.threadTitle.isEmpty)
    let endpoint = try XCTUnwrap(URLComponents(string: baseline.mainM4.endpoint))
    XCTAssertEqual(endpoint.scheme, "https")
    XCTAssertFalse(endpoint.host?.isEmpty ?? true)
    XCTAssertTrue(endpoint.path.isEmpty || endpoint.path == "/")
    XCTAssertNil(endpoint.user)
    XCTAssertNil(endpoint.password)
    XCTAssertNil(endpoint.query)
    XCTAssertNil(endpoint.fragment)
    XCTAssertFalse(baseline.mainM4.token.isEmpty)
    XCTAssertNotNil(baseline.mainM4.draftText.range(of: #"^TRACEPHONE_LOCAL_DRAFT_[A-Za-z0-9_-]{8,128}$"#, options: .regularExpression))
    XCTAssertEqual(baseline.drafts.count, 1)
    let draft = try XCTUnwrap(baseline.drafts.first)
    XCTAssertEqual(draft.clientId, baseline.m4ClientId)
    XCTAssertEqual(draft.threadId, baseline.mainM4.threadId)
    XCTAssertTrue(draft.localOnly)
    XCTAssertEqual(draft.textSha256, tracePhoneUIHash(baseline.mainM4.draftText))
    XCTAssertEqual(fixture.adoptionVerification, true)
    XCTAssertFalse(fixture.existingConnection == true)
    XCTAssertNil(fixture.recoverFirstDraftText)

    // The existing scanner requires exactly one selected client. Check the
    // truly empty picker separately, then keep that scanner unchanged below.
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "ui-baseline-empty"))
    XCTAssertTrue(readOnlySuggestionClientListReady())
    let rows = app.collectionViews.firstMatch.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "client-"))
    XCTAssertEqual(rows.count, 0, "Never seed or clear an App containing any existing client")
    XCTAssertTrue(app.buttons["add-client"].exists && app.buttons["add-client"].isHittable)
    print("SUGGESTION_DEV_UI_EMPTY_CLIENTS", baseline.bundleId)
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "ui-baseline-empty-close"))
    try tracePhoneAddClientUI(baseline.mainM4.connection(taskId: baseline.taskId), nickname: "MainM4 TracePhone")
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "ui-baseline-added"))
    let clients = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertEqual(clients.ids, Set(baseline.clientIds))
    XCTAssertEqual(clients.selectedId, baseline.m4ClientId)
    let main = try XCTUnwrap(findReadOnlySuggestionClient(baseline.m4ClientId))
    XCTAssertTrue(tapReadOnlySuggestionControl(main, step: "ui-baseline-select-M4", allowSheet: true))
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "ui-baseline-selected-M4", automatic: true))
    print("SUGGESTION_DEV_UI_MAIN_ADDED", baseline.m4ClientId)

    openDesktopTestConversation(try baseline.mainM4.connection(taskId: baseline.taskId))
    XCTAssertTrue(composer.waitForExistence(timeout: 30))
    expectSuggestionDraft("")
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    XCTAssertTrue(tapReadOnlySuggestionControl(composer, step: "ui-baseline-marker-focus"))
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    composer.typeText(baseline.mainM4.draftText)
    expectSuggestionDraft(baseline.mainM4.draftText)
    if app.buttons["composer-dismiss-keyboard"].exists {
      XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["composer-dismiss-keyboard"], step: "ui-baseline-marker-keyboard"))
    }
    // Navigate away and back before measuring: this is the App's saved local
    // composer draft, not just the value immediately after typeText.
    try tracePhoneVerifyUIBaseline(baseline, phase: "before")
  }

  /// Reuses the normal Add Client form within the exact-bundle App. Unlike the
  /// broad live-test helper it never queries/acts on SpringBoard or opens URLs.
  @MainActor private func tracePhoneAddClientUI(_ fixture: LiveFixture, nickname: String, onConnected: (() -> Void)? = nil) throws {
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "ui-add-client-picker"))
    let add = app.buttons["add-client"]
    XCTAssertTrue(add.waitForExistence(timeout: 10))
    XCTAssertTrue(tapReadOnlySuggestionControl(add, step: "ui-add-client", allowSheet: true))
    selectConnectionMethod("手动连接", agent: "codex")
    let address = app.textFields["client-address"]
    XCTAssertTrue(address.waitForExistence(timeout: 15))
    for _ in 0..<6 where !address.isHittable { app.collectionViews.firstMatch.swipeUp() }
    for (field, value, step) in [
      (app.textFields["client-name"], nickname, "name"),
      (address, fixture.endpoint, "address"),
    ] {
      XCTAssertTrue(field.waitForExistence(timeout: 10))
      XCTAssertTrue(tapReadOnlySuggestionControl(field, step: "ui-add-" + step, allowSheet: true))
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (field.value as? String ?? "").count))
      field.typeText(value)
      if app.buttons["完成输入"].exists {
        XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["完成输入"], step: "ui-add-" + step + "-keyboard", allowSheet: true))
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10))
      }
    }
    let token = app.secureTextFields["client-token"]
    let tokenReady = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: token)
    XCTAssertEqual(XCTWaiter.wait(for: [tokenReady], timeout: 15), .completed)
    XCTAssertTrue(tapReadOnlySuggestionControl(token, step: "ui-add-token", allowSheet: true))
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    token.typeText(fixture.token)
    if app.buttons["完成输入"].exists {
      XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["完成输入"], step: "ui-add-token-keyboard", allowSheet: true))
      XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10))
    }
    let connect = app.buttons["connect-client"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND isEnabled == true"), object: connect)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    XCTAssertTrue(tapReadOnlySuggestionControl(connect, step: "ui-add-connect", allowSheet: true))
    // Exactly one connect gesture. An unknown outcome fails; never re-submit.
    XCTAssertTrue(token.waitForNonExistence(timeout: 90), "Normal Add Client UI did not confirm the connection")
    onConnected?()
    dismissPhysicalPasswordPrompt()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 25))
    if app.buttons["close-sidebar"].exists {
      XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["close-sidebar"], step: "ui-add-close-sidebar"))
      XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
    }
  }

  @MainActor private func tracePhoneVerifyUIBaseline(_ baseline: TracePhoneUIBaseline, phase: String) throws {
    XCTAssertTrue(phase == "before" || phase == "after")
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "ui-baseline-" + phase))
    let clients = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertEqual(clients.ids, Set(baseline.clientIds))
    XCTAssertEqual(clients.selectedId, baseline.m4ClientId)
    XCTAssertFalse(clients.ids.contains(baseline.qa5ffClientId))
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "ui-baseline-" + phase + "-close"))
    openDesktopTestConversation(try baseline.mainM4.connection(taskId: baseline.taskId))
    XCTAssertTrue(composer.waitForExistence(timeout: 30))
    expectSuggestionDraft(baseline.mainM4.draftText)
    let actual = try XCTUnwrap(composer.value as? String)
    let actualHash = tracePhoneUIHash(actual)
    let draft = try XCTUnwrap(baseline.drafts.first)
    XCTAssertEqual(actualHash, draft.textSha256, "Hash actual UIText composer content, not the configured marker")
    XCTAssertEqual(actual, baseline.mainM4.draftText)
    let descriptor: [String: Any] = [
      "bundleId": baseline.bundleId, "taskId": baseline.taskId,
      "clientIds": clients.ids.sorted(), "selectedClientId": clients.selectedId,
      "drafts": [["clientId": baseline.m4ClientId, "threadId": baseline.mainM4.threadId,
        "textSha256": actualHash, "localOnly": true] as [String: Any]],
    ]
    let bytes = try JSONSerialization.data(withJSONObject: descriptor, options: [.sortedKeys, .withoutEscapingSlashes])
    let snapshot = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    XCTAssertEqual(snapshot, baseline.snapshotSha256)
    shot("trace-phone-ui-baseline-" + phase)
    print("SUGGESTION_DEV_ISOLATED_BASELINE", phase, snapshot)
  }

  #if DEBUG
  @MainActor func testPhysicalAgentHubFeatureRestoreExistingClient() throws {
    guard let target = ProcessInfo.processInfo.environment["EXISTING_CLIENT_SELECTION_UUID"] else {
      throw XCTSkip("Requires an explicit existing client UUID")
    }
    XCTAssertTrue(target.utf8.count == 36 && UUID(uuidString: target) != nil,
      "EXISTING_CLIENT_SELECTION_UUID must be a bare UUID")
    dismissLiveQuestionPresentation(wait: 2)
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "restore-existing-before"))
    let before = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertTrue(before.ids.contains(target), "The requested client must already exist")
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "restore-existing-reset"))
    if before.selectedId != target {
      XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "restore-existing-select"))
      let client = try XCTUnwrap(findReadOnlySuggestionClient(target))
      XCTAssertTrue(tapReadOnlySuggestionControl(client, step: "restore-existing-tap", allowSheet: true))
      XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "restore-existing-selected", automatic: true))
    }
    dismissLiveQuestionPresentation(wait: 2)
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "restore-existing-verify"))
    let after = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertEqual(after.ids, before.ids)
    XCTAssertEqual(after.selectedId, target)
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "restore-existing-finish"))
    print("PHYSICAL_EXISTING_CLIENT_SELECTION_RESTORED", target, "count", after.ids.count)
  }
  #endif

  private struct RealConversationSuggestionProof: Decodable {
    let hostId: String
    let threadId: String
    let title: String
    let sourceTurnId: String
    let text: String
  }

  /// Parameters come from an existing formal Host/native prediction, never a
  /// fixture response. Preserve the actual conversation's draft byte for byte.
  @MainActor func testOriginalDesktopReadOnlySuggestionsDEVRealConversation() throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["SUGGESTION_DEV_FIXTURE_JSON"])
    let proof = try JSONDecoder().decode(RealConversationSuggestionProof.self, from: Data(encoded.utf8))
    dismissPhysicalPasswordPrompt()
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "real-before-clients"))
    let beforeClients = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertEqual(beforeClients.selectedId, proof.hostId)
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "real-before-close"))
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(proof.title); search.typeText("\n")
    let thread = app.buttons["recent-thread-" + proof.threadId]
    XCTAssertTrue(thread.waitForExistence(timeout: 30)); thread.tap()
    XCTAssertTrue(composer.waitForExistence(timeout: 30))
    let original = normalizedSuggestionDraftText()
    let entry = app.buttons[original.isEmpty ? "suggestion-details" : "suggestion-entry"]
    XCTAssertTrue(entry.waitForExistence(timeout: 75))
    verifyRealSuggestionPreview(entry, text: proof.text, draft: original, step: "real-initial")
    for index in 0..<3 {
      let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.28))
      start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 45, dy: 0)),
        withVelocity: .slow, thenHoldForDuration: 0.3)
      XCTAssertFalse(app.buttons["close-sidebar"].exists)
      XCTAssertTrue(entry.exists, "A canceled drag must retain the suggestion")
      swipeSidebar(from: 0.08, to: 0.82)
      XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 5))
      swipeSidebar(from: 0.97, to: 0.4)
      XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 5))
      XCTAssertTrue(entry.exists, "Closing the sidebar must restore the cached suggestion immediately")
      XCTAssertEqual(normalizedSuggestionDraftText(), original)
      verifyRealSuggestionPreview(entry, text: proof.text, draft: original, step: "real-after-drag-\(index)")
    }
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "real-overlay-clients"))
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "real-overlay-close"))
    XCTAssertTrue(entry.exists, "A client picker dismissed without changing target must retain the suggestion")
    quickAttachmentPanel()
    XCTAssertTrue(quickAttachmentControl("clear-composer-draft").exists)
    quickAttachmentClosePanel()
    XCTAssertTrue(entry.exists)
    XCTAssertEqual(normalizedSuggestionDraftText(), original)
    shot("real-conversation-after-overlay-cycles")
    XCTAssertTrue(tapReadOnlySuggestionControl(entry, step: "real-adoption-preview"))
    let full = app.descendants(matching: .any)["suggestion-full-text"].firstMatch
    XCTAssertTrue(full.waitForExistence(timeout: 10)); XCTAssertEqual(full.label, proof.text)
    XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-apply"], step: "real-adopt", allowSheet: true))
    let applied = original.isEmpty ? proof.text : original + "\n\n" + proof.text
    expectSuggestionDraft(applied)
    shot("real-conversation-adopted-no-send")
    XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-undo"], step: "real-undo"))
    expectSuggestionDraft(original)
    if app.buttons["composer-dismiss-keyboard"].exists {
      XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["composer-dismiss-keyboard"], step: "real-keyboard-close"))
    }
    shot("real-conversation-undo-restored")
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "real-after-clients"))
    let afterClients = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertEqual(afterClients.ids, beforeClients.ids)
    XCTAssertEqual(afterClients.selectedId, beforeClients.selectedId)
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "real-after-close"))
    print("REAL_CONVERSATION_OVERLAYS_ADOPTION_UNDO_NO_SEND", proof.threadId, proof.sourceTurnId)
  }

  @MainActor private func verifyRealSuggestionPreview(_ entry: XCUIElement, text: String, draft: String, step: String) {
    XCTAssertTrue(tapReadOnlySuggestionControl(entry, step: step))
    let full = app.descendants(matching: .any)["suggestion-full-text"].firstMatch
    XCTAssertTrue(full.waitForExistence(timeout: 10)); XCTAssertEqual(full.label, text)
    XCTAssertEqual(normalizedSuggestionDraftText(), draft)
    XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-details-close"], step: step + "-close", allowSheet: true))
    XCTAssertTrue(full.waitForNonExistence(timeout: 10))
  }

  /// Measures only entering an existing real conversation; no adoption or send.
  @MainActor func testOriginalDesktopReadOnlySuggestionsDEVRealConversationLatency() throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["SUGGESTION_DEV_FIXTURE_JSON"])
    let proof = try JSONDecoder().decode(RealConversationSuggestionProof.self, from: Data(encoded.utf8))
    dismissPhysicalPasswordPrompt()
    for attempt in 0..<2 {
      let search = openDesktopSidebarSearch()
      if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
      search.tap(); search.typeText(String(proof.title.prefix(24))); search.typeText("\n")
      let thread = app.buttons["recent-thread-" + proof.threadId]
      XCTAssertTrue(thread.waitForExistence(timeout: 30))
      let started = ProcessInfo.processInfo.systemUptime
      thread.tap()
      // Query the hint directly. Serial composer/draft queries previously added
      // several seconds of XCTest overhead before looking for the hint. App-side
      // monotonic phase timings provide the display latency; this is an upper bound.
      let entry = app.buttons.matching(NSPredicate(format: "identifier IN %@",
        ["suggestion-details", "suggestion-entry"])).firstMatch
      XCTAssertTrue(entry.waitForExistence(timeout: 10))
      let elapsed = (ProcessInfo.processInfo.systemUptime - started) * 1000
      print("REAL_SUGGESTION_QUERY_UPPER_BOUND_MS", attempt, Int(elapsed), proof.threadId, proof.sourceTurnId)
      let original = normalizedSuggestionDraftText()
      if original.isEmpty {
        XCTAssertEqual(app.descendants(matching: .any)["suggestion-text"].firstMatch.label, proof.text)
      } else {
        verifyRealSuggestionPreview(entry, text: proof.text, draft: original, step: "latency-text-\(attempt)")
      }
      XCTAssertEqual(normalizedSuggestionDraftText(), original)
      shot("real-suggestion-entry-latency-\(attempt)")
    }
    print("REAL_SUGGESTION_LATENCY_COMPLETE_NO_SEND", proof.threadId, proof.sourceTurnId)
  }

  /// Ordinary installed App, real Host reads only. The runner separately checks
  /// source Turn IDs in HTTP requests and unchanged original-owner input counts.
  /// Real remote Host response, ordinary independent App, no execution send.
  @MainActor func testOriginalDesktopReadOnlySuggestionsDEVHostRepair() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the explicitly paired physical iPhone")
    #else
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["SUGGESTION_DEV_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(ReadOnlySuggestionFixture.self, from: Data(encoded.utf8))
    XCTAssertEqual(fixture.bundleId, "com.easyai.agenthub.hostrepair20261026")
    XCTAssertNotEqual(fixture.threadAId, fixture.threadBId)
    let fixtureMetadata = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    let expectsEmptyNative = fixtureMetadata["expectsEmptyNativeSuggestions"] as? Bool == true
    let connectionOnly = fixtureMetadata["connectionOnly"] as? Bool == true
    let historyMarkers = fixtureMetadata["expectedHistoryMarkers"] as? [String: String] ?? [:]
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    dismissPhysicalPasswordPrompt()
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "host-repair-start"))
    let initialClientIds = Set(app.collectionViews.firstMatch.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "client-")).allElementsBoundByIndex.map { String($0.identifier.dropFirst("client-".count)) })
    if let own = findReadOnlySuggestionClient(fixture.hostId) {
      own.tap()
      XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "host-repair-existing", automatic: true))
    } else {
      XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "host-repair-new"))
      let metadata = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
      if let pairing = metadata["pairingURI"] as? String {
        app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
        selectConnectionMethod("扫码连接", agent: "codex")
        let field = app.textFields["codex-qr-description"]
        XCTAssertTrue(field.waitForExistence(timeout: 10)); field.tap(); field.typeText(pairing)
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        app.buttons["codex-connect-qr"].tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deadline = Date().addingTimeInterval(90)
        while Date() < deadline && app.buttons["codex-connect-qr"].exists {
          let alert = springboard.alerts.firstMatch
          if alert.exists {
            let text = alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")
            if text.contains("Agent Hub") && (text.contains("网络") || text.localizedCaseInsensitiveContains("network")) {
              let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["允许", "Allow", "好", "OK", "无线局域网与蜂窝网络", "无线局域网与蜂窝数据"])).firstMatch
              if allow.exists { allow.tap() }
            }
          }
          if app.staticTexts["client-form-error"].exists { break }
          Thread.sleep(forTimeInterval: 0.3)
        }
        XCTAssertFalse(app.buttons["codex-connect-qr"].exists, "The pinned ordinary QR connection must finish")
      } else {
        connectLive(try fixture.connection(threadId: fixture.threadAId, title: fixture.threadATitle), supportsCreation: false)
      }
    }
    for (index, useA) in [true, false, true, false].enumerated() {
      // Reassert the exact test App after the bounded read-only settling window.
      app.activate()
      XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5))
      let threadId = useA ? fixture.threadAId : fixture.threadBId
      let title = useA ? fixture.threadATitle : fixture.threadBTitle
      let expected = useA ? fixture.textA : fixture.textB
      openDesktopTestConversation(try fixture.connection(threadId: threadId, title: title))
      XCTAssertTrue(composer.waitForExistence(timeout: 30))
      let before = normalizedSuggestionDraftText()
      if connectionOnly {
        let marker = try XCTUnwrap(historyMarkers[useA ? "A" : "B"])
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 60))
        XCTAssertEqual(normalizedSuggestionDraftText(), before)
        XCTAssertFalse(app.staticTexts["readonly-note"].exists)
        XCTAssertFalse(app.staticTexts["离线 · 草稿保留在此对话"].exists)
        shot("host-repair-connection-history-\(index)")
        print("HOST_REPAIR_CONNECTION_HISTORY", index, threadId)
        continue
      }
      if expectsEmptyNative {
        let marker = try XCTUnwrap(historyMarkers[useA ? "A" : "B"])
        let message = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
        XCTAssertTrue(message.waitForExistence(timeout: 60), "The real source history must render")
        let settling = Date().addingTimeInterval(15)
        while Date() < settling {
          XCTAssertFalse(app.buttons["suggestion-details"].exists)
          XCTAssertFalse(app.buttons["suggestion-entry"].exists)
          XCTAssertFalse(app.buttons["suggestion-apply"].exists)
          XCTAssertEqual(normalizedSuggestionDraftText(), before)
          Thread.sleep(forTimeInterval: 0.3)
        }
        XCTAssertFalse(app.staticTexts["readonly-note"].exists)
        XCTAssertFalse(app.staticTexts["离线 · 草稿保留在此对话"].exists)
        shot("host-repair-native-empty-\(index)")
        print("HOST_REPAIR_NATIVE_EMPTY", index, threadId)
        continue
      }
      let entry = app.buttons[before.isEmpty ? "suggestion-details" : "suggestion-entry"]
      XCTAssertTrue(entry.waitForExistence(timeout: 60), "The actual current source must provide its own native suggestion")
      if before.isEmpty {
        XCTAssertEqual(app.descendants(matching: .any)["suggestion-text"].firstMatch.label, expected)
        XCTAssertFalse(app.buttons["send-message"].isEnabled)
      }
      shot("host-repair-native-source-\(index)")
      entry.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let full = app.descendants(matching: .any)["suggestion-full-text"].firstMatch
      XCTAssertTrue(full.waitForExistence(timeout: 10))
      XCTAssertEqual(full.label, expected)
      shot("host-repair-native-full-\(index)")
      app.buttons["suggestion-apply"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      expectSuggestionDraft(before.isEmpty ? expected : before + "\n\n" + expected)
      app.buttons["suggestion-undo"].tap()
      expectSuggestionDraft(before)
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      print("HOST_REPAIR_NATIVE_SOURCE_ADOPT_UNDO", index, threadId)
    }
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "host-repair-cold-reconnect"))
    let clients = try XCTUnwrap(scanReadOnlySuggestionClients())
    XCTAssertTrue(clients.ids.isSuperset(of: initialClientIds.union([fixture.hostId]))); XCTAssertEqual(clients.selectedId, fixture.hostId)
    XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "host-repair-end"))
    XCTAssertTrue(composer.waitForExistence(timeout: 30))
    if connectionOnly {
      let marker = try XCTUnwrap(historyMarkers["B"])
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 60))
      XCTAssertFalse(app.staticTexts["readonly-note"].exists)
      XCTAssertFalse(app.staticTexts["离线 · 草稿保留在此对话"].exists)
    } else if expectsEmptyNative {
      let marker = try XCTUnwrap(historyMarkers["B"])
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 60))
      let settling = Date().addingTimeInterval(15)
      while Date() < settling {
        XCTAssertFalse(app.buttons["suggestion-details"].exists)
        XCTAssertFalse(app.buttons["suggestion-entry"].exists)
        Thread.sleep(forTimeInterval: 0.3)
      }
      XCTAssertFalse(app.staticTexts["readonly-note"].exists)
      XCTAssertFalse(app.staticTexts["离线 · 草稿保留在此对话"].exists)
    } else {
      let coldSuggestion = app.buttons[normalizedSuggestionDraftText().isEmpty ? "suggestion-details" : "suggestion-entry"]
      XCTAssertTrue(coldSuggestion.waitForExistence(timeout: 60), "A cold launch must obtain a fresh native suggestion")
      XCTAssertEqual(app.descendants(matching: .any)["suggestion-text"].firstMatch.label, fixture.textB)
    }
    shot("host-repair-cold-connection-source-B")
    print(connectionOnly ? "HOST_REPAIR_PHYSICAL_CONNECTION_COMPLETE" : "HOST_REPAIR_PHYSICAL_COMPLETE", fixture.hostId)
    #endif
  }

  @MainActor func testOriginalDesktopReadOnlySuggestionsDEV() throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["SUGGESTION_DEV_FIXTURE_JSON"])
    let fixture = try JSONDecoder().decode(ReadOnlySuggestionFixture.self, from: Data(encoded.utf8))
    XCTAssertNotEqual(fixture.threadAId, fixture.threadBId)
    XCTAssertFalse(fixture.sourceATurnId.isEmpty || fixture.sourceBTurnId.isEmpty)
    if let baseline = fixture.tracePhoneIsolatedBaseline {
      try tracePhonePrepareUIBaseline(baseline, fixture: fixture)
    }
    dismissPhysicalPasswordPrompt()
    XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "initial-client-switch"))
    let clients = try XCTUnwrap(scanReadOnlySuggestionClients(), "Read every connection before DEV")
    let selectedId = clients.selectedId
    let originalId = fixture.originalClientId ?? selectedId
    if fixture.existingConnection == true {
      XCTAssertEqual(originalId, fixture.hostId)
      XCTAssertEqual(selectedId, fixture.hostId, "Verify the original connection without switching or editing credentials")
      XCTAssertTrue(clients.ids.contains(fixture.hostId))
      XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "existing-close-picker"))
      print("SUGGESTION_DEV_ORIGINAL_CLIENT", originalId)
    } else {
      XCTAssertNotEqual(originalId, fixture.hostId, "Use a dedicated temporary Host identity")
      XCTAssertTrue(selectedId == originalId || (fixture.originalClientId != nil && selectedId == fixture.hostId),
        "Do not change an unrelated selected connection")
      XCTAssertTrue(clients.ids.contains(originalId), "The declared original connection must still exist")
      let existingTemporary = clients.ids.contains(fixture.hostId)
      if existingTemporary {
        _ = try XCTUnwrap(fixture.originalClientId, "Reusing this task's connection requires an explicit original client")
      }
      print("SUGGESTION_DEV_ORIGINAL_CLIENT", originalId)
      // Store only verified identities; synchronous tearDownWithError also runs
      // when XCTest aborts this method at an assertion failure.
      if existingTemporary {
        suggestionCleanupTarget = (fixture.hostId, originalId)
        XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "reuse-reset-picker"))
        XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "reuse-open-picker"))
        let temporary = try XCTUnwrap(findReadOnlySuggestionClient(fixture.hostId))
        XCTAssertTrue(tapReadOnlySuggestionControl(temporary, step: "reuse-temporary", allowSheet: true))
        XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "reuse-selected", automatic: true))
        print("SUGGESTION_DEV_REUSE_TEMPORARY_CLIENT", fixture.hostId)
      } else {
        XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "initial-close-picker"))
        if fixture.tracePhoneIsolatedBaseline != nil {
          try tracePhoneAddClientUI(fixture.connection(threadId: fixture.threadAId, title: fixture.threadATitle),
            nickname: "QA5ff TracePhone", onConnected: { self.suggestionCleanupTarget = (fixture.hostId, originalId) })
        } else {
          connectLive(try fixture.connection(threadId: fixture.threadAId, title: fixture.threadATitle),
            supportsCreation: false, onConnected: { self.suggestionCleanupTarget = (fixture.hostId, originalId) })
        }
      }
    }
    dismissPhysicalPasswordPrompt()
    var drafts: [String: String] = [:]
    for (index, useA) in [true, false, true, false].enumerated() {
      let threadId = useA ? fixture.threadAId : fixture.threadBId
      let title = useA ? fixture.threadATitle : fixture.threadBTitle
      let expectedText = useA ? fixture.textA : fixture.textB
      let sourceTurnId = useA ? fixture.sourceATurnId : fixture.sourceBTurnId
      openDesktopTestConversation(try fixture.connection(threadId: threadId, title: title))
      XCTAssertTrue(composer.waitForExistence(timeout: 30))
      if index == 0, let ownedText = fixture.recoverFirstDraftText,
        normalizedSuggestionDraftText() == ownedText {
        // Establish the caret through an explicit append/undo on this known
        // test draft; tapping a text field does not guarantee an end position.
        let entry = app.buttons["suggestion-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 75))
        XCTAssertTrue(tapReadOnlySuggestionControl(entry, step: "suggestion-recovery-preview"))
        XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-apply"], step: "suggestion-recovery-append", allowSheet: true))
        expectSuggestionDraft(ownedText + "\n\n" + expectedText)
        XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-undo"], step: "suggestion-recovery-undo"))
        expectSuggestionDraft(ownedText)
        clearVerifiedSuggestionDraft(ownedText)
        print("SUGGESTION_DEV_RECOVERED_OWN_ADOPTED_DRAFT", threadId, sourceTurnId)
      }
      let before = composer.value as? String ?? ""
      if let previous = drafts[threadId] { XCTAssertEqual(before, previous, "Navigation must preserve the original draft") }
      else { drafts[threadId] = before }
      let entry = app.buttons[before.isEmpty ? "suggestion-details" : "suggestion-entry"]
      XCTAssertTrue(entry.waitForExistence(timeout: 75), "The current source must have its own suggestion")
      if before.isEmpty {
        let ghost = app.descendants(matching: .any)["suggestion-text"].firstMatch
        XCTAssertTrue(ghost.exists)
        XCTAssertEqual(ghost.label, expectedText)
        XCTAssertGreaterThanOrEqual(entry.frame.height, 44, "View-all must have a full touch target")
        XCTAssertGreaterThanOrEqual(app.buttons["suggestion-adopt"].frame.height, 44, "Use must have a full touch target")
        XCTAssertFalse(app.buttons["send-message"].isEnabled, "Gray text is not a submitted draft")
      }
      shot("desktop-suggestion-ghost-\(index)")
      XCTAssertTrue(tapReadOnlySuggestionControl(entry, step: "suggestion-details-\(index)"))
      let text = app.descendants(matching: .any)["suggestion-full-text"].firstMatch
      XCTAssertTrue(text.waitForExistence(timeout: 15))
      let matches = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", expectedText), object: text)
      XCTAssertEqual(XCTWaiter.wait(for: [matches], timeout: 15), .completed)
      XCTAssertEqual(text.label, expectedText, "Original suggestion bytes must belong to this source")
      XCTAssertFalse(app.buttons["suggestion-send"].exists)
      XCTAssertEqual(composer.value as? String ?? "", drafts[threadId], "Reading/expanding must not change the draft")
      shot("read-only-desktop-suggestion-\(index)")
      print("SUGGESTION_DEV_MATCH", index, threadId, sourceTurnId)
      let close = app.buttons["suggestion-details-close"]
      XCTAssertTrue(tapReadOnlySuggestionControl(close, step: "suggestion-close-\(index)", allowSheet: true))
      XCTAssertTrue(text.waitForNonExistence(timeout: 10))
      if fixture.adoptionVerification == true {
        verifySuggestionDraftAdoption(index: index, expectedText: expectedText, existing: before,
          marker: "PHONE_DRAFT_" + (fixture.nonce ?? "suggestion"))
        drafts[threadId] = normalizedSuggestionDraftText()
      } else {
        XCTAssertEqual(normalizedSuggestionDraftText(), before)
      }
    }
    if fixture.existingConnection == true {
      XCTAssertTrue(openReadOnlySuggestionClientPicker(step: "existing-verify-connections"))
      let after = try XCTUnwrap(scanReadOnlySuggestionClients())
      XCTAssertEqual(after.ids, clients.ids, "Keep every original connection and add none")
      XCTAssertEqual(after.selectedId, selectedId)
      XCTAssertTrue(closeReadOnlySuggestionClientPicker(step: "existing-finish-picker"))
      print("SUGGESTION_DEV_EXISTING_CONNECTION_PRESERVED", fixture.hostId)
    } else {
      cleanupReadOnlySuggestionConnection()
      XCTAssertNil(suggestionCleanupTarget, "The dedicated connection must be restored before passing")
    }
    if let baseline = fixture.tracePhoneIsolatedBaseline {
      try tracePhoneVerifyUIBaseline(baseline, phase: "after")
    }
    print("SUGGESTION_DEV_READONLY_COMPLETE")
  }

  private func normalizedSuggestionDraftText() -> String {
    let value = composer.value as? String ?? ""
    return value == "输入消息，长按语音输入" ? "" : value
  }

  private func expectSuggestionDraft(_ text: String) {
    let predicate = text.isEmpty
      ? NSPredicate(format: "value == '' OR value == nil OR value == '输入消息，长按语音输入'")
      : NSPredicate(format: "value == %@", text)
    let expected = XCTNSPredicateExpectation(predicate: predicate, object: composer)
    let result = XCTWaiter.wait(for: [expected], timeout: 15)
    if result != .completed { captureSuggestionUIFailure("draft-value") }
    XCTAssertEqual(result, .completed)
    XCTAssertEqual(normalizedSuggestionDraftText(), text)
  }

  /// Call only immediately after verified typing at the end or suggestion undo,
  /// which explicitly places the caret at the end. Delete exactly this test's
  /// known characters; no menu, transport or send/queue/steer control is used.
  private func clearVerifiedSuggestionDraft(_ knownText: String) {
    guard normalizedSuggestionDraftText() == knownText else { XCTFail("Refuse to clear an unknown draft"); return }
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    composer.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: knownText.count))
    expectSuggestionDraft("")
  }

  @MainActor private func verifySuggestionDraftAdoption(index: Int, expectedText: String, existing: String, marker: String) {
    if index == 0 || index == 2 {
      XCTAssertTrue(existing.isEmpty, "Fill requires a fresh, empty dedicated draft")
      let adopt = app.buttons["suggestion-adopt"]
      XCTAssertTrue(tapReadOnlySuggestionControl(adopt, step: "suggestion-adopt-\(index)"))
      expectSuggestionDraft(expectedText)
      XCTAssertTrue(app.buttons["send-message"].isEnabled)
      XCTAssertFalse(app.buttons["suggestion-adopt"].exists)
      XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
      shot("desktop-suggestion-adopted-\(index)")
      if index == 0 {
        XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-undo"], step: "suggestion-undo"))
        expectSuggestionDraft("")
      } else {
        composer.typeText(" PHONE_EDIT")
        expectSuggestionDraft(expectedText + " PHONE_EDIT")
        XCTAssertFalse(app.buttons["suggestion-undo"].exists, "An edit invalidates the older undo")
        clearVerifiedSuggestionDraft(expectedText + " PHONE_EDIT")
        XCTAssertTrue(app.buttons["suggestion-adopt"].waitForExistence(timeout: 10), "Deleting the draft must restore its valid source suggestion")
        XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-adopt"], step: "suggestion-readopt-after-delete"))
        expectSuggestionDraft(expectedText)
        if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
        quickAttachmentPanel(); quickAttachmentControl("clear-composer-draft").tap()
        expectSuggestionDraft("")
        XCTAssertTrue(app.buttons["suggestion-adopt"].waitForExistence(timeout: 10), "Clear draft must preserve the current valid suggestion")
      }
    } else {
      if index == 1 {
        XCTAssertTrue(existing.isEmpty || existing == marker, "Never change an unrecognized pre-existing draft")
        if existing.isEmpty { composer.tap(); composer.typeText(marker) }
      } else { XCTAssertEqual(existing, marker, "Switching away and back must preserve the draft") }
      expectSuggestionDraft(marker)
      XCTAssertFalse(app.buttons["suggestion-adopt"].exists, "Occupied drafts cannot be directly adopted over")
      let entry = app.buttons["suggestion-entry"]
      XCTAssertTrue(entry.waitForExistence(timeout: 10))
      XCTAssertTrue(tapReadOnlySuggestionControl(entry, step: "suggestion-existing-draft"))
      let full = app.descendants(matching: .any)["suggestion-full-text"].firstMatch
      XCTAssertTrue(full.waitForExistence(timeout: 10)); XCTAssertEqual(full.label, expectedText)
      if index == 3 {
        XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-replace"], step: "suggestion-request-replace", allowSheet: true))
        let cancel = app.buttons["suggestion-cancel-replace"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10)); cancel.tap()
        expectSuggestionDraft(marker)
      }
      XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-apply"], step: "suggestion-append", allowSheet: true))
      expectSuggestionDraft(marker + "\n\n" + expectedText)
      shot("desktop-suggestion-appended-\(index)")
      XCTAssertTrue(app.buttons["suggestion-undo"].waitForExistence(timeout: 10))
      XCTAssertTrue(tapReadOnlySuggestionControl(app.buttons["suggestion-undo"], step: "suggestion-undo-append"))
      expectSuggestionDraft(marker)
      if index == 3 { clearVerifiedSuggestionDraft(marker) }
    }
    let keyboard = app.buttons["composer-dismiss-keyboard"]
    if keyboard.exists { XCTAssertTrue(tapReadOnlySuggestionControl(keyboard, step: "suggestion-finish-keyboard")) }
    print("SUGGESTION_DEV_ADOPTION_PASS", index, "local-draft-only")
  }

  private func captureSuggestionUIFailure(_ step: String) {
    shot("read-only-suggestion-failure-" + step, fullScreen: true)
    let hierarchy = app.debugDescription
    let attachment = XCTAttachment(string: hierarchy)
    attachment.name = "read-only-suggestion-hierarchy-" + step
    attachment.lifetime = .keepAlways
    add(attachment)
    print("SUGGESTION_DEV_UI_FAILURE", step, hierarchy)
  }

  /// The visible toolbar reported an invalid AX hit point on the DEV phone.
  /// Use its observed frame, after checking the foreground and
  /// modal boundary, instead of either the default hit point or isHittable.
  private func tapReadOnlySuggestionControl(_ control: XCUIElement, step: String, allowSheet: Bool = false) -> Bool {
    guard app.state == .runningForeground, !app.alerts.firstMatch.exists,
      control.exists, control.isEnabled else {
      captureSuggestionUIFailure(step + "-not-ready"); return false
    }
    if !allowSheet && (app.sheets.firstMatch.exists || app.buttons["cancel-client-form"].exists || app.navigationBars["客户端"].exists) {
      captureSuggestionUIFailure(step + "-modal"); return false
    }
    let frame = control.frame
    let window = app.windows.firstMatch.frame
    guard !frame.isNull, !frame.isInfinite, frame.width > 1, frame.height > 1,
      !window.isNull, !window.isInfinite, window.contains(frame) else {
      captureSuggestionUIFailure(step + "-frame"); return false
    }
    print("SUGGESTION_DEV_CENTER_TAP", step, String(describing: frame), "hittable", control.isHittable)
    if step.hasPrefix("open-sidebar") { shot("read-only-suggestion-before-" + step, fullScreen: true) }
    let origin = app.frame.origin
    app.coordinate(withNormalizedOffset: .zero)
      .withOffset(CGVector(dx: frame.midX - origin.x, dy: frame.midY - origin.y)).tap()
    return true
  }

  private func cleanupReadOnlySuggestionConnection() {
    guard let target = suggestionCleanupTarget else { return }
    app.activate()
    dismissPhysicalPasswordPrompt()
    let keepDraft = app.buttons["保留原草稿"]
    if keepDraft.exists && app.buttons["确认替换"].exists { keepDraft.tap() }
    let suggestionDetails = app.buttons["suggestion-details-close"]
    if suggestionDetails.exists {
      guard tapReadOnlySuggestionControl(suggestionDetails, step: "cleanup-suggestion-details", allowSheet: true),
        suggestionDetails.waitForNonExistence(timeout: 10) else { return }
    }
    // A failed assertion can leave the picker at any scroll position. Reopen
    // it from the top so a complete scan can distinguish absent from offscreen.
    if app.navigationBars["客户端"].exists,
      !closeReadOnlySuggestionClientPicker(step: "cleanup-reset-picker") { return }
    let keyboard = app.buttons["composer-dismiss-keyboard"]
    if keyboard.exists, !tapReadOnlySuggestionControl(keyboard, step: "cleanup-keyboard") { return }
    let sidebar = app.buttons["close-sidebar"]
    if sidebar.exists {
      guard tapReadOnlySuggestionControl(sidebar, step: "cleanup-sidebar"), sidebar.waitForNonExistence(timeout: 10) else { return }
    }
    guard openReadOnlySuggestionClientPicker(step: "cleanup-client-switch"),
      var clients = scanReadOnlySuggestionClients() else {
      captureSuggestionUIFailure("cleanup-selection"); XCTFail("Cannot verify the complete client list"); return
    }
    guard clients.selectedId == target.hostId || clients.selectedId == target.originalId,
      clients.ids.contains(target.originalId) else {
      captureSuggestionUIFailure("cleanup-unexpected-target"); XCTFail("Unknown selection or missing original client; no connection was removed"); return
    }
    if clients.ids.contains(target.hostId) {
      guard closeReadOnlySuggestionClientPicker(step: "cleanup-reset-removal"),
        openReadOnlySuggestionClientPicker(step: "cleanup-open-removal"),
        let own = findReadOnlySuggestionClient(target.hostId) else {
        captureSuggestionUIFailure("cleanup-temporary"); XCTFail("Cannot locate the exact temporary client"); return
      }
      own.swipeLeft()
      let remove = app.buttons["移除连接"]
      guard remove.waitForExistence(timeout: 10), remove.isHittable else {
        captureSuggestionUIFailure("cleanup-remove-action"); XCTFail("Exact temporary connection removal is unavailable"); return
      }
      guard tapReadOnlySuggestionControl(remove, step: "cleanup-remove", allowSheet: true) else { return }
      guard own.waitForNonExistence(timeout: 10) else {
        captureSuggestionUIFailure("cleanup-removal"); XCTFail("Temporary connection remains"); return
      }
      // Removing the selected connection automatically selects the first saved
      // connection and dismisses this sheet; removing another row leaves it open.
      guard closeReadOnlySuggestionClientPicker(step: "cleanup-removed", automatic: clients.selectedId == target.hostId),
        openReadOnlySuggestionClientPicker(step: "cleanup-verify-removal"),
        let afterRemoval = scanReadOnlySuggestionClients() else { return }
      clients = afterRemoval
    }
    guard !clients.ids.contains(target.hostId), clients.ids.contains(target.originalId) else {
      captureSuggestionUIFailure("cleanup-removal-verification"); XCTFail("Temporary removal or original connection could not be verified"); return
    }
    // Deleting the temporary row commonly restores the original automatically.
    // Only select it again when the verified current selection differs.
    if clients.selectedId != target.originalId {
      guard closeReadOnlySuggestionClientPicker(step: "cleanup-reset-original"),
        openReadOnlySuggestionClientPicker(step: "cleanup-open-original"),
        let original = findReadOnlySuggestionClient(target.originalId),
        tapReadOnlySuggestionControl(original, step: "cleanup-restore-original", allowSheet: true),
        closeReadOnlySuggestionClientPicker(step: "cleanup-original-selected", automatic: true),
        openReadOnlySuggestionClientPicker(step: "cleanup-verify-original"),
        let restored = scanReadOnlySuggestionClients() else { return }
      clients = restored
    }
    guard clients.selectedId == target.originalId, !clients.ids.contains(target.hostId) else {
      captureSuggestionUIFailure("cleanup-final-selection"); XCTFail("Cleanup result could not be verified"); return
    }
    guard closeReadOnlySuggestionClientPicker(step: "cleanup-finish") else { return }
    suggestionCleanupTarget = nil
    print("SUGGESTION_DEV_CLEANUP", target.originalId)
  }

  private func openReadOnlySuggestionClientPicker(step: String) -> Bool {
    let navigation = app.navigationBars["客户端"]
    let list = app.collectionViews.firstMatch
    for attempt in 0..<3 {
      if navigation.exists, list.waitForExistence(timeout: 5) { return true }
      let switcher = app.buttons["client-switch"]
      guard switcher.waitForExistence(timeout: 15),
        tapReadOnlySuggestionControl(switcher, step: step + "-\(attempt)") else { return false }
      if navigation.waitForExistence(timeout: 10), list.waitForExistence(timeout: 5) { return true }
    }
    captureSuggestionUIFailure(step + "-open-timeout")
    return false
  }

  private func closeReadOnlySuggestionClientPicker(step: String, automatic: Bool = false) -> Bool {
    let navigation = app.navigationBars["客户端"]
    if !automatic, navigation.exists {
      let done = navigation.buttons["完成"]
      guard done.waitForExistence(timeout: 10),
        tapReadOnlySuggestionControl(done, step: step, allowSheet: true) else { return false }
    }
    guard navigation.waitForNonExistence(timeout: 10),
      app.buttons["client-switch"].waitForExistence(timeout: 10) else {
      captureSuggestionUIFailure(step + "-close-timeout"); return false
    }
    return true
  }

  /// Call after opening a fresh picker at its top. Only a scan that reaches
  /// Add Client proves absence; an interrupted/dismissed picker returns nil.
  private func scanReadOnlySuggestionClients() -> (ids: Set<String>, selectedId: String)? {
    var ids = Set<String>(), selectedIds = Set<String>()
    for _ in 0..<12 {
      guard readOnlySuggestionClientListReady() else {
        captureSuggestionUIFailure("client-scan-interrupted"); return nil
      }
      let rows = app.collectionViews.firstMatch.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "client-")).allElementsBoundByIndex
      for row in rows where row.exists && row.isHittable {
        let id = String(row.identifier.dropFirst("client-".count))
        ids.insert(id)
        if row.isSelected { selectedIds.insert(id) }
      }
      let add = app.buttons["add-client"]
      if add.exists && add.isHittable {
        guard selectedIds.count == 1, let selectedId = selectedIds.first else {
          captureSuggestionUIFailure("client-scan-selection"); return nil
        }
        print("SUGGESTION_DEV_CLIENT_SCAN", ids.sorted(), "selected", selectedId)
        return (ids, selectedId)
      }
      app.collectionViews.firstMatch.swipeUp()
    }
    captureSuggestionUIFailure("client-scan-limit")
    return nil
  }

  /// Searches forward from a freshly opened picker, never swiping down at the
  /// top edge where iOS would dismiss the sheet instead of scrolling its rows.
  private func findReadOnlySuggestionClient(_ id: String) -> XCUIElement? {
    let row = app.buttons["client-" + id]
    for _ in 0..<12 {
      guard readOnlySuggestionClientListReady() else { return nil }
      if row.exists && row.isHittable { return row }
      let add = app.buttons["add-client"]
      if add.exists && add.isHittable { return nil }
      app.collectionViews.firstMatch.swipeUp()
    }
    return nil
  }

  private func readOnlySuggestionClientListReady() -> Bool {
    let list = app.collectionViews.firstMatch
    guard app.state == .runningForeground, !app.alerts.firstMatch.exists,
      app.navigationBars["客户端"].exists, list.exists else { return false }
    return true
  }

  @MainActor private func restoreSlowNetworkClient(temporaryId: String, originalId: String) {
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    app.buttons["client-switch"].tap()
    let own = app.buttons["client-" + temporaryId]
    for _ in 0..<8 {
      if own.exists && own.isHittable { break }
      app.swipeUp()
    }
    if own.exists {
      own.swipeLeft()
      app.buttons["移除连接"].tap()
      XCTAssertTrue(own.waitForNonExistence(timeout: 10))
    }
    // Removing the selected client already dismisses the picker.
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    app.buttons["client-switch"].tap()
    let restored = app.buttons["client-" + originalId]
    for _ in 0..<8 {
      if restored.exists && restored.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(restored.exists); restored.tap()
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    print("SLOW_SYNC_PHYSICAL_CLEANUP passed")
  }
  @MainActor private func runSlowSyncAcceptance(_ fixture: LiveFixture, physical: Bool) async throws {
    if physical && ProcessInfo.processInfo.environment["SYNC_REUSE_CONNECTION"] == "1" {
      app.buttons["client-switch"].tap()
      let existing = app.buttons["client-" + fixture.hostId]
      for _ in 0..<8 {
        if existing.exists && existing.isHittable { break }
        app.swipeUp()
      }
      XCTAssertTrue(existing.exists); existing.tap()
      if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    } else {
      connectLive(fixture, supportsCreation: false, fromSettings: physical)
    }
    openDesktopTestConversation(fixture)
    let generation = app.buttons["generation-settings"]
    XCTAssertTrue(generation.waitForExistence(timeout: 30)); generation.tap()
    let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
    XCTAssertTrue(effort.waitForExistence(timeout: 15)); effort.tap()
    app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
    focusLiveComposer()
    composer.typeText("专用慢网发送验收，仅原样回复 SYNC_FIRST_" + fixture.nonce + "，不要调用工具。")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["SYNC_FIRST_" + fixture.nonce].waitForExistence(timeout: 210))
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 45))
    focusLiveComposer()
    composer.typeText("专用慢网发送验收，仅原样回复 SYNC_DONE_" + fixture.nonce + "，不要调用工具。")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let send = app.buttons["send-message"]
    if physical {
      // The computer arms a real history read after the first owner completes.
      // Keep the runner independent of iOS networking permissions.
      for _ in 0..<12 {
        XCTAssertTrue(send.isEnabled)
        XCTAssertFalse(app.descendants(matching: .any)["history-loading"].firstMatch.exists)
        try await Task.sleep(for: .seconds(1))
      }
      print("SLOW_SYNC_PHYSICAL_CLICK_AT", Date().timeIntervalSince1970 * 1000)
      send.tap()
    } else {
      _ = try await slowSyncControl("/sync/arm")
      var active = false
      for _ in 0..<90 {
        if try await slowSyncControl("/sync/status")["active"] as? Bool == true { active = true; break }
        try await Task.sleep(for: .milliseconds(200))
      }
      XCTAssertTrue(active, "A real paced HTTP history read must overlap the click")
      XCTAssertTrue(send.isEnabled)
      XCTAssertFalse(app.descendants(matching: .any)["history-loading"].firstMatch.exists)
      let clickedAt = Date().timeIntervalSince1970
      send.tap()
      var submitted = false
      for _ in 0..<40 {
        if try await slowSyncControl("/sync/status")["replies"] as? Int == 2 { submitted = true; break }
        try await Task.sleep(for: .milliseconds(100))
      }
      let status = try await slowSyncControl("/sync/status")
      XCTAssertTrue(submitted, "Single click reaches Host before history is released")
      XCTAssertTrue(status["active"] as? Bool == true)
      if let sends = status["sends"] as? [[String: Any]], let at = sends.last?["at"] as? Double {
        print("SLOW_SYNC_CLICK_TO_HOST_MS", at - clickedAt * 1000)
      }
      _ = try await slowSyncControl("/sync/release")
    }
    XCTAssertTrue(app.staticTexts["SYNC_DONE_" + fixture.nonce].waitForExistence(timeout: 210))
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 45))
    XCTAssertFalse(app.alerts.firstMatch.exists)
  }

  @MainActor func testOriginalDesktopReceivePerformance() async throws {
    let fixture = try await desktopFixture()
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let generation = app.buttons["generation-settings"]
    XCTAssertTrue(generation.waitForExistence(timeout: 30))
    if !generation.label.contains("极高") {
      generation.tap()
      let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
      XCTAssertTrue(effort.waitForExistence(timeout: 15)); effort.tap()
      app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
    }
    XCTAssertTrue(generation.label.contains("极高"))
    focusLiveComposer()
    let start = "RECEIVE_START_" + fixture.nonce
    let end = "RECEIVE_DONE_" + fixture.nonce
    composer.typeText("专用慢网消息接收验收。先单独原样输出 \(start)，接着调用 exec_command 执行 sleep 3，不读取或修改文件。最后输出关于网络性能的40行短文，每行独立编号，每行约40字；最后一行单独输出 \(end)。不要再调用其他工具。")
    print("RECEIVE_UI_SEND", Date().timeIntervalSince1970)
    sendLiveComposer()
    let first = app.staticTexts.matching(NSPredicate(format: "label == %@", start)).firstMatch
    XCTAssertTrue(first.waitForExistence(timeout: 210))
    print("RECEIVE_UI_FIRST", Date().timeIntervalSince1970)
    let final = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", end)).firstMatch
    XCTAssertTrue(final.waitForExistence(timeout: 210))
    print("RECEIVE_UI_FINAL", Date().timeIntervalSince1970)
    let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 45), .completed)
    XCTAssertFalse(app.alerts.firstMatch.exists)
  }

  @MainActor func testOriginalDesktopLiveWindow() async throws {
    let fixture = try await desktopFixture()
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let generation = app.buttons["generation-settings"]
    XCTAssertTrue(generation.waitForExistence(timeout: 30))
    if !generation.label.contains("极高") {
      generation.tap()
      let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
      XCTAssertTrue(effort.waitForExistence(timeout: 15)); effort.tap()
      app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
    }
    let start = "RECEIVE_START_" + fixture.nonce, end = "RECEIVE_DONE_" + fixture.nonce
    focusLiveComposer()
    composer.typeText("专用实时进度验收。先单独输出 \(start)。接着调用 exec_command 执行 python3 -c 'import time; [print(i, flush=True) or time.sleep(3) for i in range(15)]'，不读取修改任何文件。然后输出20行简短网络说明，最后一行原样输出 \(end)。不要调用其他工具。")
    print("RECEIVE_UI_SEND", Date().timeIntervalSince1970)
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", start)).firstMatch.waitForExistence(timeout: 210))
    print("RECEIVE_UI_FIRST", Date().timeIntervalSince1970)
    _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/sync/live")!)
    let draft = "UNSENT_LIVE_WINDOW_DRAFT"
    focusLiveComposer(); composer.typeText(draft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    XCTAssertTrue(app.staticTexts["实时进度已同步"].waitForExistence(timeout: 90), app.debugDescription)
    shot("original-owner-live-window-without-full-snapshot")
    let final = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@", end)).firstMatch
    XCTAssertTrue(final.waitForExistence(timeout: 210), app.debugDescription)
    print("RECEIVE_UI_FINAL", Date().timeIntervalSince1970)
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    shot("original-owner-live-window-final-progress")
  }

  @MainActor func testOriginalDesktopLargeConversationSync() async throws {
    try await testOriginalDesktopReceivePerformance()
    let draft = "UNSENT_SYNC_RECOVERY_DRAFT"
    focusLiveComposer(); composer.typeText(draft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/sync/fail")!)
    let banner = app.staticTexts["conversation-sync-failure"].firstMatch
    let retry = app.buttons["conversation-sync-retry"]
    XCTAssertTrue(retry.waitForExistence(timeout: 35), app.debugDescription)
    retry.tap()
    try await Task.sleep(for: .seconds(3))
    XCTAssertTrue(retry.exists, "HTTP 200 with an unavailable cached snapshot must not hide the failure")
    XCTAssertTrue(app.staticTexts["对话同步失败"].exists)
    XCTAssertTrue(composer.value as? String == draft)
    _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/sync/recover")!)
    let disappeared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: retry)
    XCTAssertEqual(XCTWaiter.wait(for: [disappeared], timeout: 35), .completed)
    XCTAssertFalse(banner.exists)
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/sync/history")!)
    XCTAssertTrue(app.staticTexts["实时状态暂不可用"].waitForExistence(timeout: 35))
    XCTAssertTrue(app.staticTexts["已从电脑同步最新历史进度，正在重试实时连接。"].exists)
    var historyRefreshed = false
    for _ in 0..<40 {
      let (bytes, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/sync/status")!)
      let value = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
      if (value?["historyReads"] as? Int ?? 0) >= 2 { historyRefreshed = true; break }
      try await Task.sleep(for: .milliseconds(250))
    }
    XCTAssertTrue(historyRefreshed, "Cached events must not postpone the next fresh history read")
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    shot("latest-history-with-explicit-unavailable-runtime")
    _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/sync/recover")!)
    XCTAssertTrue(retry.waitForNonExistence(timeout: 35))
    XCTAssertEqual(composer.value as? String, draft)
  }

  @MainActor func testPhysicalAgentHubFeatureLargeConversationSyncDEV() async throws {
    let environment = ProcessInfo.processInfo.environment
    guard let hostId = environment["LARGE_SYNC_HOST_ID"],
      let threadId = environment["LARGE_SYNC_THREAD_ID"],
      let title = environment["LARGE_SYNC_THREAD_TITLE"] else {
      throw XCTSkip("Requires an explicitly selected existing Host and conversation; read-only verification")
    }
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    app.buttons["client-switch"].tap()
    let original = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND selected == true", "client-")).firstMatch
    let originalId = original.exists ? original.identifier : nil
    let target = app.buttons["client-" + hostId]
    XCTAssertTrue(target.waitForExistence(timeout: 15)); target.tap()
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(title + "\n")
    let row = app.buttons.matching(NSPredicate(format: "identifier == %@ OR identifier == %@ OR identifier == %@",
      "recent-thread-" + threadId, "thread-" + threadId, "pinned-thread-" + threadId)).firstMatch
    XCTAssertTrue(row.waitForExistence(timeout: 30)); row.tap()
    let warning = app.staticTexts["conversation-sync-failure"]
    if environment["LARGE_SYNC_EXPECTATION"] == "unavailable" {
      XCTAssertTrue(warning.waitForExistence(timeout: 60), app.debugDescription)
      XCTAssertTrue(app.buttons["conversation-sync-retry"].exists)
      shot("physical-large-conversation-sync-failure")
    } else if environment["LARGE_SYNC_EXPECTATION"] == "live-window" {
      let live = app.staticTexts["conversation-sync-failure"]
      XCTAssertTrue(live.waitForExistence(timeout: 60))
      let synced = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "实时进度已同步"), object: live)
      XCTAssertEqual(XCTWaiter.wait(for: [synced], timeout: 60), .completed)
      if let text = environment["LARGE_SYNC_EXPECTED_TEXT"] {
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch.waitForExistence(timeout: 60))
      }
      shot("physical-large-conversation-live-progress-and-queue")
    } else {
      XCTAssertTrue(warning.waitForNonExistence(timeout: 60), app.debugDescription)
      if let text = environment["LARGE_SYNC_EXPECTED_TEXT"] {
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch.waitForExistence(timeout: 60), app.debugDescription)
      }
      shot("physical-large-conversation-latest-progress")
    }
    if environment["LARGE_SYNC_EXPECTATION"] != "unavailable" {
      if environment["LARGE_SYNC_EXPECT_HOST_QUEUE"] == "1" {
        let operationId = try XCTUnwrap(environment["LARGE_SYNC_QUEUE_OPERATION_ID"])
        let source = app.descendants(matching: .any)["legacy-queue-notice-" + operationId]
        XCTAssertFalse(source.exists, "Retired Host queue records must not occupy the conversation UI")
        XCTAssertFalse(app.buttons["execute-queued-" + operationId].exists)
        XCTAssertFalse(app.buttons["cancel-queued-" + operationId].exists)
        XCTAssertFalse(app.staticTexts["消息执行结果待确认"].exists)
      }
      for _ in 0..<15 {
        try await Task.sleep(for: .seconds(3))
        XCTAssertFalse(app.alerts["无法完成操作"].exists, "Reading-state persistence must remain healthy during live refresh")
      }
      shot("physical-large-conversation-persistence-and-queue-verified")
    }
    if let originalId, originalId != "client-" + hostId {
      app.buttons["client-switch"].tap(); app.buttons[originalId].tap()
      if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    }
  }

  @MainActor func testApplicationApprovalsSimulator() async throws {
    guard let encoded = ProcessInfo.processInfo.environment["APPLICATION_APPROVAL_SIMULATOR_JSON"] else {
      throw XCTSkip("Run scripts/application-approval-ios-fixture.mjs on an isolated simulator")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: Data(encoded.utf8))
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let cases = [("computer-session", "accept", "本会话允许"), ("computer-always", "acceptAlways", "始终允许"),
      ("computer-decline", "decline", "拒绝"), ("computer-cancel", "cancel", "取消"),
      ("permission-turn", "accept", "允许本轮"), ("permission-session", "acceptForSession", "本会话允许"),
      ("unsupported", "decline", "拒绝")]
    for (index, entry) in cases.enumerated() {
      if index > 0 {
        var request = URLRequest(url: URL(string: fixture.endpoint + "/scenario?value=\(index)")!)
        request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      }
      let action = app.buttons["approval-" + entry.1]
      XCTAssertTrue(action.waitForExistence(timeout: 30), app.debugDescription)
      XCTAssertEqual(action.label, entry.2)
      if entry.0.hasPrefix("computer") {
        XCTAssertFalse(app.buttons["approval-acceptForSession"].exists, "Computer Use shows one conversation grant")
        XCTAssertEqual(app.buttons["approval-acceptAlways"].label, "始终允许")
        XCTAssertEqual(app.buttons["approval-cancel"].label, "取消")
      }
      if entry.0 == "unsupported" {
        XCTAssertFalse(app.buttons["approval-accept"].exists)
        XCTAssertFalse(app.buttons["approval-acceptAlways"].exists)
      }
      shot("application-approval-" + entry.0)
      tapAISharingAction(action)
      XCTAssertTrue(action.waitForNonExistence(timeout: 30))
      XCTAssertTrue(app.staticTexts["APPROVAL_DONE_" + entry.0].waitForExistence(timeout: 30))
      XCTAssertFalse(app.alerts["无法完成操作"].exists)
    }
  }

  @MainActor func testComputerUseApprovalOriginalDesktopDEV() async throws {
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["COMPUTER_USE_APPROVAL_FIXTURE_JSON"])
    let bytes = Data(encoded.utf8)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: bytes)
    let settings = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let accept = app.buttons["approval-accept"]
    let session = app.buttons["approval-acceptForSession"]
    XCTAssertTrue(accept.waitForExistence(timeout: 120) || session.exists, app.debugDescription)
    let action = session.exists ? session : accept
    XCTAssertEqual(action.label, "本会话允许")
    XCTAssertTrue(app.buttons["approval-decline"].exists)
    XCTAssertEqual(app.buttons["approval-acceptAlways"].exists, settings["supportsAlways"] as? Bool == true)
    if !action.isHittable { app.scrollViews["message-list"].swipeUp() }
    shot("computer-use-native-app-approval")
    tapAISharingAction(action)
    XCTAssertTrue(action.waitForNonExistence(timeout: 60))
    let marker = "COMPUTER_USE_APPROVED_" + fixture.nonce
    let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 180), app.debugDescription)
    XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 60))
    XCTAssertFalse(app.alerts["无法完成操作"].exists)
    shot("computer-use-native-app-approved")
  }

  @MainActor func testOriginalDesktopReplyProgressStopAndApproval() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 90), .completed)
    XCTAssertEqual(app.buttons["new-chat"].isEnabled, fixture.creationProjectId != nil)
    let marker = "DESKTOP_IOS_" + fixture.nonce
    focusLiveComposer()
    typeComposerText("原桌面手机验收：仅原样回复 \(marker)，不要使用工具。")
    sendLiveComposer()
    let answer = app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 90))
    let stop = app.buttons["stop-turn"]
    let finished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 45), .completed)
    focusLiveComposer()
    let streamMarker = "STREAM_DESKTOP_" + fixture.nonce
    typeComposerText("原桌面停止验收：立即以 \(streamMarker)：开头，连续输出长篇中文的软件工程论述，约6000字，无标题、无前言，不使用工具，不必统计字数，直到我点击停止。")
    sendLiveComposer()
    XCTAssertTrue(stop.waitForExistence(timeout: 30))
    let streamed = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", streamMarker)).firstMatch
    XCTAssertTrue(streamed.waitForExistence(timeout: 180))
    XCTAssertTrue(stop.exists)
    XCTAssertFalse(app.segmentedControls["reply-mode"].exists)
    stop.tap()
    let stopped = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 40), .completed)
    let settings = app.buttons["generation-settings"]
    let configurable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: settings)
    XCTAssertEqual(XCTWaiter.wait(for: [configurable], timeout: 45), .completed)
    settings.tap()
    XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 15))
    let readOnly = app.buttons["permission-read-only"]
    XCTAssertTrue(readOnly.waitForExistence(timeout: 15))
    if !readOnly.isHittable { app.swipeUp() }
    readOnly.tap()
    app.buttons["generation-done"].tap()
    focusLiveComposer()
    typeComposerText("专用原桌面审批验收：使用 exec_command 执行 printf DESKTOP_APPROVAL_\(fixture.nonce)，显式设置 sandbox_permissions=require_escalated，justification=验证手机审批。命令不读取或修改文件，必须发起权限请求，批准后执行，最终仅回复命令输出。")
    sendLiveComposer()
    let accept = app.buttons["approval-accept"]
    XCTAssertTrue(accept.waitForExistence(timeout: 150))
    if !accept.isHittable { app.swipeUp() }
    shot("original-desktop-native-approval")
    accept.tap()
    let approved = app.staticTexts.matching(NSPredicate(format: "label == %@", "DESKTOP_APPROVAL_" + fixture.nonce)).firstMatch
    XCTAssertTrue(approved.waitForExistence(timeout: 120))
    let completed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [completed], timeout: 45), .completed)
    XCTAssertFalse(accept.exists)
    shot("original-desktop-native-completed")
  }
  @MainActor private func codexHistory(_ fixture: LiveFixture) async throws -> [String: Any] {
    let url = URL(string: fixture.endpoint + "/v1/history?hostId=" + fixture.hostId + "&threadId=" + fixture.threadId)!
    var request = URLRequest(url: url)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (data, response) = try await URLSession.shared.data(for: request)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }
  @MainActor private func verifyCodexToolUI(_ messages: [[String: Any]], commandId: String) throws {
    let index = try XCTUnwrap(messages.firstIndex { $0["id"] as? String == commandId })
    var first = index
    var last = index
    while first > 0 && messages[first - 1]["role"] as? String == "tool" { first -= 1 }
    while last + 1 < messages.count && messages[last + 1]["role"] as? String == "tool" { last += 1 }
    let tool = app.buttons["tool-item-" + commandId]
    if last > first {
      let group = app.buttons["tool-group-" + (try XCTUnwrap(messages[first]["id"] as? String))]
      for _ in 0..<18 where !group.exists || !group.isHittable {
        app.swipeDown()
        let more = app.buttons["history-local-more"]
        if more.exists && more.isHittable { more.tap() }
      }
      XCTAssertTrue(group.isHittable)
      XCTAssertTrue((group.value as? String ?? "").contains("已折叠"))
      XCTAssertFalse(tool.exists)
      group.tap()
      XCTAssertTrue((group.value as? String ?? "").contains("已展开"))
    }
    for _ in 0..<18 where !tool.exists || !tool.isHittable {
      app.swipeDown()
      let more = app.buttons["history-local-more"]
      if more.exists && more.isHittable { more.tap() }
    }
    XCTAssertTrue(tool.exists)
    XCTAssertTrue(tool.isHittable)
    tool.tap()
    let detail = app.descendants(matching: .any)["tool-detail-" + commandId].firstMatch
    XCTAssertTrue(detail.waitForExistence(timeout: 5))
    XCTAssertTrue(app.staticTexts["Shell"].exists)
    shot("codex-real-tool-detail")
    if last > first {
      let group = app.buttons["tool-group-" + (try XCTUnwrap(messages[first]["id"] as? String))]
      for _ in 0..<8 where !group.isHittable { app.swipeDown() }
      XCTAssertTrue(group.isHittable)
      group.tap()
      XCTAssertTrue((group.value as? String ?? "").contains("已折叠"))
      XCTAssertFalse(detail.exists)
      group.tap()
      XCTAssertTrue((group.value as? String ?? "").contains("已展开"))
      XCTAssertTrue(detail.waitForExistence(timeout: 5))
      shot("codex-real-tool-group-restored")
    } else {
      XCTAssertFalse(app.buttons["tool-group-" + commandId].exists)
    }
    tool.tap()
    XCTAssertFalse(detail.exists)
  }
  @MainActor func testCodexHistoricalTools() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let history = try await codexHistory(fixture)
    XCTAssertEqual((history["runtime"] as? [String: Any])?["canControl"] as? Bool, false)
    XCTAssertTrue((app.buttons["client-switch"].value as? String ?? "").contains("Codex"))
    XCTAssertTrue((app.buttons["client-switch"].value as? String ?? "").contains("只读"))
    let messages = try XCTUnwrap(history["messages"] as? [[String: Any]])
    let command = try XCTUnwrap(messages.last { $0["kind"] as? String == "commandExecution" })
    try verifyCodexToolUI(messages, commandId: try XCTUnwrap(command["id"] as? String))
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    let restored = try await codexHistory(fixture)
    XCTAssertTrue((restored["messages"] as? [[String: Any]] ?? []).contains { $0["id"] as? String == command["id"] as? String })
    XCTAssertFalse(app.alerts.firstMatch.exists)
  }
  @MainActor func testCodexLiveTools() async throws {
    let fixture = try await desktopFixture()
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    app.buttons["generation-settings"].tap()
    let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
    XCTAssertTrue(effort.waitForExistence(timeout: 10))
    effort.tap()
    app.buttons["极高"].tap()
    app.buttons["generation-done"].tap()
    let before = try await codexHistory(fixture)
    XCTAssertEqual((before["runtime"] as? [String: Any])?["canControl"] as? Bool, true)
    let priorIds = Set((before["messages"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String })
    for count in [1, 2] {
      let marker = "CODEX_TOOLS_\(count)_" + fixture.nonce
      composer.tap()
      typeComposerText("专用工具展示验收。先单独原样输出 \(marker)_过程，然后调用 exec_command 恰好 \(count) 次，每次执行 sleep 8; printf '\(marker)_输出'，yield_time_ms=1000。两次 exec_command 之间单独原样输出 \(marker)_中间。允许用 write_stdin 等待命令自然结束。必须实际调用工具，使用默认权限，不读取或修改文件。完成后最终仅原样回复 \(marker)，不要加格式。")
      app.buttons["send-message"].tap()
      XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 30))
      let progress = app.staticTexts.matching(NSPredicate(format: "label == %@", marker + "_过程")).firstMatch
      XCTAssertTrue(progress.waitForExistence(timeout: 90))
      let running = try await codexHistory(fixture)
      let activeRun = try XCTUnwrap((running["runtime"] as? [String: Any])?["turnId"] as? String)
      let progressMessages = (running["messages"] as? [[String: Any]] ?? []).filter { $0["role"] as? String == "assistant" && $0["runId"] as? String == activeRun }
      XCTAssertFalse(progressMessages.isEmpty)
      for message in progressMessages {
        XCTAssertFalse(app.otherElements["run-reply-actions-" + (try XCTUnwrap(message["id"] as? String))].exists)
      }
      waitForDesktopAnswer(marker)
      let history = try await codexHistory(fixture)
      let messages = try XCTUnwrap(history["messages"] as? [[String: Any]])
      let commands = messages.filter { $0["kind"] as? String == "commandExecution" && ($0["text"] as? String ?? "").contains(marker) && !priorIds.contains($0["id"] as? String ?? "") }
      XCTAssertEqual(commands.count, count)
      XCTAssertTrue(commands.allSatisfy { ($0["detail"] as? String ?? "").contains(marker + "_输出") && $0["status"] as? String == "completed" })
      let runReplies = messages.filter { $0["role"] as? String == "assistant" && $0["runId"] as? String == activeRun }
      let final = try XCTUnwrap(runReplies.last { $0["text"] as? String == marker })
      XCTAssertEqual(final["replyPhase"] as? String, "final_answer")
      let actions = app.otherElements["run-reply-actions-" + (try XCTUnwrap(final["id"] as? String))]
      XCTAssertTrue(actions.waitForExistence(timeout: 10))
      XCTAssertEqual(actions.buttons.matching(identifier: "copy-reply").count, 1)
      XCTAssertEqual(actions.buttons.matching(identifier: "share-reply").count, 1)
      for message in runReplies where message["id"] as? String != final["id"] as? String {
        XCTAssertFalse(app.otherElements["run-reply-actions-" + (try XCTUnwrap(message["id"] as? String))].exists)
      }
      actions.buttons["copy-reply"].tap()
      composer.tap()
      composer.press(forDuration: 1.2)
      let paste = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Paste' OR label == '粘贴'")).firstMatch
      XCTAssertTrue(paste.waitForExistence(timeout: 5))
      paste.tap()
      XCTAssertEqual(composer.value as? String, marker, "Copy must contain only the final answer")
      typeComposerText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: marker.count))
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      shot("run-reply-actions-completed-\(count)")
      try verifyCodexToolUI(messages, commandId: try XCTUnwrap(commands.last?["id"] as? String))
      if app.buttons["跳转最新消息"].exists { app.buttons["跳转最新消息"].tap() }
    }
  }
  @MainActor func testCodexNativeQueueSyncAndExecuteNow() async throws {
    let (fixtureData, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8796")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: fixtureData)
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    var modelsRequest = URLRequest(url: URL(string: fixture.endpoint + "/v1/models?hostId=" + fixture.hostId + "&threadId=" + fixture.threadId)!)
    modelsRequest.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    let (modelsData, modelsResponse) = try await URLSession.shared.data(for: modelsRequest)
    XCTAssertEqual((modelsResponse as? HTTPURLResponse)?.statusCode, 200)
    let models = try XCTUnwrap((JSONSerialization.jsonObject(with: modelsData) as? [String: Any])?["models"] as? [[String: Any]])
    let initialHistory = try await codexHistory(fixture)
    let modelId = ((initialHistory["runtime"] as? [String: Any])?["generationSettings"] as? [String: Any])?["model"] as? String
    let model = try XCTUnwrap(models.first { $0["id"] as? String == modelId } ?? models.first { $0["isDefault"] as? Bool == true })
    let efforts = try XCTUnwrap(model["supportedEfforts"] as? [String])
    let xhighIndex = try XCTUnwrap(efforts.firstIndex(of: "xhigh"))
    XCTAssertGreaterThan(efforts.count, 1)
    app.buttons["generation-settings"].tap()
    let effortSlider = app.sliders["effort-slider"]
    XCTAssertTrue(effortSlider.waitForExistence(timeout: 15))
    effortSlider.adjust(toNormalizedSliderPosition: CGFloat(xhighIndex) / CGFloat(efforts.count - 1))
    let effort = app.buttons["effort-picker"].firstMatch
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS '极高'"), object: effort)], timeout: 10), .completed)
    let generationDone = app.buttons["generation-done"]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: generationDone)], timeout: 10), .completed)
    generationDone.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(generationDone.waitForNonExistence(timeout: 10))
    let marker = "NATIVE_QUEUE_" + fixture.nonce
    focusLiveComposer()
    composer.typeText("专用 Host 队列验收。使用 exec_command 执行 sleep 90，yield_time_ms=1000，沿用当前默认权限，不读写文件。用 write_stdin 等待该命令自然结束。收到追加指令后仍等原命令结束，最后只回复 " + marker + "_ORIGIN。")
    sendLiveComposer()
    let stop = app.buttons["stop-turn"]
    XCTAssertTrue(stop.waitForExistence(timeout: 30))
    if app.buttons["approval-accept"].exists { app.buttons["approval-accept"].tap() }
    for suffix in ["FIFO", "PROMOTE", "CANCEL"] {
      focusLiveComposer()
      composer.typeText("保留当前工具等待；本条执行时最后只原样回复 " + marker + "_" + suffix + "，不调用新工具。")
      app.segmentedControls["reply-mode"].buttons["排队执行"].tap(); sendLiveComposer()
    }
    var queued: [[String: Any]] = []
    for _ in 0..<40 {
      let queuedHistory = try await codexHistory(fixture)
      queued = try XCTUnwrap((queuedHistory["runtime"] as? [String: Any])?["receipts"] as? [[String: Any]]).filter { $0["state"] as? String == "queued" }
      if queued.count == 3 { break }
      try await Task.sleep(for: .milliseconds(500))
    }
    XCTAssertEqual(queued.count, 3)
    XCTAssertTrue(queued.allSatisfy { $0["queueSource"] as? String == "native" })
    let queueURL = URL(string: "http://127.0.0.1:8796/host-queue")!
    for receipt in queued {
      let id = try XCTUnwrap(receipt["clientMessageId"] as? String)
      XCTAssertTrue(app.buttons["execute-queued-" + id].waitForExistence(timeout: 15))
    }
    let (initialQueueData, _) = try await URLSession.shared.data(from: queueURL)
    let nativeItems = try XCTUnwrap((JSONSerialization.jsonObject(with: initialQueueData) as? [String: Any])?["items"] as? [[String: Any]])
    XCTAssertEqual(nativeItems.compactMap { $0["clientMessageId"] as? String }, queued.compactMap { $0["clientMessageId"] as? String })
    shot("native-queue-codex-phone")
    var restart = URLRequest(url: URL(string: "http://127.0.0.1:8796/restart")!); restart.httpMethod = "POST"
    let (_, response) = try await URLSession.shared.data(for: restart)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let cancelId = try XCTUnwrap(queued.last?["clientMessageId"] as? String)
    let cancel = app.buttons["cancel-queued-" + cancelId]
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: cancel)], timeout: 30), .completed)
    let syncStarted = Date()
    var desktopCancel = URLRequest(url: URL(string: "http://127.0.0.1:8796/desktop-cancel")!); desktopCancel.httpMethod = "POST"
    let (_, desktopResponse) = try await URLSession.shared.data(for: desktopCancel)
    XCTAssertEqual((desktopResponse as? HTTPURLResponse)?.statusCode, 200)
    XCTAssertTrue(cancel.waitForNonExistence(timeout: 10))
    print("NATIVE_QUEUE_DESKTOP_CANCEL_SYNC_MS", Int(Date().timeIntervalSince(syncStarted) * 1000))
    let promoteId = try XCTUnwrap(queued[1]["clientMessageId"] as? String)
    let promote = app.buttons["execute-queued-" + promoteId]
    XCTAssertTrue(promote.waitForExistence(timeout: 15)); XCTAssertTrue(promote.isEnabled); promote.tap()
    XCTAssertTrue(promote.waitForNonExistence(timeout: 25))
    let remainingId = try XCTUnwrap(queued.first?["clientMessageId"] as? String)
    XCTAssertEqual(app.staticTexts["queued-number-" + remainingId].label, "1")
    let (remainingData, _) = try await URLSession.shared.data(from: queueURL)
    let remaining = try XCTUnwrap((JSONSerialization.jsonObject(with: remainingData) as? [String: Any])?["items"] as? [[String: Any]])
    XCTAssertEqual(remaining.compactMap { $0["clientMessageId"] as? String }, [remainingId])
    shot("host-queue-codex-promoted-and-cancelled")
    waitForDesktopAnswer(marker + "_FIFO")
    XCTAssertTrue(stop.waitForNonExistence(timeout: 45))
    XCTAssertTrue(app.staticTexts["queued-text-" + remainingId].waitForNonExistence(timeout: 15))
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    openDesktopTestConversation(fixture)
    XCTAssertFalse(app.buttons["execute-queued-" + promoteId].exists)
    shot("host-queue-codex-restored-final")
    print("NATIVE_QUEUE_DEV passed", fixture.threadId)
  }
  @MainActor func testCodexComposerQueueAndSteer() async throws {
    let fixture = try await desktopFixture()
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let marker = "COMPOSER_REAL_" + fixture.nonce
    let initial = try await codexHistory(fixture)
    XCTAssertEqual((initial["runtime"] as? [String: Any])?["canControl"] as? Bool, true)
    composer.tap()
    typeComposerText("专用手机输入框验收。调用 exec_command 执行 sleep \(fixture.queueHoldSeconds ?? 30)，yield_time_ms=1000，使用默认权限，不读写文件。允许用 write_stdin 等待这一个命令自然结束，然后最终仅回复 \(marker)_ORIGIN。期间收到追加指令时采纳它，但仍等待原命令自然结束。")
    sendLiveComposer()
    let stop = app.buttons["stop-turn"]
    XCTAssertTrue(stop.waitForExistence(timeout: 25))
    for index in 1...3 {
      composer.tap()
      typeComposerText("仅原样回复 \(marker)_QUEUE_\(index)，不加格式，不调用工具。")
      app.segmentedControls["reply-mode"].buttons["排队执行"].tap()
      app.buttons["send-message"].tap()
      XCTAssertTrue(stop.waitForExistence(timeout: 15))
    }
    let queuedHistory = try await codexHistory(fixture)
    let queuedRuntime = try XCTUnwrap(queuedHistory["runtime"] as? [String: Any])
    let queued = try XCTUnwrap(queuedRuntime["receipts"] as? [[String: Any]]).filter { $0["state"] as? String == "queued" }
    XCTAssertEqual(queued.count, 3)
    for (index, receipt) in queued.enumerated() {
      let id = try XCTUnwrap(receipt["clientMessageId"] as? String)
      let text = app.staticTexts["queued-text-" + id]
      XCTAssertTrue(text.waitForExistence(timeout: 10))
      XCTAssertTrue(text.label.contains(marker + "_QUEUE_\(index + 1)"))
      XCTAssertEqual(app.staticTexts["queued-number-" + id].label, "\(index + 1)")
      XCTAssertLessThan(text.frame.maxY, composer.frame.minY)
    }
    shot("real-codex-numbered-queue")
    let cancelledId = try XCTUnwrap(queued[1]["clientMessageId"] as? String)
    app.buttons["cancel-queued-" + cancelledId].tap()
    let thirdId = try XCTUnwrap(queued[2]["clientMessageId"] as? String)
    let renumbered = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == '2'"), object: app.staticTexts["queued-number-" + thirdId])
    XCTAssertEqual(XCTWaiter.wait(for: [renumbered], timeout: 10), .completed)
    composer.tap()
    typeComposerText("追加指令：本轮最后的回复改为 \(marker)_STEER。允许继续用 write_stdin 等待当前 sleep 命令自然结束；完成后仅回复指定字符串。不停止命令，不执行其他命令。")
    app.segmentedControls["reply-mode"].buttons["立即追加"].tap()
    app.buttons["send-message"].tap()
    XCTAssertTrue(stop.waitForExistence(timeout: 15))
    let steeredHistory = try await codexHistory(fixture)
    let steeredRuntime = try XCTUnwrap(steeredHistory["runtime"] as? [String: Any])
    let receipts = try XCTUnwrap(steeredRuntime["receipts"] as? [[String: Any]])
    let steer = try XCTUnwrap(receipts.last { $0["mode"] as? String == "steer" })
    XCTAssertEqual(steer["turnId"] as? String, queuedRuntime["turnId"] as? String)
    XCTAssertEqual(steer["state"] as? String, "submitted")
    waitForDesktopAnswer(marker + "_QUEUE_3")
    let finished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 30), .completed)
    let send = app.buttons["send-message"]
    XCTAssertTrue(send.exists)
    XCTAssertFalse(send.isEnabled)
    XCTAssertFalse(app.buttons["resume-task"].exists)
    shot("real-codex-completed-disabled-send")
  }
  @MainActor func testOriginalDesktopConversationSwitchAndDraftIsolation() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture, supportsCreation: false)
    let targets = try XCTUnwrap(fixture.switchThreads)
    XCTAssertGreaterThanOrEqual(targets.count, 3)
    for target in targets + targets.reversed() {
      app.buttons["open-sidebar"].tap()
      XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 10))
      if !app.textFields["sidebar-search"].exists { app.buttons["sidebar-search-toggle"].tap() }
      let search = app.textFields["sidebar-search"]
      XCTAssertTrue(search.waitForExistence(timeout: 10))
      if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
      search.tap()
      search.typeText(target.title)
      let row = app.buttons["recent-thread-" + target.id]
      if !row.waitForExistence(timeout: 10) { shot("desktop-switch-search-failure"); print("Desktop search diagnostic:\n" + app.debugDescription) }
      XCTAssertTrue(row.exists)
      row.tap()
      let loaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS 'Codex' AND NOT value CONTAINS '离线'"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 30), .completed)
      let historyFinished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.descendants(matching: .any)["history-loading"].firstMatch)
      XCTAssertEqual(XCTWaiter.wait(for: [historyFinished], timeout: 210), .completed)
      XCTAssertFalse(app.alerts.firstMatch.exists)
      XCTAssertTrue(app.scrollViews["message-list"].exists)
    }
    app.buttons["open-sidebar"].tap()
    if !app.textFields["sidebar-search"].exists { app.buttons["sidebar-search-toggle"].tap() }
    let search = app.textFields["sidebar-search"]
    XCTAssertTrue(search.waitForExistence(timeout: 10))
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap()
    let title = String((fixture.threadTitle ?? "").prefix(32))
    XCTAssertFalse(title.isEmpty)
    search.typeText(title)
    app.buttons["recent-thread-" + fixture.threadId].tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 30), .completed)
    composer.tap()
    typeComposerText("desktop-draft-" + fixture.nonce)
    switchClient("demo-mac")
    XCTAssertFalse((composer.value as? String ?? "").contains(fixture.nonce))
    switchClient(fixture.hostId)
    XCTAssertEqual(composer.value as? String, "desktop-draft-" + fixture.nonce)
    XCTAssertFalse(app.alerts.firstMatch.exists)
  }
  @MainActor private func actualWorkspace(_ fixture: LiveFixture) async throws -> [[String: Any]] {
    var request = URLRequest(url: URL(string: fixture.endpoint + "/v1/workspace")!)
    request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    request.timeoutInterval = 30
    // This is a read-only independent check. Never resubmit a creation on timeout.
    for attempt in 0..<3 {
      do {
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let value = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(value["threads"] as? [[String: Any]])
      } catch let error as URLError where error.code == .timedOut && attempt < 2 {
        try await Task.sleep(for: .seconds(1))
      }
    }
    throw URLError(.timedOut)
  }
  @MainActor private func chooseDesktopProject(_ id: String?) {
    let newChat = app.buttons["new-chat"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: newChat)
    let result = XCTWaiter.wait(for: [ready], timeout: 25)
    if result != .completed {
      shot("desktop-new-chat-readiness-failure")
      let state = XCTAttachment(string: app.debugDescription)
      state.name = "desktop-new-chat-readiness"; state.lifetime = .keepAlways; add(state)
    }
    XCTAssertEqual(result, .completed)
    dismissLiveQuestionPresentation(wait: 2)
    // The floating button overlaps the history scroll view's AX frame. Verify
    // the actual native action, instead of trusting XCTest's hit-point heuristic.
    newChat.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let choice = app.buttons[id.map { "choose-project-" + $0 } ?? "choose-projectless"]
    let chooser = app.navigationBars["选择项目"]
    if !chooser.waitForExistence(timeout: 10) {
      shot("desktop-project-chooser-ui-retry")
      dismissLiveQuestionPresentation()
      newChat.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    XCTAssertTrue(chooser.waitForExistence(timeout: 10))
    // The real workspace retains projects from prior acceptances. Scroll to the
    // exact fixture ID with a bounded large-list budget, never pick a nearby row.
    let list = app.collectionViews.firstMatch
    func visibleChoice() -> Bool {
      guard choice.exists else { return false }
      let frame = choice.frame
      let viewport = list.exists ? list.frame : app.frame
      guard !frame.isNull, !frame.isInfinite, !frame.isEmpty,
        viewport.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
      return choice.isHittable
    }
    for _ in 0..<40 {
      if visibleChoice() { break }
      if list.exists { list.swipeUp() }
      else { app.swipeUp() }
    }
    XCTAssertTrue(choice.exists)
    XCTAssertTrue(visibleChoice())
    choice.tap()
  }
  @MainActor private func waitForDesktopAnswer(_ marker: String, timeout: TimeInterval = 210) {
    let answer = app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch
    if !answer.waitForExistence(timeout: timeout) {
      shot("desktop-creation-failure")
      print("DESKTOP_CREATION_DIAGNOSTIC", app.debugDescription)
    }
    XCTAssertTrue(answer.exists)
    let idle = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [idle], timeout: 45), .completed)
    XCTAssertFalse(app.buttons["查询状态"].exists)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertTrue((app.buttons["client-switch"].value as? String ?? "").contains("Codex · 实时连接"))
  }
  @MainActor private func configureOriginalSharedAcceptanceGeneration() throws {
    let settings = app.buttons["generation-settings"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: settings)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    XCTAssertTrue(settings.label.contains("GPT-6.1-Sol"), "Keep the actual desktop model; do not silently change acceptance targets")
    guard settings.label.contains("GPT-6.1-Sol") else { throw URLError(.cannotParseResponse) }
    settings.tap()
    XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 15))
    let effort = app.descendants(matching: .any)["effort-picker"].firstMatch
    XCTAssertTrue(effort.label.contains("极高"))
    guard effort.label.contains("极高") else { throw URLError(.cannotParseResponse) }
    let readOnly = app.buttons["permission-read-only"]
    if !readOnly.isHittable { app.swipeUp() }
    readOnly.tap(); app.buttons["generation-done"].tap()
    XCTAssertTrue(settings.label.contains("极高"))
  }
  @MainActor func testOriginalDesktopProjectCreation() async throws {
    let fixture = try await desktopFixture()
    if fixture.sharedOriginalDesktop == true {
      app.terminate(); app.launchArguments = []; app.launchChineseTest()
      let live = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
      if XCTWaiter.wait(for: [live], timeout: 20) != .completed { connectLive(fixture) }
    } else { connectLive(fixture) }
    let search = openDesktopSidebarSearch()
    search.tap()
    search.typeText(try XCTUnwrap(fixture.creationProjectName))
    search.typeText("\n")
    let create = app.buttons["new-in-" + (try XCTUnwrap(fixture.creationProjectId))]
    for _ in 0..<5 where !create.exists || !create.isHittable { app.swipeUp() }
    XCTAssertTrue(create.isHittable)
    create.tap()
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    if fixture.sharedOriginalDesktop == true { try configureOriginalSharedAcceptanceGeneration() }
    let marker = "IOS_PROJECT_" + fixture.nonce
    composer.tap()
    typeComposerText("手机项目内新建验收：仅原样回复 \(marker)，不要使用工具。")
    sendLiveComposer()
    XCTAssertTrue(app.otherElements["desktop-creation-message"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.descendants(matching: .any)["desktop-creation-message-loading"].exists)
    XCTAssertTrue(app.buttons["composer-busy"].exists)
    XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
    shot("desktop-project-message-sending")
    waitForDesktopAnswer(marker)
    XCTAssertFalse(app.otherElements["desktop-creation-message"].exists)
    let created = try await actualWorkspace(fixture).filter { ($0["title"] as? String ?? "").contains(marker) }
    XCTAssertEqual(created.count, 1)
    XCTAssertEqual(created.first?["projectId"] as? String, fixture.creationProjectId)
    shot("desktop-project-created")
  }
  @MainActor func testOriginalDesktopRegularAppCreation() async throws {
    let fixture = try await desktopFixture()
    // Use ordinary persisted App storage and normal capability gates, with no testing flags.
    app.terminate()
    app.launchArguments = []
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    // Ordinary storage is separate from test storage; pair through the public UI.
    connectLive(fixture)
    let enabled = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "isEnabled == true"), object: app.buttons["new-chat"])
    XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 35), .completed)
    chooseDesktopProject(try XCTUnwrap(fixture.creationProjectId))
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    let marker = "IOS_RELEASE_" + fixture.nonce
    composer.tap()
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    typeComposerText("手机正式模式新建验收：仅原样回复 \(marker)，不要使用工具。")
    let send = app.buttons["send-message"]
    XCTAssertEqual(app.buttons.matching(identifier: "send-message").count, 1)
    XCTAssertTrue(send.isEnabled)
    print("DESKTOP_REGULAR_ACTION_FRAMES", send.frame, app.buttons["voice-input"].frame)
    shot("desktop-ordinary-before-send")
    app.buttons["send-message"].tap()
    XCTAssertFalse(app.staticTexts["dictation-transcript"].exists, "Sending must not activate dictation")
    waitForDesktopAnswer(marker)
    let created = try await actualWorkspace(fixture).filter { ($0["title"] as? String ?? "").contains(marker) }
    XCTAssertEqual(created.count, 1)
    XCTAssertEqual(created.first?["projectId"] as? String, fixture.creationProjectId)
    shot("desktop-ordinary-app-created")
  }
  @MainActor func testOriginalDesktopEmptyAndProjectlessCreation() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture)
    for (project, kind) in [(try XCTUnwrap(fixture.emptyProjectId), "EMPTY"), (nil as String?, "PROJECTLESS")] {
      chooseDesktopProject(project)
      let marker = "IOS_" + kind + "_" + fixture.nonce
      composer.tap()
      typeComposerText("手机\(kind)新建验收：仅原样回复 \(marker)，不要使用工具。")
      sendLiveComposer()
      let progress = app.otherElements["desktop-creation-message"]
      let answer = app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch
      // XCTest may finish waiting for app animations after a fast real request has completed.
      // The controlled creation fixture separately requires every transient loading state.
      XCTAssertTrue(progress.waitForExistence(timeout: 5) || answer.exists)
      if progress.exists {
        XCTAssertTrue(app.descendants(matching: .any)["desktop-creation-message-loading"].exists)
        XCTAssertTrue(app.buttons["composer-busy"].exists)
        XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
        shot("desktop-" + kind.lowercased() + "-message-sending")
      }
      waitForDesktopAnswer(marker)
      XCTAssertFalse(app.otherElements["desktop-creation-message"].exists)
      let created = try await actualWorkspace(fixture).filter { ($0["title"] as? String ?? "").contains(marker) }
      XCTAssertEqual(created.count, 1)
      XCTAssertEqual(created.first?["projectId"] as? String, project)
      shot("desktop-" + kind.lowercased() + "-created")
    }
  }
  @MainActor func testOriginalDesktopCreationRestartRecovery() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture)
    chooseDesktopProject(try XCTUnwrap(fixture.creationProjectId))
    let marker = "IOS_RECOVERY_" + fixture.nonce
    composer.tap()
    typeComposerText("手机重启恢复验收：仅原样回复 \(marker)，不要使用工具。")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["查询状态"].waitForExistence(timeout: 20))
    // The pending UI also appears during prepare. Terminate only after the Host
    // has accepted submit; otherwise this tests an explicitly unsubmitted draft.
    var submitted = false
    for _ in 0..<100 {
      let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/creation-status")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      submitted = (try JSONSerialization.jsonObject(with: data) as? [String: Bool])?["submitted"] == true
      if submitted { break }
      try await Task.sleep(for: .milliseconds(200))
    }
    XCTAssertTrue(submitted, "Host must accept the creation before restart recovery")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["查询状态"].waitForExistence(timeout: 20))
    XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
    app.buttons["查询状态"].tap()
    waitForDesktopAnswer(marker)
    let created = try await actualWorkspace(fixture).filter { ($0["title"] as? String ?? "").contains(marker) }
    XCTAssertEqual(created.count, 1)
    shot("desktop-creation-restored-without-replay")
  }
  @MainActor func testOriginalDesktopUnloadedOpenAndReply() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture)
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 10))
    if !app.textFields["sidebar-search"].waitForExistence(timeout: 1) {
      let toggle = app.buttons["sidebar-search-toggle"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: toggle)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
      toggle.tap()
    }
    let search = app.textFields["sidebar-search"]
    if !search.waitForExistence(timeout: 10) { shot("unloaded-sidebar-search-failure"); print("UNLOADED_SEARCH_DIAGNOSTIC", app.debugDescription) }
    XCTAssertTrue(search.exists)
    search.tap()
    search.typeText(try XCTUnwrap(fixture.unloadedThreadTitle))
    let row = app.buttons["recent-thread-" + (try XCTUnwrap(fixture.unloadedThreadId))]
    XCTAssertTrue(row.waitForExistence(timeout: 60))
    row.tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 120), .completed)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    let marker = "IOS_OPENED_" + fixture.nonce
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"), object: composer)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
    composer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    if !app.keyboards.firstMatch.waitForExistence(timeout: 5) { composer.tap() }
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    typeComposerText("手机主动打开已有会话验收：仅原样回复 \(marker)，不要使用工具。")
    sendLiveComposer()
    waitForDesktopAnswer(marker)
    shot("desktop-unloaded-opened-and-replied")
  }
  @MainActor func testOriginalDesktopHostRestartRecovery() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture)
    chooseDesktopProject(try XCTUnwrap(fixture.creationProjectId))
    let marker = "IOS_HOST_RECOVERY_" + fixture.nonce
    composer.tap()
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
    typeComposerText("手机Host重启恢复验收：仅原样回复 \(marker)，不要使用工具。")
    sendLiveComposer()
    let answer = app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch
    XCTAssertTrue(app.buttons["查询状态"].waitForExistence(timeout: 5) || answer.exists)
    let (_, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8795/restart-host")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    if app.buttons["查询状态"].waitForExistence(timeout: 5) {
      XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
      app.buttons["查询状态"].tap()
    }
    waitForDesktopAnswer(marker)
    let created = try await actualWorkspace(fixture).filter { ($0["title"] as? String ?? "").contains(marker) }
    XCTAssertEqual(created.count, 1)
    shot("desktop-host-restarted-without-replay")
  }
  @MainActor func testDesktopCreationPendingSurvivesRestartWithoutReplay() async throws {
    guard let result = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8796")!) else {
      throw XCTSkip("Run the desktop creation iOS fixture harness; this is not real desktop acceptance")
    }
    XCTAssertEqual((result.1 as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: result.0)
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-fixture-project"].tap()
    composer.tap()
    typeComposerText("CREATE_FIXTURE_" + fixture.nonce)
    tapAISharingAction(app.buttons["send-message"])
    let pending = app.buttons["查询状态"]
    XCTAssertTrue(pending.waitForExistence(timeout: 15))
    if app.alerts.firstMatch.exists { app.alerts.firstMatch.buttons.firstMatch.tap() }
    XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
    XCTAssertFalse(app.buttons["继续上传并创建"].exists)
    shot("desktop-fixture-pending-before-restart")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(pending.waitForExistence(timeout: 15))
    XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
    app.buttons["查询状态"].tap()
    // Observation must preserve uncertainty until the desktop actually confirms.
    XCTAssertTrue(pending.exists)
    var confirmation = URLRequest(url: URL(string: "http://127.0.0.1:8796/confirm")!)
    confirmation.httpMethod = "POST"
    let (_, confirmationResponse) = try await URLSession.shared.data(for: confirmation)
    XCTAssertEqual((confirmationResponse as? HTTPURLResponse)?.statusCode, 200)
    XCTAssertTrue(app.staticTexts["FIXTURE_CONFIRMED_" + fixture.nonce].waitForExistence(timeout: 20))
    XCTAssertFalse(pending.exists)
    shot("desktop-fixture-confirmed-without-second-send")
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8796/results")!)
    let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    XCTAssertEqual(counters["submitCount"] as? Int, 1)
    XCTAssertEqual(counters["replyCount"] as? Int, 0)
    XCTAssertEqual(counters["projectId"] as? String, "fixture-project")
  }
  @MainActor func testDesktopCreationUnknownDraftCanBeClearedAndDetached() async throws {
    guard let response = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/reset?mode=draft-recovery")!) else {
      throw XCTSkip("Run the creation recovery fixture; this does not verify a desktop runtime")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: response.0)
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-fixture-project"].tap()
    XCTAssertFalse(app.buttons["send-message"].isEnabled)
    shot("creation-send-disabled-empty")
    focusLiveComposer()
    typeComposerText("RECOVER_FIXTURE_" + fixture.nonce)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    shot("creation-send-enabled-draft")
    sendLiveComposer()
    let pending = app.otherElements["desktop-creation-pending"]
    XCTAssertTrue(app.buttons["detach-creation-wait"].waitForExistence(timeout: 15), app.debugDescription)
    let message = app.otherElements["desktop-creation-message"]
    XCTAssertTrue(message.waitForExistence(timeout: 5), app.debugDescription)
    XCTAssertTrue(message.staticTexts["RECOVER_FIXTURE_" + fixture.nonce].exists)
    XCTAssertTrue(app.descendants(matching: .any)["desktop-creation-message-loading"].exists)
    XCTAssertEqual(app.staticTexts["desktop-creation-message-status"].label, "发送中…")
    XCTAssertTrue(app.buttons["composer-busy"].exists)
    XCTAssertFalse(app.buttons["composer-busy"].isEnabled)
    XCTAssertFalse(app.otherElements["empty-chat"].exists)
    shot("creation-message-sending")
    XCUIDevice.shared.press(.home)
    XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(message.waitForExistence(timeout: 15))
    XCTAssertTrue(message.staticTexts["RECOVER_FIXTURE_" + fixture.nonce].exists)
    XCTAssertTrue(app.descendants(matching: .any)["desktop-creation-message-loading"].exists)
    XCTAssertTrue(composer.isEnabled, "Remote uncertainty must not lock local input")
    XCTAssertTrue(app.buttons["composer-busy"].exists, "Restarted pending creation must keep the button loading")
    focusLiveComposer()
    typeComposerText("_EDITED")
    XCTAssertTrue((composer.value as? String ?? "").contains("_EDITED"))
    XCTAssertTrue(message.staticTexts["RECOVER_FIXTURE_" + fixture.nonce].exists,
      "The pending message must use immutable submitted content")
    XCTAssertFalse(message.staticTexts["RECOVER_FIXTURE_" + fixture.nonce + "_EDITED"].exists)
    app.buttons["open-sidebar"].tap()
    app.buttons["open-settings"].tap()
    app.descendants(matching: .any)["appearance-picker"].firstMatch.tap()
    app.buttons["深色"].tap()
    app.buttons["完成"].tap()
    XCTAssertTrue(message.staticTexts["RECOVER_FIXTURE_" + fixture.nonce].exists)
    shot("creation-message-sending-dark")
    quickAttachmentPanel()
    quickAttachmentControl("clear-composer-draft").tap()
    XCTAssertFalse((composer.value as? String ?? "").contains("RECOVER_FIXTURE_"))
    XCTAssertTrue(message.exists, "Clearing a draft must preserve the pending message")
    XCTAssertTrue(app.buttons["detach-creation-wait"].exists)
    XCTAssertTrue(app.buttons["composer-busy"].exists, "Clearing a draft must not end creation loading")
    app.buttons["detach-creation-wait"].tap()
    app.buttons["保留记录并返回编辑"].tap()
    assertCreationRecordInMenu()
    XCTAssertFalse(app.buttons["detach-creation-wait"].exists)
    XCTAssertFalse(pending.exists)
    XCTAssertFalse(message.exists)
    XCTAssertFalse(app.buttons["composer-busy"].exists)
    focusLiveComposer()
    typeComposerText("NEW_FIXTURE_" + fixture.nonce)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    shot("creation-send-enabled-dark")
    XCUIDevice.shared.press(.home)
    XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    assertCreationRecordInMenu()
    XCTAssertEqual(composer.value as? String, "NEW_FIXTURE_" + fixture.nonce)
    _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/confirm-old")!)
    app.buttons["chat-menu"].tap()
    app.buttons["查询记录 · 待确认"].tap()
    try await Task.sleep(for: .seconds(1))
    app.buttons["chat-menu"].tap()
    XCTAssertTrue(app.buttons["查询记录 · 已确认"].waitForExistence(timeout: 15))
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).tap()
    XCTAssertEqual(composer.value as? String, "NEW_FIXTURE_" + fixture.nonce, "Late receipt must not erase newer draft")
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["RECOVERY_NEW_CONFIRMED_" + fixture.nonce].waitForExistence(timeout: 20))
    XCTAssertFalse(message.exists, "Confirmed history must replace the pending message")
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/results")!)
    let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    XCTAssertEqual(counters["submitCount"] as? Int, 2)
    XCTAssertEqual(counters["oldSubmitCount"] as? Int, 1)
    XCTAssertEqual(counters["detachCount"] as? Int, 1)
    XCTAssertEqual(counters["replyCount"] as? Int, 0)
    XCTAssertEqual(counters["originalText"] as? String, "RECOVER_FIXTURE_" + fixture.nonce)
    shot("creation-unknown-draft-restart-late-receipt")
  }
  @MainActor func testDesktopCreationTimeoutObservesWithoutResubmit() async throws {
    guard let response = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/reset?mode=timeout-recovery")!) else {
      throw XCTSkip("Run the creation recovery fixture; this does not verify a desktop runtime")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: response.0)
    connectLive(fixture)
    app.buttons["new-chat"].tap(); app.buttons["choose-project-fixture-project"].tap()
    composer.tap(); typeComposerText("TIMEOUT_FIXTURE_" + fixture.nonce); sendLiveComposer()
    if app.alerts.firstMatch.waitForExistence(timeout: 8) { app.alerts.firstMatch.buttons["好"].tap() }
    XCTAssertTrue(app.staticTexts["RECOVERY_NEW_CONFIRMED_" + fixture.nonce].waitForExistence(timeout: 25), app.debugDescription)
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/results")!)
    let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    XCTAssertEqual(counters["prepareCount"] as? Int, 1)
    XCTAssertEqual(counters["submitCount"] as? Int, 1)
    XCTAssertGreaterThanOrEqual(counters["statusQueries"] as? Int ?? 0, 3)
    XCTAssertFalse(app.buttons["detach-creation-wait"].exists)
  }
  @MainActor func testDesktopCreationMissingOperationCanEndWait() async throws {
    guard let response = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/reset?mode=missing-operation")!) else {
      throw XCTSkip("Run the creation recovery fixture; this does not verify a desktop runtime")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: response.0)
    connectLive(fixture)
    app.buttons["new-chat"].tap(); app.buttons["choose-project-fixture-project"].tap()
    composer.tap(); typeComposerText("MISSING_FIXTURE_" + fixture.nonce); sendLiveComposer()
    if app.alerts.firstMatch.waitForExistence(timeout: 8) { app.alerts.firstMatch.buttons["好"].tap() }
    XCTAssertTrue(app.buttons["detach-creation-wait"].waitForExistence(timeout: 10))
    app.buttons["detach-creation-wait"].tap(); app.buttons["保留记录并返回编辑"].tap()
    assertCreationRecordInMenu()
    XCTAssertFalse(app.buttons["detach-creation-wait"].exists)
    XCTAssertEqual(composer.value as? String, "MISSING_FIXTURE_" + fixture.nonce)
    XCTAssertTrue(app.buttons["send-message"].isEnabled)
    XCUIDevice.shared.press(.home); XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5))
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    assertCreationRecordInMenu()
    XCTAssertEqual(composer.value as? String, "MISSING_FIXTURE_" + fixture.nonce)
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8806/results")!)
    let counters = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    XCTAssertEqual(counters["prepareCount"] as? Int, 1)
    XCTAssertEqual(counters["submitCount"] as? Int, 0)
    XCTAssertEqual(counters["detachCount"] as? Int, 1)
  }
  @MainActor private func assertCreationRecordInMenu() {
    XCTAssertFalse(app.staticTexts["此前新建结果仍待确认"].exists)
    XCTAssertFalse(app.staticTexts["此前新建已确认"].exists)
    XCTAssertFalse(app.buttons["detached-creation-receipt"].firstMatch.exists)
    XCTAssertFalse(app.buttons["查询记录 · 待确认"].exists)
    app.buttons["chat-menu"].tap()
    // UIKit menus expose the visible action label, without SwiftUI's identifier.
    XCTAssertTrue(app.buttons["查询记录 · 待确认"].waitForExistence(timeout: 10))
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).tap()
  }

  @MainActor func testFullRuntimeProjectlessAndConversationSwitch() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture)
    let projectMarker = "PROJECT_" + fixture.nonce.prefix(8)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    composer.tap()
    typeComposerText("只原样回复 \(projectMarker)，不要调用工具。")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts[String(projectMarker)].waitForExistence(timeout: 100))
    let finish = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [finish], timeout: 45), .completed)
    let projectWorkspace = try await actualWorkspace(fixture)
    let projectThread = try XCTUnwrap(projectWorkspace.first { ($0["title"] as? String)?.contains(projectMarker) == true })
    let projectId = try XCTUnwrap(projectThread["id"] as? String)
    composer.tap()
    typeComposerText("project-draft")
    app.buttons["new-chat"].tap()
    app.buttons["choose-projectless"].tap()
    XCTAssertTrue(app.buttons["client-switch"].label.contains("无项目"))
    let blankMarker = "PROJECTLESS_" + fixture.nonce.prefix(8)
    composer.tap()
    typeComposerText("只原样回复 \(blankMarker)，不要调用工具。")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts[String(blankMarker)].waitForExistence(timeout: 100))
    let blankFinish = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [blankFinish], timeout: 45), .completed)
    let blankWorkspace = try await actualWorkspace(fixture)
    let blankThread = try XCTUnwrap(blankWorkspace.first { ($0["title"] as? String)?.contains(blankMarker) == true })
    let blankId = try XCTUnwrap(blankThread["id"] as? String)
    XCTAssertTrue(blankThread["projectId"] is NSNull)
    for _ in 0..<3 {
      app.buttons["open-sidebar"].tap()
      app.buttons["recent-thread-" + projectId].tap()
      XCTAssertEqual(composer.value as? String, "project-draft")
      XCTAssertFalse(app.alerts.firstMatch.exists)
      app.buttons["open-sidebar"].tap()
      app.buttons["recent-thread-" + blankId].tap()
      XCTAssertFalse((composer.value as? String ?? "").contains("project-draft"))
      XCTAssertTrue(app.buttons["client-switch"].label.contains("无项目"))
      XCTAssertFalse(app.alerts.firstMatch.exists)
    }
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.staticTexts[String(blankMarker)].waitForExistence(timeout: 30))
    XCTAssertTrue(app.buttons["client-switch"].label.contains("无项目"))
  }
  @MainActor func testFullRuntimeEmptyProjectFirstConversation() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture)
    app.buttons["open-sidebar"].tap()
    app.buttons["add-project"].tap()
    let name = app.textFields["project-name"]
    XCTAssertTrue(name.waitForExistence(timeout: 5))
    name.tap()
    name.typeText("Empty " + fixture.nonce.prefix(8))
    let directory = app.textFields["project-directory"]
    directory.tap()
    directory.typeText(XCUIKeyboardKey.delete.rawValue + "empty")
    app.buttons["confirm-project"].tap()
    let returned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: app.buttons["new-chat"])
    XCTAssertEqual(XCTWaiter.wait(for: [returned], timeout: 15), .completed)
    app.buttons["new-chat"].tap()
    let project = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'choose-project-' AND label CONTAINS %@", "Empty " + fixture.nonce.prefix(8))).firstMatch
    XCTAssertTrue(project.waitForExistence(timeout: 15))
    project.tap()
    XCTAssertTrue(app.staticTexts["有什么可以帮忙的？"].exists)
    let marker = "EMPTY_PROJECT_" + fixture.nonce.prefix(8)
    composer.tap()
    typeComposerText("只原样回复 \(marker)，不要调用工具。")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts[String(marker)].waitForExistence(timeout: 100))
    let finish = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [finish], timeout: 45), .completed)
    let emptyWorkspace = try await actualWorkspace(fixture)
    let created = try XCTUnwrap(emptyWorkspace.first { ($0["title"] as? String)?.contains(marker) == true })
    XCTAssertNotEqual(created["projectId"] as? String, "agent-workspace")
    XCTAssertNotNil(created["projectId"] as? String)
  }
  @MainActor func testFullRuntimeNativeSendAndRestore() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    let marker = "IOS_NATIVE_" + String(fixture.nonce.prefix(8))
    composer.tap()
    typeComposerText(
      "原生App验收：先用update_plan创建并完成两个步骤‘检查格式’、‘完成验收’。最终回复首行必须是 \(marker)，接着依次使用Markdown二级标题‘传输验收’、两个无序列表项、一段引用、两列的功能/状态表格（图片和文件，状态均为可用），最后输出swift代码块，代码为 let accepted = true。每个块间空一行。不要操作文件。"
    )
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 20))
    let answer = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", marker))
      .firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 150))
    shot("real-native-stream")
    let completed = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [completed], timeout: 120), .completed)
    XCTAssertTrue(
      app.descendants(matching: .any)["markdown-heading"].firstMatch.waitForExistence(timeout: 10))
    XCTAssertTrue(app.descendants(matching: .any)["markdown-table"].firstMatch.exists)
    XCTAssertTrue(app.descendants(matching: .any)["markdown-quote"].firstMatch.exists)
    XCTAssertTrue(app.buttons["copy-code"].firstMatch.exists)
    let plan = app.descendants(matching: .any)["runtime-plan"].firstMatch
    XCTAssertTrue(plan.waitForExistence(timeout: 10))
    XCTAssertTrue(plan.label.contains("2/2"))
    shot("real-native-formatted-plan")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(answer.waitForExistence(timeout: 20))
    XCTAssertTrue((app.buttons["client-switch"].value as? String ?? "").contains("实时连接"))
    shot("real-native-relaunch")
  }
  @MainActor func testFullRuntimeStopAndApproval() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    composer.tap()
    typeComposerText("停止操作验收：请持续输出二十段中文，每段至少150字，介绍软件工程。直接输出，不调用工具。")
    app.buttons["send-message"].tap()
    let stop = app.buttons["stop-turn"]
    XCTAssertTrue(stop.waitForExistence(timeout: 25))
    stop.tap()
    let stopped = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [stopped], timeout: 35), .completed)
    let marker = "APPROVAL_IOS_" + String(fixture.nonce.prefix(8))
    composer.tap()
    typeComposerText(
      "专用审批验收：请使用 exec_command 执行 printf \(marker)，显式设置 sandbox_permissions=require_escalated，justification=验证移动端审批。命令不会读取或修改任何文件。必须发起权限请求等待批准，批准后执行，最终只回复命令输出，不加格式。"
    )
    app.buttons["send-message"].tap()
    let accept = app.buttons["approval-accept"]
    XCTAssertTrue(accept.waitForExistence(timeout: 180))
    if !accept.isHittable { app.swipeUp() }
    shot("real-native-approval")
    accept.tap()
    let answer = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", marker))
      .firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 150))
    let completed = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"), object: stop)
    XCTAssertEqual(XCTWaiter.wait(for: [completed], timeout: 90), .completed)
    XCTAssertFalse(accept.exists)
    shot("real-native-control-completed")
  }
  @MainActor func testFullRuntimeSlowSendPreservesNewDraft() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture, endpoint: fixture.delayedEndpoint)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    let marker = "SLOW_DRAFT_" + String(fixture.nonce.prefix(8))
    composer.tap()
    typeComposerText("Reply exactly \(marker), without formatting.")
    app.buttons["send-message"].tap()
    let pending = app.descendants(matching: .any)["outbox-pending"].firstMatch
    XCTAssertTrue(pending.waitForExistence(timeout: 8))
    XCTAssertFalse((composer.value as? String ?? "").contains(marker))
    composer.tap()
    let nextDraft = "NEXT_DRAFT_AFTER_SEND"
    typeComposerText(nextDraft)
    XCTAssertTrue(
      pending.exists, "The new draft must be entered before the submitted message is acknowledged")
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 150))
    XCTAssertEqual(composer.value as? String, nextDraft)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertEqual(composer.value as? String, nextDraft)
    shot("real-slow-ack-preserved-draft")
  }
  @MainActor func testFullRuntimeTwoHostsKeepTargetsSeparate() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    let markerA = "HOST_A_" + String(fixture.nonce.prefix(8))
    let markerB = "HOST_B_" + String(fixture.nonce.prefix(8))
    let promptA = "原样回复 \(markerA)，仅输出这一个字符串，不加格式，不调用工具。"
    composer.tap()
    typeComposerText(promptA)
    connectLive(fixture, second: true)
    XCTAssertFalse((composer.value as? String ?? "").contains(markerA))
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    composer.tap()
    typeComposerText("原样回复 \(markerB)，仅输出这一个字符串，不加格式，不调用工具。")
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts[markerB].waitForExistence(timeout: 150))
    switchClient(fixture.hostId)
    XCTAssertEqual(composer.value as? String, promptA)
    XCTAssertFalse(app.staticTexts[markerB].exists)
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.staticTexts[markerA].waitForExistence(timeout: 150))
    switchClient(fixture.secondHostId)
    XCTAssertTrue(app.staticTexts[markerB].waitForExistence(timeout: 20))
    XCTAssertFalse(app.staticTexts[markerA].exists)
    shot("real-two-host-isolation")
  }
  @MainActor func testFullRuntimePhotoFileUploadAndDownload() async throws {
    let fixture = try await liveFixture()
    connectLive(fixture)
    app.buttons["new-chat"].tap()
    app.buttons["choose-project-agent-workspace"].tap()
    app.buttons["attachment-menu"].tap()
    app.buttons["选择图片"].tap()
    selectPhotoFromSystemPicker()
    app.buttons["attachment-menu"].tap()
    app.buttons["选择文件"].tap()
    let browse = app.buttons.matching(NSPredicate(format: "label == 'Browse' OR label == '浏览'"))
      .firstMatch
    if browse.waitForExistence(timeout: 3) { browse.tap() }
    let phone = app.staticTexts.matching(
      NSPredicate(
        format: "label == 'On My iPhone' OR label == '我的 iPhone' OR label == '我的iPhone'")
    ).firstMatch
    if phone.waitForExistence(timeout: 3) { phone.tap() }
    let folder = app.descendants(matching: .any).matching(
      NSPredicate(format: "label == 'Agent Hub'")
    ).firstMatch
    if folder.waitForExistence(timeout: 3) { folder.tap() }
    let file = app.collectionViews.cells.matching(
      NSPredicate(format: "label CONTAINS 'native-input'")
    ).firstMatch
    if !file.waitForExistence(timeout: 5) {
      print("FILE_PICKER_FINAL_STATE", app.debugDescription)
    }
    XCTAssertTrue(file.exists)
    if app.buttons["跳转最新消息"].exists { app.buttons["跳转最新消息"].tap() }
    for _ in 0..<35 where !file.isHittable { app.scrollViews["message-list"].swipeUp() }
    XCTAssertTrue(file.isHittable, app.debugDescription)
    file.tap()
    let open = app.buttons.matching(NSPredicate(format: "label == 'Open' OR label == '打开'"))
      .firstMatch
    if open.waitForExistence(timeout: 2), open.isEnabled { open.tap() }
    let imported = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "count == 2"),
      object: app.buttons.matching(identifier: "remove-attachment"))
    XCTAssertEqual(XCTWaiter.wait(for: [imported], timeout: 10), .completed)
    composer.tap()
    typeComposerText(
      "混合附件原生验收：判断图片颜色，使用工具读取TXT校验码，写到当前目录的 native-report.txt；复制图片到 native-output.png。最后给出两份文件的绝对路径 Markdown 链接，回复必须包含TXT完整校验码。只操作当前工作目录和上传的附件。"
    )
    app.buttons["send-message"].tap()
    XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 25))
    let report = app.staticTexts["native-report.txt"]
    XCTAssertTrue(report.waitForExistence(timeout: 240))
    let finished = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 180), .completed)
    app.swipeUp()
    let downloads = app.buttons.matching(identifier: "preview-native-report.txt")
    XCTAssertTrue(downloads.firstMatch.waitForExistence(timeout: 20))
    XCTAssertTrue(app.buttons["preview-native-report.txt"].firstMatch.waitForExistence(timeout: 15))
    XCTAssertTrue(
      app.images.matching(identifier: "received-image-native-output.png").firstMatch
        .waitForExistence(timeout: 15))
    shot("real-photo-file-result")
    app.buttons["preview-native-report.txt"].firstMatch.tap()
    let done = app.buttons.matching(
      NSPredicate(
        format:
          "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label == '关闭' OR label == 'Close' OR label == 'Done' OR label == '完成'"
      )
    )
    .firstMatch
    let previewOpened = done.waitForExistence(timeout: 10)
    shot("real-file-quicklook")
    if !previewOpened { print("Owned file preview hierarchy:\n" + app.debugDescription) }
    XCTAssertTrue(previewOpened)
    XCTAssertTrue(
      app.textViews.matching(NSPredicate(format: "label CONTAINS %@", fixture.nonce)).firstMatch
        .waitForExistence(timeout: 10))
    done.tap()
    XCTAssertTrue(app.buttons["preview-native-report.txt"].firstMatch.waitForExistence(timeout: 5))
  }
  @MainActor func testOriginalDesktopLongFilenameCreation() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture)
    chooseDesktopProject(nil)
    app.buttons["generation-settings"].tap()
    XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 15))
    app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
    app.buttons["极高"].tap()
    app.buttons["generation-done"].tap()
    app.buttons["attachment-menu"].tap()
    app.buttons["选择文件"].tap()
    let browse = app.buttons.matching(NSPredicate(format: "label == 'Browse' OR label == '浏览'")).firstMatch
    if browse.waitForExistence(timeout: 3) { browse.tap() }
    let phone = app.staticTexts.matching(NSPredicate(format: "label == 'On My iPhone' OR label == '我的 iPhone' OR label == '我的iPhone'")).firstMatch
    if phone.waitForExistence(timeout: 3) { phone.tap() }
    let folder = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Agent Hub'")).firstMatch
    if folder.waitForExistence(timeout: 3) { folder.tap() }
    let file = app.collectionViews.cells.matching(NSPredicate(format: "label CONTAINS 'native-long-input'")).firstMatch
    XCTAssertTrue(file.waitForExistence(timeout: 10), app.debugDescription)
    file.tap()
    let open = app.buttons.matching(NSPredicate(format: "label == 'Open' OR label == '打开'")).firstMatch
    if open.waitForExistence(timeout: 2), open.isEnabled { open.tap() }
    XCTAssertTrue(app.buttons.matching(identifier: "remove-attachment").firstMatch.waitForExistence(timeout: 10))
    composer.tap()
    composer.typeText("长中文文件名附件验收：只读附件文件，完整原样回复文件中的校验码，不修改文件。")
    sendLiveComposer()
    if fixture.attachmentMarker == "expect-rejected" {
      let alert = app.alerts["无法完成操作"]
      XCTAssertTrue(alert.waitForExistence(timeout: 25))
      XCTAssertTrue(alert.staticTexts["附件清单无效"].exists)
      shot("long-filename-before-rejected")
      alert.buttons["好"].tap()
      XCTAssertTrue(app.buttons["返回草稿"].exists)
      XCTAssertTrue((composer.value as? String ?? "").contains("长中文文件名附件验收"))
    } else {
      waitForDesktopAnswer("LONG_FILENAME_" + fixture.nonce, timeout: 240)
      shot("long-filename-after-model-read")
    }
  }
  @MainActor func testOriginalDesktopMessageLinksDEV() async throws {
    struct LinkFixture: Decodable { let connection: LiveFixture; let labels: [String]; let webMarker: String; let historyFallback: Bool? }
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8896/")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LinkFixture.self, from: data)
    connectLive(fixture.connection, supportsCreation: false)
    openDesktopTestConversation(fixture.connection)
    if fixture.historyFallback == true {
      let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "LINK_FILE_" + fixture.connection.nonce)).firstMatch
      XCTAssertTrue(answer.waitForExistence(timeout: 30), app.debugDescription)
      XCTAssertFalse(app.buttons["conversation-sync-retry"].exists, "Fresh terminal history must not report a live connection failure")
      shot("history-fallback-terminal-without-false-live-warning")
    }
    let timeline = app.scrollViews["message-list"]
    func tapLink(_ label: String) {
      let link = app.links.matching(NSPredicate(format: "label == %@", label)).firstMatch
      let fallback = app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch
      for _ in 0..<12 where !(link.exists && link.isHittable) && !(fallback.exists && fallback.isHittable) {
        let element = link.exists ? link : fallback
        if element.exists && element.frame.minY < timeline.frame.minY { timeline.swipeDown() }
        else { timeline.swipeUp() }
      }
      if link.exists && link.isHittable { link.tap() }
      else { XCTAssertTrue(fallback.isHittable, app.debugDescription); fallback.tap() }
    }
    for label in fixture.labels {
      tapLink(label)
      let done = app.buttons.matching(NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label == '关闭' OR label == 'Close' OR label == 'Done' OR label == '完成'")).firstMatch
      XCTAssertTrue(done.waitForExistence(timeout: 15), app.debugDescription)
      XCTAssertTrue(app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "LINK_FILE_" + fixture.connection.nonce)).firstMatch.waitForExistence(timeout: 10))
      shot("message-link-preview-" + label)
      done.tap()
    }
    func updateFile(_ stage: Int) async throws -> String {
      struct Update: Decodable { let marker: String }
      let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8896/update?stage=\(stage)")!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try JSONDecoder().decode(Update.self, from: data).marker
    }
    func checkPreview(_ marker: String) {
      let done = app.buttons.matching(NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label == '关闭' OR label == 'Close' OR label == 'Done' OR label == '完成'")).firstMatch
      XCTAssertTrue(done.waitForExistence(timeout: 15), app.debugDescription)
      XCTAssertTrue(app.textViews.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 10), app.debugDescription)
      shot("message-link-updated-preview-" + marker)
      done.tap()
    }
    for stage in [1, 2] {
      let marker = try await updateFile(stage)
      tapLink(fixture.labels[0])
      checkPreview(marker)
    }
    let preview = app.buttons["preview-验证 (记录).txt"].firstMatch
    for _ in 0..<10 where !preview.isHittable { timeline.swipeUp() }
    XCTAssertTrue(preview.waitForExistence(timeout: 15), app.debugDescription)
    preview.tap()
    checkPreview("LINK_FILE_" + fixture.connection.nonce + "_UPDATED_2")
    let marker = try await updateFile(3)
    XCTAssertTrue(preview.waitForExistence(timeout: 15), app.debugDescription)
    preview.tap()
    checkPreview(marker)
    if fixture.historyFallback == true {
      func setHistoryState(_ value: String) async throws {
        let (_, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8896/history-state?value=" + value)!)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      }
      let retry = app.buttons["conversation-sync-retry"]
      for state in ["running", "unknown", "read-failure", "pending-reply"] {
        try await setHistoryState(state)
        XCTAssertTrue(retry.waitForExistence(timeout: 35), "Missing warning for " + state)
        shot("history-fallback-warning-" + state)
        try await setHistoryState("completed")
        XCTAssertTrue(retry.waitForNonExistence(timeout: 35), app.debugDescription)
      }
      shot("history-fallback-terminal-recovered")
    }
    tapLink("网页预览")
    XCTAssertTrue(app.staticTexts[fixture.webMarker].waitForExistence(timeout: 25), app.debugDescription)
    shot("message-link-web-preview")
    let browserDone = app.buttons.matching(NSPredicate(format: "label == 'Done' OR label == '完成' OR label == '关闭' OR label == 'Close'")).firstMatch
    XCTAssertTrue(browserDone.waitForExistence(timeout: 10)); browserDone.tap()
    for (label, reason) in [("缺失文件", "电脑上的文件不存在"), ("电脑本机网页", "此网址指向电脑的本机地址"), ("不支持的链接", "手机暂不支持此类链接")] {
      tapLink(label)
      let alert = app.alerts["无法打开链接"]
      XCTAssertTrue(alert.waitForExistence(timeout: 10), app.debugDescription)
      XCTAssertTrue(alert.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", reason)).firstMatch.exists)
      XCTAssertTrue(alert.buttons["复制链接"].exists)
      shot("message-link-error-" + label)
      alert.buttons["好"].tap()
    }
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 15))
    openDesktopTestConversation(fixture.connection)
    tapLink(fixture.labels[0])
    XCTAssertTrue(app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "LINK_FILE_" + fixture.connection.nonce + "_UPDATED_3")).firstMatch.waitForExistence(timeout: 15))
    shot("message-link-restart-preview")
  }
  @MainActor func testOriginalDesktopPhotoFileUploadAndDownload() async throws {
    let fixture = try await desktopFixture()
    connectLive(fixture, supportsCreation: false)
    if let projectId = fixture.creationProjectId {
      chooseDesktopProject(projectId)
    } else if fixture.sharedOriginalDesktop == true && fixture.threadId.isEmpty {
      chooseDesktopProject(nil)
    } else {
      openDesktopTestConversation(fixture)
      let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
      XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 25), .completed)
    }
    if fixture.attachmentOutputDirectory != nil {
      app.buttons["generation-settings"].tap()
      XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 15))
      app.descendants(matching: .any)["model-picker"].firstMatch.tap()
      app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "GPT-6.1-Sol")).firstMatch.tap()
      app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
      app.buttons["极高"].tap()
      let full = app.buttons["permission-full-access"]
      if !full.isHittable { app.swipeUp() }
      full.tap(); app.buttons["generation-done"].tap()
    }
    app.buttons["attachment-menu"].tap()
    app.buttons["选择图片"].tap()
    selectPhotoFromSystemPicker()
    app.buttons["attachment-menu"].tap()
    app.buttons["选择文件"].tap()
    let browse = app.buttons.matching(NSPredicate(format: "label == 'Browse' OR label == '浏览'"))
      .firstMatch
    if browse.waitForExistence(timeout: 3) { browse.tap() }
    let phone = app.staticTexts.matching(
      NSPredicate(
        format: "label == 'On My iPhone' OR label == '我的 iPhone' OR label == '我的iPhone'")
    ).firstMatch
    if phone.waitForExistence(timeout: 3) { phone.tap() }
    let folder = app.descendants(matching: .any).matching(
      NSPredicate(format: "label == 'Agent Hub'")
    ).firstMatch
    if folder.waitForExistence(timeout: 3) { folder.tap() }
    let file = app.collectionViews.cells.matching(
      NSPredicate(format: "label CONTAINS 'native-input'")
    ).firstMatch
    if !file.waitForExistence(timeout: 5) {
      print("FILE_PICKER_FINAL_STATE", app.debugDescription)
    }
    XCTAssertTrue(file.exists)
    if app.buttons["跳转最新消息"].exists { app.buttons["跳转最新消息"].tap() }
    for _ in 0..<35 where !file.isHittable { app.scrollViews["message-list"].swipeUp() }
    XCTAssertTrue(file.isHittable, app.debugDescription)
    file.tap()
    let open = app.buttons.matching(NSPredicate(format: "label == 'Open' OR label == '打开'"))
      .firstMatch
    if open.waitForExistence(timeout: 2), open.isEnabled { open.tap() }
    let imported = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "count == 2"),
      object: app.buttons.matching(identifier: "remove-attachment"))
    XCTAssertEqual(XCTWaiter.wait(for: [imported], timeout: 10), .completed)
    composer.tap()
    typeComposerText(
      "混合附件原生验收 \(fixture.attachmentMarker ?? "existing")：判断图片颜色，使用工具读取TXT校验码，写到\(fixture.attachmentOutputDirectory ?? "当前目录的 .local")/desktop-native-report.txt；复制图片到同一输出目录的 desktop-native-output.png。最后给出两份文件的绝对路径 Markdown 链接，回复必须包含TXT完整校验码。只操作这一输出目录和上传的附件。"
    )
    sendLiveComposer()
    let report = app.staticTexts["desktop-native-report.txt"]
    // A fast first task can finish before the creation receipt reaches the App.
    let startedOrFinished = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == 'stop-turn' OR label == 'desktop-native-report.txt'"))
    let visible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "count > 0"), object: startedOrFinished)
    XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 120), .completed)
    XCTAssertTrue(report.waitForExistence(timeout: 240))
    let finished = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "exists == false"), object: app.buttons["stop-turn"])
    XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 180), .completed)
    app.swipeUp()
    let downloads = app.buttons.matching(identifier: "preview-desktop-native-report.txt")
    XCTAssertTrue(downloads.firstMatch.waitForExistence(timeout: 20))
    XCTAssertTrue(app.buttons["preview-desktop-native-report.txt"].firstMatch.waitForExistence(timeout: 15))
    XCTAssertTrue(
      app.images.matching(identifier: "received-image-desktop-native-output.png").firstMatch
        .waitForExistence(timeout: 15))
    shot("real-photo-file-result")
    app.buttons["preview-desktop-native-report.txt"].firstMatch.tap()
    let done = app.buttons.matching(
      NSPredicate(
        format:
          "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label == '关闭' OR label == 'Close' OR label == 'Done' OR label == '完成'"
      )
    )
    .firstMatch
    let previewOpened = done.waitForExistence(timeout: 10)
    shot("real-file-quicklook")
    if !previewOpened { print("Owned file preview hierarchy:\n" + app.debugDescription) }
    XCTAssertTrue(previewOpened)
    XCTAssertTrue(
      app.textViews.matching(NSPredicate(format: "label CONTAINS %@", fixture.nonce)).firstMatch
        .waitForExistence(timeout: 10))
    done.tap()
    XCTAssertTrue(app.buttons["preview-desktop-native-report.txt"].firstMatch.waitForExistence(timeout: 5))
  }
  @MainActor private func enableDEVQuestionNotificationPreview() {
    if !app.buttons["sidebar-client-switch"].exists { app.buttons["open-sidebar"].tap() }
    app.buttons["open-settings"].tap(); app.buttons["通知"].tap()
    if app.alerts["通知"].waitForExistence(timeout: 4) { app.alerts["通知"].buttons["好"].tap() }
    let preview = app.switches["notification-content-preview"]
    for _ in 0..<16 where !preview.isHittable {
      if app.alerts["通知"].exists { app.alerts["通知"].buttons["好"].tap() }
      app.swipeUp()
    }
    XCTAssertTrue(preview.isHittable)
    if preview.value as? String == "0" { preview.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap() }
    XCTAssertEqual(preview.value as? String, "1")
    app.navigationBars["通知"].buttons.firstMatch.tap()
    app.buttons["完成"].firstMatch.tap()
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
    enableDEVBadgePermission()
    if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
  }
  @MainActor private func verifyDEVSystemQuestionNotification(_ title: String, threadTitle: String) {
    let screen = app.frame
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let start = springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: 1))
    let finish = springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: screen.height * 0.85))
    start.press(forDuration: 0.1, thenDragTo: finish)
    let question = springboard.staticTexts.matching(NSPredicate(format: "label == %@", title)).firstMatch
    if !question.waitForExistence(timeout: 5) {
      let group = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", threadTitle)).firstMatch
      if group.exists { group.tap() }
    }
    XCTAssertTrue(question.waitForExistence(timeout: 15), "The system notice must show the specific pending question")
    shot("async-question-system-notification-specific-prompt", fullScreen: true)
    app.activate()
    if app.buttons["open-async-questions"].exists { app.buttons["open-async-questions"].tap() }
    XCTAssertTrue(app.buttons["answer-question"].firstMatch.waitForExistence(timeout: 15))
  }
  @MainActor func testOriginalDesktopQuestionNotificationPreview() async throws {
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8895")!) else {
      throw XCTSkip("Requires the original-owner question notification DEV fixture")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    guard fixture.verifyQuestionNotifications == true else { throw XCTSkip("Enable question notification acceptance explicitly") }
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    enableDEVQuestionNotificationPreview()
    openDesktopTestConversation(fixture)
    try await Task.sleep(for: .seconds(12))
    verifyDEVSystemQuestionNotification("可选的发布渠道", threadTitle: fixture.threadTitle ?? "异步提问验收")
  }
  @MainActor func testOriginalDesktopActionNotificationPreview() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8895")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    let title = try XCTUnwrap(fixture.threadTitle)
    let summary = try XCTUnwrap(fixture.notificationSummary)
    XCTAssertFalse(title.isEmpty); XCTAssertFalse(summary.isEmpty)
    if fixture.reuseDeliveredNotification != true {
      enableDEVBadgePermission()
      if app.buttons["close-sidebar"].exists { app.buttons["close-sidebar"].tap() }
      connectLive(fixture, supportsCreation: false)
      try await Task.sleep(for: .seconds(12))
    }
    let screen = app.windows.firstMatch.frame
    XCUIDevice.shared.press(.home)
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    print("NOTIFICATION_COORDINATE_FRAMES", screen, springboard.frame, springboard.windows.firstMatch.frame)
    let origin = springboard.coordinate(withNormalizedOffset: .zero)
    origin.withOffset(CGVector(dx: screen.width * 0.2, dy: 1)).press(forDuration: 0.1,
      thenDragTo: origin.withOffset(CGVector(dx: screen.width * 0.2, dy: screen.height * 0.85)))
    let heading = springboard.staticTexts.matching(NSPredicate(format: "label == %@", title)).firstMatch
    let details = springboard.staticTexts.matching(NSPredicate(format: "label == %@", summary)).firstMatch
    let count = springboard.buttons.matching(NSPredicate(format: "label MATCHES %@", "[0-9]+个通知")).firstMatch
    if count.waitForExistence(timeout: 3) { count.tap() }
    if !details.waitForExistence(timeout: 8), heading.exists { heading.tap() }
    XCTAssertTrue(heading.waitForExistence(timeout: 10), "A system notice must identify its conversation")
    XCTAssertTrue(details.waitForExistence(timeout: 10), "A system notice must describe its specific pending action")
    XCTAssertTrue(springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fixture.notificationKind ?? "待审批")).firstMatch.exists)
    shot("real-action-notification-conversation-and-summary", fullScreen: true)
  }
  @MainActor func testOriginalDesktopAsyncQuestions() async throws {
    let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8895")!)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    func verifySidebarQuestions(_ count: Int, running: Bool = false) {
      app.buttons["open-sidebar"].tap()
      let row = app.buttons["recent-thread-" + fixture.threadId]
      XCTAssertTrue(row.waitForExistence(timeout: 10))
      let predicate = count > 0
        ? NSPredicate(format: "value == %@", (running ? "执行中，" : "") + "\(count) 个问题待回答")
        : NSPredicate(format: "NOT (value CONTAINS %@)", "问题待回答")
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: row)], timeout: 15), .completed)
      shot("async-question-sidebar-\(count)-\(running ? "running" : "idle")")
      app.buttons["close-sidebar"].tap()
    }
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    if fixture.verifyQuestionNotifications == true {
      enableDEVQuestionNotificationPreview()
    }
    openDesktopTestConversation(fixture)
    let marker = "ASYNC_DONE_" + fixture.nonce
    if fixture.asyncQuestionTaskPrestarted != true {
      let generation = app.buttons["generation-settings"]
      XCTAssertTrue(generation.waitForExistence(timeout: 20))
      if !generation.label.contains("极高") {
        generation.tap()
        XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 10))
        let effort = app.sliders["effort-slider"]
        XCTAssertTrue(effort.waitForExistence(timeout: 10))
        let expectedEffortPosition = try XCTUnwrap(fixture.effortPosition)
        // A stepped SwiftUI slider can land one step away from XCTest's requested
        // normalized position. Verify the actual label before sending any input.
        for offset in [0.0, 0.05, 0.1, -0.05, -0.1] {
          effort.adjust(toNormalizedSliderPosition: CGFloat(min(1, max(0, expectedEffortPosition + offset))))
          if app.staticTexts["极高"].firstMatch.waitForExistence(timeout: 1) { break }
        }
        XCTAssertTrue(app.staticTexts["极高"].firstMatch.waitForExistence(timeout: 10))
        let generationDone = app.buttons["generation-done"]
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: generationDone)], timeout: 15), .completed, app.debugDescription)
        generationDone.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      }
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", "极高"), object: generation)], timeout: 15), .completed, app.debugDescription)
      focusLiveComposer()
      typeComposerText("真实异步问题专项验收。必须调用 request_user_input_async 一次提出三个问题：第一个标题为请选择交付格式，选项为简洁版和详细版；第二个标题为请输入验收暗号，不提供选项；第三个标题为可选的发布渠道，选项为测试渠道和正式渠道。提问后不要等待回答，立即调用 exec_command 执行 printf 'ASYNC_CONTINUE_\(fixture.nonce)\\n'; sleep 100。独立工作完成后再读取异步回答；收到手机选择简洁版以及暗号 PHONE_\(fixture.nonce) 后仅回复 \(marker)。第三题可以跳过，不要求回答；不要调用同步 request_user_input，不要修改项目文件，不要创建其他聊天。")
      sendLiveComposer()
    }
    let submit = app.buttons["answer-question"].firstMatch
    XCTAssertTrue(submit.waitForExistence(timeout: 180), app.debugDescription)
    XCTAssertTrue(app.staticTexts["任务仍在继续，可随时回答或跳过"].firstMatch.exists)
    XCTAssertFalse(submit.isEnabled)
    XCTAssertTrue(submit.isHittable, "The answer action must be visible without expanding the sheet")
    let firstAnswer = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "question-custom-")).firstMatch
    XCTAssertTrue(firstAnswer.isHittable)
    XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "question-voice-")).firstMatch.isHittable)
    var continuedWhileUnanswered = false
    let witnessDeadline = Date().addingTimeInterval(45)
    while Date() < witnessDeadline && !continuedWhileUnanswered {
      let (statusData, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8895/status")!)
      let status = try JSONSerialization.jsonObject(with: statusData) as? [String: Bool]
      continuedWhileUnanswered = status?["continuedWhileUnanswered"] == true
      if !continuedWhileUnanswered { try await Task.sleep(for: .milliseconds(250)) }
    }
    XCTAssertTrue(continuedWhileUnanswered, "Independent owner must execute the tool before any answer")
    let page = app.staticTexts["async-question-page"]
    XCTAssertEqual(page.label, "1 / 3")
    XCTAssertFalse(app.buttons["async-question-prev"].isEnabled)
    XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "question-option-")).count, 2)
    app.buttons["async-question-next"].tap()
    XCTAssertEqual(page.label, "2 / 3")
    let draftField = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "question-custom-")).firstMatch
    draftField.tap(); draftField.typeText("PHONE_" + fixture.nonce)
    app.buttons["async-question-next"].tap()
    XCTAssertEqual(page.label, "3 / 3")
    XCTAssertFalse(app.buttons["async-question-next"].isEnabled)
    let channelTitle = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "question-title-", "可选的发布渠道")).firstMatch
    channelTitle.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)).press(forDuration: 0.05,
      thenDragTo: channelTitle.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)))
    XCTAssertEqual(page.label, "2 / 3")
    XCTAssertEqual(draftField.value as? String, "PHONE_" + fixture.nonce)
    app.buttons["async-question-prev"].tap()
    XCTAssertEqual(page.label, "1 / 3")
    app.buttons["close-async-questions"].tap()
    XCTAssertTrue(app.buttons["open-async-questions"].waitForExistence(timeout: 10))
    let collapsed = app.buttons["open-async-questions"]
    XCTAssertLessThanOrEqual(collapsed.frame.height, 46)
    XCTAssertTrue(collapsed.isHittable)
    app.scrollViews["message-list"].swipeDown()
    XCTAssertTrue(collapsed.isHittable, "Pending questions must remain visible while reading history")
    XCTAssertFalse(app.buttons["answer-question"].exists)
    shot("async-question-00-collapsed-chat")
    verifySidebarQuestions(3, running: true)
    app.buttons["open-async-questions"].tap()
    XCTAssertTrue(submit.waitForExistence(timeout: 10))
    shot("async-question-01-pending-running")
    let option = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "question-option-", "简洁版")).firstMatch
    XCTAssertTrue(option.exists, app.debugDescription)
    option.tap(); submit.tap()
    if allowTestAISharingIfNeeded() {
      XCTAssertTrue(app.buttons["open-async-questions"].waitForExistence(timeout: 10))
      app.buttons["open-async-questions"].tap()
      XCTAssertTrue(submit.waitForExistence(timeout: 10))
      XCTAssertTrue(submit.isEnabled, "The confirmed sharing sheet must preserve the answer draft")
      submit.tap()
    }
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: option)], timeout: 15), .completed)
    let custom = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "question-custom-")).firstMatch
    XCTAssertTrue(custom.waitForExistence(timeout: 15))
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "question-title-", "请输入验收暗号")).firstMatch.exists, "Answer must automatically advance to the next question")
    XCTAssertEqual(page.label, "1 / 2")
    let customId = custom.identifier
    XCTAssertEqual(custom.value as? String, "PHONE_" + fixture.nonce, "Paging and closing must retain each draft")
    shot("async-question-02-freeform")
    XCTAssertTrue(app.buttons["answer-question"].firstMatch.isEnabled)
    app.buttons["answer-question"].firstMatch.tap()
    let answeredCustom = app.descendants(matching: .any).matching(identifier: customId).firstMatch
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: answeredCustom)], timeout: 15), .completed)
    let skip = app.buttons["skip-question"].firstMatch
    XCTAssertTrue(skip.waitForExistence(timeout: 15))
    XCTAssertTrue(channelTitle.exists, "Freeform submission must automatically advance")
    skip.tap()
    XCTAssertTrue(app.staticTexts[marker].waitForExistence(timeout: 180), app.debugDescription)
    XCTAssertFalse(app.buttons["skip-question"].exists)
    XCTAssertFalse(app.buttons["answer-question"].exists)
    shot("async-question-03-completed")
    verifySidebarQuestions(0)
    // A completed task retains its optional question as a follow-up, without a waiting state.
    focusLiveComposer()
    typeComposerText("第二轮异步专项：必须调用 request_user_input_async 提一个问题，标题为结束后继续对话，选项为继续验收和稍后处理。不要等回答，立即最终回复 ASYNC_IDLE_\(fixture.nonce)。之后收到回答继续验收时仅回复 ASYNC_FOLLOWUP_\(fixture.nonce)。")
    sendLiveComposer()
    XCTAssertTrue(app.staticTexts["任务已结束，回答将继续此对话"].firstMatch.waitForExistence(timeout: 180), app.debugDescription)
    shot("async-question-04-idle-followup")
    app.buttons["close-async-questions"].tap()
    verifySidebarQuestions(1)
    app.buttons["open-async-questions"].tap()
    if fixture.verifyQuestionNotifications == true {
      // This is the actual Host foreground feed and iOS notification presentation.
      try await Task.sleep(for: .seconds(12))
      verifyDEVSystemQuestionNotification("结束后继续对话", threadTitle: fixture.threadTitle ?? "异步提问验收")
    }
    let followup = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "question-option-", "继续验收")).firstMatch
    followup.tap(); app.buttons["answer-question"].firstMatch.tap()
    XCTAssertTrue(app.staticTexts["ASYNC_FOLLOWUP_" + fixture.nonce].waitForExistence(timeout: 180), app.debugDescription)
    XCTAssertFalse(app.buttons["answer-question"].exists)
    verifySidebarQuestions(0)
    shot("async-question-05-followup-completed")
  }
  @MainActor private func openQuestionFixtureConversation(_ fixture: LiveFixture) {
    // This fixture exposes one thread. Select that exact visible row instead
    // of depending on search-toggle hit testing under the full-height history.
    let row = app.buttons["recent-thread-" + fixture.threadId]
    if !row.exists, app.buttons["open-sidebar"].exists {
      app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }
    XCTAssertTrue(row.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(app.frame.contains(row.frame), app.debugDescription)
    row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
  }

  @MainActor func testQuestionVoiceFailureMatchesComposerRecovery() async throws {
    let address = ProcessInfo.processInfo.environment["QUESTION_PANEL_FIXTURE_URL"] ?? "http://127.0.0.1:8803"
    let suppliedFixture = ProcessInfo.processInfo.environment["QUESTION_PANEL_FIXTURE_JSON"]
    let data: Data
    if let suppliedFixture { data = Data(suppliedFixture.utf8) }
    else { (data, _) = try await URLSession.shared.data(from: URL(string: address)!) }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture", "-voice-transcription-failed"]
    app.launchChineseTest()
    connectLive(fixture, supportsCreation: false, afterConnectTap: {
      let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
      let allow = system.alerts.buttons.matching(NSPredicate(format: "label IN %@",
        ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow"])).firstMatch
      if allow.waitForExistence(timeout: 5) { allow.tap() }
    })
    openQuestionFixtureConversation(fixture)
    if suppliedFixture == nil {
      var activate = URLRequest(url: URL(string: fixture.endpoint + "/activate?reset=true")!)
      activate.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      _ = try await URLSession.shared.data(for: activate)
    }
    let submit = app.buttons["answer-question"]
    if !submit.exists && app.buttons["open-async-questions"].exists {
      app.buttons["open-async-questions"].tap()
    }
    XCTAssertTrue(submit.waitForExistence(timeout: 15))
    app.buttons["async-question-next"].tap()
    let answer = app.descendants(matching: .any)["question-custom-long"].firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 5))
    answer.tap(); answer.typeText("保留问题草稿")
    let mic = app.buttons["question-voice-long"]
    func startRecording() {
      mic.tap()
      if allowTestAISharingIfNeeded() {
        if app.buttons["open-async-questions"].exists { app.buttons["open-async-questions"].tap() }
        XCTAssertTrue(mic.waitForExistence(timeout: 5)); mic.tap()
      }
      XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "结束录音并转写"), object: mic)], timeout: 5), .completed)
      XCTAssertFalse(submit.isEnabled)
    }
    startRecording()
    app.buttons["question-cancel-dictation-long"].tap()
    XCTAssertEqual(answer.value as? String, "保留问题草稿")
    startRecording()
    mic.tap()
    XCTAssertTrue(app.staticTexts["question-voice-failure-long"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertEqual(mic.label, "重试转写")
    XCTAssertEqual(app.state, .runningForeground)
    shot("question-voice-inline-recovery-options", fullScreen: true)
    mic.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "重试转写"), object: mic)], timeout: 5), .completed)
    XCTAssertTrue(app.staticTexts["question-voice-failure-long"].exists)
    XCTAssertEqual(answer.value as? String, "保留问题草稿")
    app.buttons["question-local-transcription-long"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "value == %@", "保留问题草稿\n语音输入测试"), object: answer)], timeout: 5), .completed)
    XCTAssertEqual(mic.label, "语音输入回复")
    XCTAssertTrue(submit.isEnabled)
    XCTAssertFalse(app.otherElements["question-dictation-long"].exists)
    shot("question-voice-local-fallback-unsent-answer")
    app.buttons["async-question-next"].tap()
    let other = app.descendants(matching: .any)["question-custom-other"].firstMatch
    XCTAssertFalse((other.value as? String ?? "").contains("语音输入测试"))
    app.buttons["async-question-prev"].tap()
    XCTAssertEqual(answer.value as? String, "保留问题草稿\n语音输入测试")
    if suppliedFixture == nil {
      let (status, _) = try await URLSession.shared.data(from: URL(string: fixture.endpoint + "/status")!)
      XCTAssertEqual((try JSONSerialization.jsonObject(with: status) as? [String: Any])?["answers"] as? Int, 0,
        "Transcribing an answer must never submit it")
    }
  }


  @MainActor func testQuestionPanelLayoutAndDictation() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8803")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate()
    app.launchArguments = ["-ui-testing", "-voice-input-fixture"]
    app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openQuestionFixtureConversation(fixture)
    var activate = URLRequest(url: URL(string: fixture.endpoint + "/activate")!)
    activate.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
    _ = try await URLSession.shared.data(for: activate)
    let submit = app.buttons["answer-question"]
    XCTAssertTrue(submit.waitForExistence(timeout: 15))
    func visibleAnswer(_ id: String, keyboard: Bool = false) {
      let field = app.descendants(matching: .any)["question-custom-" + id].firstMatch
      let mic = app.buttons["question-voice-" + id]
      XCTAssertTrue(field.isHittable, app.debugDescription)
      XCTAssertTrue(mic.isHittable)
      XCTAssertTrue(submit.isHittable)
      let bottom = keyboard ? app.keyboards.firstMatch.frame.minY : app.frame.maxY - 20
      XCTAssertLessThanOrEqual(submit.frame.maxY, bottom)
      XCTAssertLessThanOrEqual(field.frame.maxY, submit.frame.minY)
      XCTAssertGreaterThanOrEqual(mic.frame.minX, field.frame.maxX - 1)
    }
    visibleAnswer("short")
    shot("question-ui-short")
    app.buttons["async-question-next"].tap()
    visibleAnswer("long")
    shot("question-ui-long-visible")
    let field = app.descendants(matching: .any)["question-custom-long"].firstMatch
    field.tap(); field.typeText("保留草稿")
    visibleAnswer("long", keyboard: true)
    shot("question-ui-long-keyboard")
    let mic = app.buttons["question-voice-long"]
    mic.tap()
    if allowTestAISharingIfNeeded() {
      if app.buttons["open-async-questions"].exists { app.buttons["open-async-questions"].tap() }
      XCTAssertTrue(mic.waitForExistence(timeout: 5)); mic.tap()
    }
    let waveform = app.descendants(matching: .any)["question-dictation-long"].firstMatch
    XCTAssertTrue(waveform.waitForExistence(timeout: 5))
    XCTAssertEqual(field.value as? String, "保留草稿", "Recording leaves the original answer unchanged until remote transcription completes")
    XCTAssertFalse(submit.isEnabled, "Finish dictation before submitting the answer")
    app.buttons["question-cancel-dictation-long"].tap()
    XCTAssertEqual(field.value as? String, "保留草稿")
    mic.tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "结束录音并转写"), object: mic)], timeout: 5), .completed)
    XCTAssertEqual(waveform.label, "轻触停止录音")
    app.buttons["question-dictation-long"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "语音输入回复"), object: mic)], timeout: 5), .completed)
    XCTAssertEqual(field.value as? String, "保留草稿\n语音输入测试")
    XCTAssertGreaterThanOrEqual(field.frame.height, 38, "Two lines of draft text must remain visible")
    shot("question-ui-dictation-retained")
    mic.tap()
    XCTAssertTrue(waveform.waitForExistence(timeout: 5))
    app.buttons["async-question-next"].tap()
    XCTAssertFalse(waveform.exists)
    let other = app.descendants(matching: .any)["question-custom-other"].firstMatch
    XCTAssertTrue((other.value as? String ?? "").isEmpty || other.value as? String == "输入回复或补充说明", "Dictation must not enter another question")
    app.buttons["async-question-prev"].tap()
    XCTAssertFalse(waveform.exists)
    XCTAssertTrue((field.value as? String ?? "").hasPrefix("保留草稿\n语音输入测试"))
    app.buttons["close-async-questions"].tap()
    let collapsed = app.buttons["open-async-questions"]
    XCTAssertTrue(collapsed.waitForExistence(timeout: 5))
    XCTAssertEqual(collapsed.frame.height, 44, accuracy: 1)
    let originalFrame = collapsed.frame
    app.scrollViews["message-list"].swipeDown()
    XCTAssertEqual(collapsed.frame.minY, originalFrame.minY, accuracy: 1)
    XCTAssertTrue(collapsed.isHittable)
    shot("question-ui-collapsed-history")
    collapsed.tap()
    XCTAssertTrue(submit.waitForExistence(timeout: 5))
    mic.tap()
    XCTAssertTrue(waveform.waitForExistence(timeout: 5))
    XCUIDevice.shared.press(.home); app.activate()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "重试转写"), object: mic)], timeout: 5), .completed)
    XCTAssertTrue((field.value as? String ?? "").contains("语音输入测试"))
    app.buttons["question-cancel-dictation-long"].tap()
    XCTAssertFalse(waveform.exists)
    app.buttons["async-question-prev"].tap()
    app.buttons["question-option-short-简洁版"].tap()
    submit.tap()
    if allowTestAISharingIfNeeded() {
      collapsed.tap()
      XCTAssertTrue(submit.waitForExistence(timeout: 5))
      submit.tap()
    }
    XCTAssertTrue(field.waitForExistence(timeout: 10))
    XCTAssertTrue(submit.isEnabled)
    submit.tap()
    XCTAssertTrue(other.waitForExistence(timeout: 10))
    app.buttons["skip-question"].tap()
    XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: submit)], timeout: 10), .completed)
    XCTAssertFalse(collapsed.exists)
    let (statusData, _) = try await URLSession.shared.data(from: URL(string: fixture.endpoint + "/status")!)
    let status = try XCTUnwrap(JSONSerialization.jsonObject(with: statusData) as? [String: Any])
    XCTAssertEqual(status["answers"] as? Int, 3, "Answers and skip must each submit once")
    XCTAssertTrue((composer.value as? String ?? "").isEmpty || composer.value as? String == "输入消息，长按语音输入")
  }
  @MainActor private func openDesktopSidebarSearch() -> XCUIElement {
    dismissLiveQuestionPresentation()
    if name.contains("testOriginalDesktopReadOnlySuggestionsDEV") || name.contains("testWindowsConversationSyncReadOnlyDEV") || name.contains("testThreadModelSettingsPhysicalDEV") {
      physicalSidebar()
    } else {
      app.buttons["open-sidebar"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 10))
    }
    let search = app.textFields["sidebar-search"]
    if !search.waitForExistence(timeout: 1) {
      let toggle = app.buttons["sidebar-search-toggle"]
      if name.contains("testOriginalDesktopReadOnlySuggestionsDEV") {
        XCTAssertTrue(tapReadOnlySuggestionControl(toggle, step: "sidebar-search-toggle"))
      } else {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: toggle)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
        toggle.tap()
      }
    }
    let appeared = search.waitForExistence(timeout: 10)
    if !appeared && name.contains("testOriginalDesktopReadOnlySuggestionsDEV") {
      captureSuggestionUIFailure("sidebar-search-field")
    }
    XCTAssertTrue(appeared)
    return search
  }
  @MainActor private func openDesktopTestConversation(_ fixture: LiveFixture) {
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap()
    search.typeText(fixture.threadTitle ?? "HOST_A_d09b9a33")
    search.typeText("\n")
    let thread = app.buttons["recent-thread-" + fixture.threadId]
    XCTAssertTrue(thread.waitForExistence(timeout: 20))
    thread.tap()
  }

  private let threadForkFixtureURL = URL(string: "http://127.0.0.1:8817")!
  @MainActor private func threadForkFixture(_ mode: String) async throws -> LiveFixture {
    guard let (data, response) = try? await URLSession.shared.data(from: threadForkFixtureURL.appendingPathComponent("reset").appending(queryItems: [URLQueryItem(name: "mode", value: mode)])) else {
      throw XCTSkip("Start scripts/thread-fork-ios-fixture.mjs on the reserved simulator")
    }
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    return try JSONDecoder().decode(LiveFixture.self, from: data)
  }
  @MainActor private func threadForkCounters() async throws -> [String: Any] {
    let (data, _) = try await URLSession.shared.data(from: threadForkFixtureURL.appendingPathComponent("results"))
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }
  @MainActor private func confirmThreadForkFixture() async throws {
    let (_, response) = try await URLSession.shared.data(from: threadForkFixtureURL.appendingPathComponent("confirm"))
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
  }
  @MainActor private func openForkFixtureThread(_ id: String) {
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    app.buttons["open-sidebar"].tap()
    let row = app.buttons["recent-thread-" + id]
    for _ in 0..<5 where !row.isHittable { app.swipeUp() }
    XCTAssertTrue(row.waitForExistence(timeout: 15)); row.tap()
  }
  @MainActor private func forkActionGroup(_ id: String = "reply-visible-earlier") -> XCUIElement {
    app.descendants(matching: .any)["run-reply-actions-" + id].firstMatch
  }
  @MainActor private func openFirstForkMenu(_ id: String = "reply-visible-earlier") {
    let group = forkActionGroup(id)
    for _ in 0..<12 where !group.buttons["fork-reply"].isHittable {
      app.swipeDown()
      let earlier = app.buttons["history-local-more"].exists ? app.buttons["history-local-more"] : app.buttons["history-more"]
      if !group.exists && earlier.isHittable { earlier.tap(); _ = group.waitForExistence(timeout: 3) }
    }
    XCTAssertTrue(group.waitForExistence(timeout: 15))
    let copy = group.buttons["copy-reply"], fork = group.buttons["fork-reply"], share = group.buttons["share-reply"]
    XCTAssertLessThan(copy.frame.midX, fork.frame.midX)
    XCTAssertLessThan(fork.frame.midX, share.frame.midX)
    var previousFrame = CGRect.null
    let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard fork.exists, fork.isHittable else { return false }
      let frame = fork.frame
      defer { previousFrame = frame }
      return !frame.isEmpty && !frame.isInfinite && frame == previousFrame
    }, object: fork)
    XCTAssertEqual(XCTWaiter.wait(for: [stable], timeout: 10), .completed)
    let frame = fork.frame
    app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
    XCTAssertTrue(app.buttons["fork-same-directory"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.buttons["fork-worktree"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.buttons["fork-same-directory"].label.contains("在当前工作目录中分支"))
    XCTAssertTrue(app.buttons["fork-worktree"].label.contains("在新工作树中分支"))
  }
  @MainActor private func tapStableForkOption(_ environment: String) {
    let option = app.buttons[environment == "worktree" ? "fork-worktree" : "fork-same-directory"]
    var previousFrame = CGRect.null
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard option.exists, option.isEnabled, option.isHittable else { return false }
      let frame = option.frame
      defer { previousFrame = frame }
      return !frame.isNull && !frame.isEmpty && !frame.isInfinite && frame == previousFrame
        && self.app.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }, object: option)
    XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed)
    let frame = option.frame
    app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
    // A click that did not dismiss the native Menu fails; never retry a possibly submitted command.
    let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: option)
    if XCTWaiter.wait(for: [dismissed], timeout: 10) != .completed {
      shot("thread-fork-menu-did-not-dismiss")
      XCTFail("Native fork menu did not dismiss after a stable visible row click")
    }
  }
  @MainActor private func tapForkEnvironment(_ environment: String, replyId: String = "reply-visible-earlier") {
    tapStableForkOption(environment)
    // Product consent grants permission only. The subsequent fork still needs its own explicit native click.
    if allowTestAISharingIfNeeded() {
      let closed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["ai-sharing-allow"])
      XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 10), .completed)
      openFirstForkMenu(replyId)
      tapStableForkOption(environment)
    }
  }
  @MainActor private func assertSingleFork(_ environment: String, fixture: LiveFixture) async throws {
    let counters = try await threadForkCounters()
    let posts = try XCTUnwrap(counters["posts"] as? [[String: Any]])
    XCTAssertEqual(posts.count, 1)
    let post = try XCTUnwrap(posts.first)
    XCTAssertEqual(post["hostId"] as? String, fixture.hostId)
    XCTAssertEqual(post["threadId"] as? String, fixture.threadId)
    XCTAssertEqual(post["turnId"] as? String, "actual-first-turn")
    XCTAssertEqual(post["environment"] as? String, environment)
    let queries = try XCTUnwrap(counters["queries"] as? [[String: Any]])
    for query in queries {
      XCTAssertEqual(query["hostId"] as? String, fixture.hostId)
      XCTAssertEqual(query["threadId"] as? String, fixture.threadId)
      XCTAssertEqual(query["id"] as? String, post["clientOperationId"] as? String)
    }
    XCTAssertEqual((counters["forbiddenInputs"] as? [Any])?.count, 0)
    XCTAssertEqual((counters["violations"] as? [Any])?.count, 0)
  }
  @MainActor private func runThreadForkRoute(_ environment: String, mode: String? = nil) async throws {
    let fixture = try await threadForkFixture(mode ?? environment)
    connectLive(fixture, supportsCreation: false)
    XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].waitForExistence(timeout: 20))
    composer.tap(); composer.typeText("original-fork-draft")
    app.buttons["composer-dismiss-keyboard"].tap()
    openFirstForkMenu(); tapForkEnvironment(environment)
    let opened = app.staticTexts["FORK_RESULT_" + environment].waitForExistence(timeout: 30)
    let evidence = try await threadForkCounters()
    let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: evidence, options: [.prettyPrinted, .sortedKeys]), uniformTypeIdentifier: "public.json")
    attachment.name = "thread-fork-route-" + (mode ?? environment)
    attachment.lifetime = .keepAlways; add(attachment)
    if !opened { shot("thread-fork-route-failure"); print(app.debugDescription) }
    XCTAssertTrue(opened)
    XCTAssertTrue(app.staticTexts["FIRST_COMPLETED_REPLY"].exists)
    XCTAssertFalse(app.staticTexts["SECOND_COMPLETED_REPLY"].exists)
    XCTAssertFalse((composer.value as? String ?? "").contains("original-fork-draft"))
    openForkFixtureThread(fixture.threadId)
    XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].waitForExistence(timeout: 15))
    XCTAssertEqual(composer.value as? String, "original-fork-draft")
    XCTAssertTrue(app.buttons["打开分支"].exists)
    try await assertSingleFork(environment, fixture: fixture)
    shot("thread-fork-" + environment + "-accurate-earlier-turn")
  }
  @MainActor func testThreadForkSameDirectory() async throws { try await runThreadForkRoute("same-directory") }
  @MainActor func testThreadForkWorktree() async throws { try await runThreadForkRoute("worktree") }
  @MainActor func testThreadForkPaginatedTurnSelection() async throws { try await runThreadForkRoute("same-directory", mode: "paginated") }
  @MainActor func testThreadForkRepeatAfterSuccess() async throws {
    let fixture = try await threadForkFixture("same-directory")
    connectLive(fixture, supportsCreation: false)
    for _ in 0..<2 {
      openFirstForkMenu(); tapForkEnvironment("same-directory")
      XCTAssertTrue(app.staticTexts["FORK_RESULT_same-directory"].waitForExistence(timeout: 30))
      openForkFixtureThread(fixture.threadId)
      XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].waitForExistence(timeout: 15))
    }
    let counters = try await threadForkCounters()
    let posts = try XCTUnwrap(counters["posts"] as? [[String: Any]])
    XCTAssertEqual(posts.count, 2)
    XCTAssertNotEqual(posts[0]["clientOperationId"] as? String, posts[1]["clientOperationId"] as? String)
    XCTAssertEqual((counters["forbiddenInputs"] as? [Any])?.count, 0)
    XCTAssertEqual((counters["violations"] as? [Any])?.count, 0)
  }
  @MainActor func testThreadForkDetachAndNewOperationOwnsNavigation() async throws {
    let fixture = try await threadForkFixture("pending")
    connectLive(fixture, supportsCreation: false)
    openFirstForkMenu(); tapForkEnvironment("same-directory")
    XCTAssertTrue(app.buttons["查询原回执"].waitForExistence(timeout: 15))
    let initialCounters = try await threadForkCounters()
    let original = try XCTUnwrap(try XCTUnwrap(initialCounters["posts"] as? [[String: Any]]).first)
    let oldId = try XCTUnwrap(original["clientOperationId"] as? String)
    _ = try await URLSession.shared.data(from: threadForkFixtureURL.appendingPathComponent("hold-original-query"))
    app.buttons["query-fork-" + oldId].tap()
    var held = false
    for _ in 0..<15 {
      held = (try await threadForkCounters())["heldQuery"] as? Bool == true
      if held { break }; try await Task.sleep(for: .milliseconds(200))
    }
    XCTAssertTrue(held)
    app.buttons["detach-fork-" + oldId].tap()
    XCTAssertTrue(app.staticTexts["仅结束本机等待；电脑端操作未取消，原回执保留。"].exists)
    openFirstForkMenu(); tapForkEnvironment("same-directory")
    composer.tap(); composer.typeText("new-operation-draft-on-same-source")
    app.buttons["composer-dismiss-keyboard"].tap()
    _ = try await URLSession.shared.data(from: threadForkFixtureURL.appendingPathComponent("release-original-query"))
    XCTAssertTrue(app.buttons["query-fork-" + oldId].waitForExistence(timeout: 15))
    let finished = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "打开分支"), object: app.buttons["query-fork-" + oldId])
    XCTAssertEqual(XCTWaiter.wait(for: [finished], timeout: 15), .completed)
    XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].exists, "An old confirmed receipt must not take the newer operation's navigation ownership")
    XCTAssertEqual(composer.value as? String, "new-operation-draft-on-same-source")
    let updatedCounters = try await threadForkCounters()
    let posts = try XCTUnwrap(updatedCounters["posts"] as? [[String: Any]])
    XCTAssertEqual(posts.count, 2)
    let newId = try XCTUnwrap(posts[1]["clientOperationId"] as? String)
    XCTAssertNotEqual(oldId, newId)
    // Persisted detachment keeps the old receipt queryable without resubmitting or observing it.
    app.terminate(); app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.staticTexts["仅结束本机等待；电脑端操作未取消，原回执保留。"].waitForExistence(timeout: 20))
    XCTAssertEqual(composer.value as? String, "new-operation-draft-on-same-source")
    try await confirmThreadForkFixture()
    app.buttons["query-fork-" + newId].tap()
    XCTAssertTrue(app.staticTexts["FORK_OPERATION_" + newId].waitForExistence(timeout: 30))
    XCTAssertFalse(app.staticTexts["FORK_OPERATION_" + oldId].exists)
    let counters = try await threadForkCounters()
    XCTAssertEqual((counters["posts"] as? [Any])?.count, 2)
    XCTAssertEqual((counters["forbiddenInputs"] as? [Any])?.count, 0)
    XCTAssertEqual((counters["violations"] as? [Any])?.count, 0)
  }

  @MainActor func testThreadForkPendingLateResultPreservesDraft() async throws {
    let fixture = try await threadForkFixture("pending")
    connectLive(fixture, supportsCreation: false)
    openFirstForkMenu(); tapForkEnvironment("same-directory")
    XCTAssertTrue(app.buttons["查询原回执"].waitForExistence(timeout: 15))
    openFirstForkMenu()
    XCTAssertFalse(app.buttons["fork-same-directory"].isEnabled)
    XCTAssertFalse(app.buttons["fork-worktree"].isEnabled)
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.1)).tap()
    composer.tap(); composer.typeText("source-draft-after-submit")
    openForkFixtureThread("fork-other")
    XCTAssertTrue(app.staticTexts["OTHER_TARGET"].waitForExistence(timeout: 15))
    composer.tap(); composer.typeText("other-target-draft")
    app.buttons["composer-dismiss-keyboard"].tap()
    try await confirmThreadForkFixture()
    // Allow the original observer to receive the terminal receipt while another thread is selected.
    for _ in 0..<15 {
      let counters = try await threadForkCounters()
      if (counters["queries"] as? [Any])?.isEmpty == false { break }
      try await Task.sleep(for: .seconds(1))
    }
    XCTAssertTrue(app.staticTexts["OTHER_TARGET"].exists)
    XCTAssertEqual(composer.value as? String, "other-target-draft")
    XCTAssertFalse(app.buttons["打开分支"].exists)
    openForkFixtureThread(fixture.threadId)
    XCTAssertEqual(composer.value as? String, "source-draft-after-submit")
    let query = app.buttons["打开分支"].exists ? app.buttons["打开分支"] : app.buttons["查询原回执"]
    XCTAssertTrue(query.waitForExistence(timeout: 15)); query.tap()
    XCTAssertTrue(app.staticTexts["FORK_RESULT_same-directory"].waitForExistence(timeout: 30))
    try await assertSingleFork("same-directory", fixture: fixture)
  }
  @MainActor func testThreadForkRestartQueriesOriginalReceipt() async throws {
    let fixture = try await threadForkFixture("pending")
    connectLive(fixture, supportsCreation: false)
    openFirstForkMenu(); tapForkEnvironment("worktree")
    XCTAssertTrue(app.buttons["查询原回执"].waitForExistence(timeout: 15))
    composer.tap(); composer.typeText("restart-retained-fork-draft")
    app.buttons["composer-dismiss-keyboard"].tap()
    app.terminate()
    try await confirmThreadForkFixture()
    app.launchArguments = ["-ui-testing", "-restore-testing"]; app.launchChineseTest()
    XCTAssertTrue(app.buttons["打开分支"].waitForExistence(timeout: 30))
    XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].exists, "Restored receipts must not navigate automatically")
    XCTAssertEqual(composer.value as? String, "restart-retained-fork-draft")
    app.buttons["打开分支"].tap()
    XCTAssertTrue(app.staticTexts["FORK_RESULT_worktree"].waitForExistence(timeout: 30))
    openForkFixtureThread(fixture.threadId)
    XCTAssertEqual(composer.value as? String, "restart-retained-fork-draft")
    try await assertSingleFork("worktree", fixture: fixture)
  }
  @MainActor private func selectForkFixtureClient(_ id: String, name: String) {
    app.buttons["client-switch"].tap()
    let row = app.buttons["client-" + id]
    XCTAssertTrue(row.waitForExistence(timeout: 10))
    print("FORK_CLIENT_ROW_BEFORE", id, row.frame, row.isHittable)
    shot("thread-fork-client-picker-before-" + id)
    for _ in 0..<6 {
      let frame = row.frame
      if row.isHittable && self.app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) { break }
      let list = self.app.collectionViews.firstMatch
      if list.exists { list.swipeUp() } else { self.app.swipeUp() }
    }
    var previousFrame = CGRect.null
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      let frame = row.frame
      guard row.isHittable, !frame.isNull, !frame.isInfinite, !frame.isEmpty,
        self.app.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) else { return false }
      defer { previousFrame = frame }
      return frame == previousFrame
    }, object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed)
    let frame = row.frame
    app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
    let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed)
    XCTAssertTrue(app.buttons["client-switch"].label.contains(name), app.debugDescription)
  }
  @MainActor func testThreadForkHostSwitchDoesNotNavigate() async throws {
    let fixture = try await threadForkFixture("pending")
    connectLive(fixture, supportsCreation: false, nickname: "Fork primary computer")
    connectLive(fixture, second: true, supportsCreation: false, nickname: "Fork other computer")
    selectForkFixtureClient(fixture.hostId, name: "Fork primary computer")
    openFirstForkMenu(); tapForkEnvironment("same-directory")
    XCTAssertTrue(app.buttons["查询原回执"].waitForExistence(timeout: 15))
    selectForkFixtureClient(fixture.secondHostId, name: "Fork other computer")
    composer.tap(); composer.typeText("other-host-same-thread-draft")
    app.buttons["composer-dismiss-keyboard"].tap()
    try await confirmThreadForkFixture()
    try await Task.sleep(for: .seconds(5))
    XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].exists)
    shot("thread-fork-switched-client-preserves-target")
    XCTAssertFalse(app.buttons["打开分支"].exists)
    XCTAssertEqual(composer.value as? String, "other-host-same-thread-draft")
    try await assertSingleFork("same-directory", fixture: fixture)
  }
  @MainActor func testThreadForkUnavailableOptions() async throws {
    for mode in ["non-git", "legacy", "history", "provider", "missing-capability"] {
      if mode != "non-git" { app.terminate(); app.launchArguments = ["-ui-testing"]; app.launchChineseTest() }
      let fixture = try await threadForkFixture(mode)
      connectLive(fixture, supportsCreation: false)
      openFirstForkMenu()
      XCTAssertEqual(app.buttons["fork-same-directory"].isEnabled, mode == "non-git")
      XCTAssertFalse(app.buttons["fork-worktree"].isEnabled)
      let reasons = [
        "non-git": "Fixture source is outside a Git repository",
        "legacy": "此回复缺少准确的轮次 ID，请更新电脑连接程序并重新读取历史",
        "history": "需要连接原桌面运行时，历史连接不能创建分支",
        "provider": "此客户端尚未支持对话分支",
        "missing-capability": "电脑尚未开放对话分支，请更新连接程序",
      ]
      // Native Menu exposes subtitles/footer text through merged accessibility labels.
      let reason = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", reasons[mode]!)).firstMatch
      XCTAssertTrue(reason.waitForExistence(timeout: 5), app.debugDescription)
      let counters = try await threadForkCounters()
      XCTAssertEqual((counters["posts"] as? [Any])?.count, 0)
      if mode != "non-git" { XCTAssertEqual((counters["optionReads"] as? [Any])?.count, 0) }
      shot("thread-fork-unavailable-" + mode)
    }
  }
  @MainActor func testThreadForkReceiptIdentityAndMissingReceipt() async throws {
    for mode in ["mismatched-receipt", "missing-receipt"] {
      if mode == "missing-receipt" { app.terminate(); app.launchArguments = ["-ui-testing"]; app.launchChineseTest() }
      let fixture = try await threadForkFixture(mode)
      connectLive(fixture, supportsCreation: false)
      openFirstForkMenu(); tapForkEnvironment("same-directory")
      XCTAssertTrue(app.buttons["查询原回执"].waitForExistence(timeout: 15))
      app.buttons["查询原回执"].tap()
      XCTAssertTrue(app.staticTexts["SECOND_COMPLETED_REPLY"].exists)
      XCTAssertFalse(app.staticTexts["FORK_RESULT_same-directory"].exists)
      try await assertSingleFork("same-directory", fixture: fixture)
    }
  }
  @MainActor func testThreadForkRejectedOutcome() async throws {
    let fixture = try await threadForkFixture("rejected")
    connectLive(fixture, supportsCreation: false)
    composer.tap(); composer.typeText("rejected-fork-keeps-draft")
    app.buttons["composer-dismiss-keyboard"].tap()
    openFirstForkMenu(); tapForkEnvironment("worktree")
    XCTAssertTrue(app.staticTexts["分支未创建"].waitForExistence(timeout: 15))
    XCTAssertTrue(app.staticTexts["Fixture desktop rejected fork"].exists)
    XCTAssertEqual(composer.value as? String, "rejected-fork-keeps-draft")
    try await assertSingleFork("worktree", fixture: fixture)
  }

  private struct RealThreadForkFixture: Decodable {
    let endpoint: String
    let key: String
    let hostId: String
    let threadId: String
    let title: String
    let turnId: String
    let firstMarker: String
    let laterMarker: String
    let sameMarker: String
    let worktreeMarker: String
  }
  @MainActor func testOriginalDesktopThreadFork() async throws {
    let evidenceURL = URL(string: "http://127.0.0.1:8798")!
    guard let (configData, response) = try? await URLSession.shared.data(from: evidenceURL) else {
      throw XCTSkip("Start the dedicated real desktop fork evidence server on 8798")
    }
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let config = try JSONDecoder().decode(RealThreadForkFixture.self, from: configData)
    let fixture = LiveFixture(verifyUnread: nil, endpoint: config.endpoint, token: config.key,
      hostId: config.hostId, threadId: config.threadId, threadTitle: config.title,
      nonce: config.sameMarker, secondEndpoint: config.endpoint, secondToken: config.key,
      secondHostId: config.hostId, delayedEndpoint: config.endpoint)
    connectLive(fixture, supportsCreation: false)
    func hostJSON(_ path: String, _ query: [String: String]) async throws -> [String: Any] {
      let url = try XCTUnwrap(URL(string: config.endpoint)).appendingPathComponent(path)
        .appending(queryItems: (["hostId": config.hostId].merging(query) { _, rhs in rhs }).map { URLQueryItem(name: $0.key, value: $0.value) })
      var request = URLRequest(url: url)
      request.setValue("Bearer " + config.key, forHTTPHeaderField: "Authorization")
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    for (environment, marker) in [("same-directory", config.sameMarker), ("worktree", config.worktreeMarker)] {
      openDesktopTestConversation(fixture)
      var sourceMessages: [[String: Any]] = []
      var historyQuery = ["threadId": config.threadId]
      var cursors: Set<String> = []
      repeat {
        let page = try await hostJSON("v1/history", historyQuery)
        sourceMessages = (page["messages"] as? [[String: Any]] ?? []) + sourceMessages
        if let cursor = page["nextCursor"] as? String {
          XCTAssertTrue(cursors.insert(cursor).inserted)
          historyQuery["cursor"] = cursor
        } else { historyQuery.removeValue(forKey: "cursor") }
      } while historyQuery["cursor"] != nil
      let answers = sourceMessages.filter { $0["role"] as? String == "assistant" && $0["runId"] as? String == config.turnId && $0["replyPhase"] as? String != "commentary" }
      let reply = try XCTUnwrap(answers.last(where: { $0["replyPhase"] as? String == "final_answer" }) ?? answers.last)
      let replyId = try XCTUnwrap(reply["id"] as? String)
      let action = forkActionGroup(replyId)
      for _ in 0..<20 where !action.exists {
        app.swipeDown()
        let earlier = app.buttons["history-local-more"].exists ? app.buttons["history-local-more"] : app.buttons["history-more"]
        if earlier.exists && earlier.isHittable { earlier.tap() }
      }
      XCTAssertTrue(action.waitForExistence(timeout: 30), "The original first-turn reply must be reachable, including paginated history")
      openFirstForkMenu(replyId); tapForkEnvironment(environment, replyId: replyId)
      // The source retains its immutable receipt even when success has already navigated.
      try await Task.sleep(for: .seconds(2))
      openDesktopTestConversation(fixture)
      let receiptElement = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@ AND value == %@", "fork-receipt-", environment)).firstMatch
      XCTAssertTrue(receiptElement.waitForExistence(timeout: 30))
      let operationId = String(receiptElement.identifier.dropFirst("fork-receipt-".count))
      var operation: [String: Any] = [:]
      for _ in 0..<90 {
        operation = try await hostJSON("v1/threads/fork", ["threadId": config.threadId, "id": operationId])
        if operation["phase"] as? String == "confirmed" { break }
        XCTAssertNotEqual(operation["phase"] as? String, "rejected")
        try await Task.sleep(for: .seconds(1))
      }
      XCTAssertEqual(operation["phase"] as? String, "confirmed")
      XCTAssertEqual(operation["hostId"] as? String, config.hostId)
      XCTAssertEqual(operation["threadId"] as? String, config.threadId)
      XCTAssertEqual(operation["turnId"] as? String, config.turnId)
      XCTAssertEqual(operation["environment"] as? String, environment)
      let childId = try XCTUnwrap(operation["forkedThreadId"] as? String)
      let inherited = try await hostJSON("v1/history", ["threadId": childId])
      let inheritedText = (inherited["messages"] as? [[String: Any]] ?? []).compactMap { $0["text"] as? String }.joined(separator: "\n")
      XCTAssertTrue(inheritedText.contains(config.firstMarker))
      XCTAssertFalse(inheritedText.contains(config.laterMarker))
      let open = receiptElement.buttons["打开分支"]
      XCTAssertTrue(open.waitForExistence(timeout: 30)); open.tap()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", config.firstMarker)).firstMatch.waitForExistence(timeout: 30))
      let prompt = "What is the FIRST marker you remember? Reply with it and \(marker), do not use tools."
      focusLiveComposer(); composer.typeText(prompt); sendLiveComposer()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", config.firstMarker, marker)).firstMatch.waitForExistence(timeout: 180))
      let completed = try await hostJSON("v1/history", ["threadId": childId])
      let messages = completed["messages"] as? [[String: Any]] ?? []
      XCTAssertEqual(messages.filter { $0["role"] as? String == "user" && $0["text"] as? String == prompt }.count, 1)
      XCTAssertTrue(messages.contains { $0["role"] as? String == "assistant" && ($0["text"] as? String ?? "").contains(config.firstMarker) && ($0["text"] as? String ?? "").contains(marker) })
      // Independent desktop/source-prefix and exact-input witnesses are owned by the evidence server.
      let (evidence, evidenceResponse) = try await URLSession.shared.data(from: evidenceURL.appendingPathComponent("evidence"))
      XCTAssertEqual((evidenceResponse as? HTTPURLResponse)?.statusCode, 200)
      let attachment = XCTAttachment(data: evidence, uniformTypeIdentifier: "public.json")
      attachment.name = "thread-fork-" + environment + "-independent-witness"; attachment.lifetime = .keepAlways; add(attachment)
      let receiptAttachment = XCTAttachment(string: String(data: try JSONSerialization.data(withJSONObject: operation), encoding: .utf8)!)
      receiptAttachment.name = "thread-fork-" + environment + "-exact-receipt"; receiptAttachment.lifetime = .keepAlways; add(receiptAttachment)
      shot("real-desktop-thread-fork-" + environment)
    }
  }
  @MainActor func testUM04DiscoveryExplicitPrivateConnectionRealDEV() async throws {
    let base = URL(string: "http://127.0.0.1:8817/um04/")!
    func read(_ path: String) async throws -> [String: Any] {
      let (data, response) = try await URLSession.shared.data(from: base.appendingPathComponent(path))
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    let config = try await read("fixture")
    let host = try XCTUnwrap(config["hostId"] as? String), thread = try XCTUnwrap(config["threadId"] as? String)
    let title = try XCTUnwrap(config["title"] as? String), marker = try XCTUnwrap(config["marker"] as? String)
    let endpoint = try XCTUnwrap(config["endpoint"] as? String)
    app.buttons["client-switch"].tap(); app.buttons["add-client"].tap()
    let row = app.buttons["codex-discovered-" + host]
    XCTAssertTrue(row.waitForExistence(timeout: 60), "The actual isolated Host must appear through native Bonjour, without QR/manual input")
    XCTAssertTrue(row.label.contains("点选连接")); XCTAssertFalse(app.textFields["codex-qr-description"].exists)
    let before = try await read("stats"); XCTAssertEqual(before["claimPosts"] as? Int, 0)
    shot("um04-real-bonjour-before-click")
    row.tap()
    let keyField = app.secureTextFields["codex-host-key"]
    XCTAssertTrue(keyField.waitForExistence(timeout: 10))
    XCTAssertFalse(app.buttons["codex-authorize-connect"].isEnabled)
    let selected = try await read("stats"); XCTAssertEqual(selected["claimPosts"] as? Int, 0)
    keyField.tap(); keyField.typeText(try XCTUnwrap(config["hostKey"] as? String))
    app.buttons["codex-authorize-connect"].tap()
    XCTAssertTrue(row.waitForNonExistence(timeout: 90), app.debugDescription)
    XCTAssertFalse(app.staticTexts["client-form-error"].exists)
    let fixture = LiveFixture(verifyUnread: nil, endpoint: endpoint, token: "unused-no-host-token-provided-to-app",
      hostId: host, threadId: thread, threadTitle: title, nonce: marker,
      secondEndpoint: endpoint, secondToken: "unused", secondHostId: host, delayedEndpoint: endpoint)
    openDesktopTestConversation(fixture)
    focusLiveComposer();composer.typeText("Reply only " + marker + ". Do not use tools.");sendLiveComposer()
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 180),app.debugDescription)
    shot("um04-private-credential-original-owner-reply")
    let after = try await read("stats");XCTAssertEqual(after["claimPosts"] as? Int, 1)
    var restart = URLRequest(url: base.appendingPathComponent("restart"));restart.httpMethod="POST";restart.httpBody=Data("{}".utf8)
    let (_, restartResponse)=try await URLSession.shared.data(for:restart);XCTAssertEqual((restartResponse as? HTTPURLResponse)?.statusCode,200)
    app.terminate();app.launchArguments=["-ui-testing","-restore-testing"];app.launchChineseTest()
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 45))
    app.buttons["client-switch"].tap();app.buttons["add-client"].tap()
    XCTAssertTrue(row.waitForExistence(timeout: 45));row.tap()
    XCTAssertTrue(row.waitForNonExistence(timeout: 90),app.debugDescription)
    XCTAssertFalse(app.staticTexts["client-form-error"].exists)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch.waitForExistence(timeout: 30))
    let reopened = try await read("stats");XCTAssertEqual(reopened["claimPosts"] as? Int, 1)
    shot("um04-private-connection-reopened-without-claim")
  }

}

// Existing scenarios use Chinese text selectors. Keep their locale explicit.
extension XCUIApplication {
  func launchChineseTest() {
    launchArguments += ["-app-language", "zh-Hans", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
    launch()
  }
}

extension CodexManageUITests {
  @MainActor func testSharedRuntimeLifecycleDEV() async throws {
    let base = URL(string: "http://127.0.0.1:8831")!
    var originalDesktop = false
    func fixture(_ kind: String) async throws -> LiveFixture {
      let (bytes, response) = try await URLSession.shared.data(from: base.appendingPathComponent(kind))
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try JSONDecoder().decode(LiveFixture.self, from: bytes)
    }
    func resolvedFixture(_ kind: String) async throws -> LiveFixture {
      for _ in 0..<180 {
        let value = try await fixture(kind)
        if !value.threadId.isEmpty { return value }
        try await Task.sleep(for: .seconds(1))
      }
      XCTFail("The original desktop creation has no confirmed thread; preserve its receipt without replay")
      throw URLError(.timedOut)
    }
    func witness(_ kind: String, _ phase: String) async throws {
      let url = base.appendingPathComponent("witness").appending(queryItems: [URLQueryItem(name: "kind", value: kind), URLQueryItem(name: "phase", value: phase)])
      var failure = "No independent result"
      for _ in 0..<60 {
        let (bytes, response) = try await URLSession.shared.data(from: url)
        if (response as? HTTPURLResponse)?.statusCode == 200 { return }
        failure = String(decoding: bytes, as: UTF8.self)
        try await Task.sleep(for: .milliseconds(500))
      }
      XCTFail(failure)
    }
    func waitSharedAnswer(_ marker: String) {
      let answer = app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch
      XCTAssertTrue(answer.waitForExistence(timeout: 210), app.debugDescription)
      XCTAssertTrue(app.buttons["stop-turn"].waitForNonExistence(timeout: 45))
      XCTAssertFalse(app.alerts.firstMatch.exists)
      let connection = app.buttons["client-switch"].value as? String ?? ""
      if originalDesktop {
        XCTAssertTrue(connection.contains("Codex · 实时连接"), connection)
        XCTAssertFalse(connection.contains("共享运行时"))
        XCTAssertFalse(connection.contains("Agent 托管"))
      } else {
        XCTAssertTrue(connection.contains("共享运行时 · 实时连接"))
      }
    }
    func menu() {
      if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
      let button = app.buttons["chat-menu"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: button)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 40), .completed)
      button.tap(); XCTAssertTrue(app.buttons["chat-archive"].waitForExistence(timeout: 10))
    }
    func settings() {
      let button = app.buttons["generation-settings"]
      XCTAssertTrue(button.waitForExistence(timeout: 30)); button.tap()
      XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 15))
      app.descendants(matching: .any)["model-picker"].firstMatch.tap()
      let model = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "GPT-6.1-Sol")).firstMatch
      XCTAssertTrue(model.waitForExistence(timeout: 10)); model.tap()
      app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
      app.buttons["极高"].tap()
      let readOnly = app.buttons["permission-read-only"]
      if !readOnly.isHittable { app.swipeUp() }
      readOnly.tap(); app.buttons["generation-done"].tap()
    }
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    var control = try await fixture("tools-and-controls")
    originalDesktop = control.sharedOriginalDesktop == true
    connectLive(control)
    if originalDesktop && control.threadId.isEmpty { chooseDesktopProject(nil) }
    else { openDesktopTestConversation(control) }
    if control.sharedApprovalDone != true {
    if control.sharedExistingOutcome != "inProgress" {
    settings()
    focusLiveComposer()
    typeComposerText("本机隔离共享服务审批验收：使用 exec_command 执行 printf SHARED_APPROVAL_\(control.nonce)，显式设置 sandbox_permissions=require_escalated，justification=验证手机审批。命令不读写文件，必须等待批准后执行；然后实际调用 Codex app 内置 get_usage_limits，确认有真实返回。最终只回复命令输出。")
    sendLiveComposer()
    }
    if originalDesktop { control = try await resolvedFixture("tools-and-controls") }
    let approve = app.buttons["approval-accept"]
    XCTAssertTrue(approve.waitForExistence(timeout: 180), app.debugDescription)
    shot("shared-runtime-real-approval")
    if !approve.isHittable { app.swipeUp() }; approve.tap()
    waitSharedAnswer("SHARED_APPROVAL_" + control.nonce)
    try await witness("tools-and-controls", "approval")
    }
    for kind in ["background-archive", "background-delete", "completed-archive", "completed-delete", "stopped-archive", "stopped-delete"] {
      var current = try await fixture(kind)
      if current.sharedLifecycleDone == true { continue }
      if originalDesktop && current.threadId.isEmpty { chooseDesktopProject(nil) }
      else { openDesktopTestConversation(current) }
      let stopped = kind.hasPrefix("stopped")
      if current.sharedExistingOutcome == nil {
      settings(); focusLiveComposer()
      let destination = kind.hasPrefix("background") ? control.threadId : (current.sharedNavigationThreadId ?? current.threadId)
      let ending = stopped ? "随后以 STREAM_\(current.nonce)：开头，连续输出约6000字中文的软件工程论述，直到我点击停止；不要提前结束。" : "完成后仅回复 DONE_\(current.nonce)。"
      typeComposerText("本机隔离共享服务验收：先调用 Codex app 内置 navigate_to_codex_page 打开准确对话 \(destination)，只切换视图，不发送任何消息。然后执行一个 shell 命令输出 TOOL_\(current.nonce)。\(ending)不要修改文件，不要调用其他工具。")
      sendLiveComposer()
      if originalDesktop { current = try await resolvedFixture(kind) }
      if stopped {
        var toolRunning = false
        for _ in 0..<120 {
          let url = base.appendingPathComponent("witness").appending(queryItems: [URLQueryItem(name: "kind", value: kind), URLQueryItem(name: "phase", value: "running")])
          let (_, response) = try await URLSession.shared.data(from: url)
          if (response as? HTTPURLResponse)?.statusCode == 200 { toolRunning = true; break }
          try await Task.sleep(for: .milliseconds(1500))
        }
        XCTAssertTrue(toolRunning, "The actual shell output and completed desktop navigation must precede stop")
        let stop = app.buttons["stop-turn"]; XCTAssertTrue(stop.exists); stop.tap()
        XCTAssertTrue(stop.waitForNonExistence(timeout: 45))
      } else { waitSharedAnswer("DONE_" + current.nonce) }
      }
      try await witness(kind, "before")
      menu()
      let expectedStatus = "未归档 · 任务状态：" + (stopped ? "已停止" : "已完成")
      XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", expectedStatus)).firstMatch.exists, app.debugDescription)
      XCTAssertTrue(app.buttons["chat-archive"].isEnabled); XCTAssertTrue(app.buttons["chat-delete"].isEnabled)
      shot("shared-runtime-" + kind + "-status")
      if kind.hasSuffix("archive") {
        app.buttons["chat-archive"].tap()
        menu(); app.buttons["archived-chats"].tap()
        let row = app.buttons["archived-chat-" + current.threadId]
        XCTAssertTrue(row.waitForExistence(timeout: 30)); shot("shared-runtime-" + kind + "-archived")
        row.tap()
        try await witness(kind, "archive")
      } else {
        app.buttons["chat-delete"].tap()
        let confirm = app.buttons["chat-delete-confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        try await witness(kind, "delete")
      }
    }
  }

  @MainActor func testOriginalDesktopRestoredFollowupDEV() async throws {
    let base = URL(string: "http://127.0.0.1:8819")!
    let (data, response) = try await URLSession.shared.data(from: base)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    func witness(_ phase: String) async throws {
      let url = base.appendingPathComponent("witness").appending(queryItems: [URLQueryItem(name: "phase", value: phase)])
      var failure = "No independent result"
      for _ in 0..<60 {
        let (bytes, response) = try await URLSession.shared.data(from: url)
        if (response as? HTTPURLResponse)?.statusCode == 200 { return }
        failure = String(decoding: bytes, as: UTF8.self)
        try await Task.sleep(for: .milliseconds(500))
      }
      XCTFail(failure)
    }
    if fixture.sharedLifecycleDone == true { try await witness("delete"); return }
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    let live = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    if XCTWaiter.wait(for: [live], timeout: 20) != .completed { connectLive(fixture, supportsCreation: false) }
    openDesktopTestConversation(fixture)
    try await witness("restore")
    if fixture.sharedFollowupDone != true {
      if fixture.sharedExistingOutcome != "inProgress" {
        try configureOriginalSharedAcceptanceGeneration()
        focusLiveComposer()
        let text = "恢复后原桌面跟进验收：仅原样回复 RESTORED_CHAT_ACTIONS_\(fixture.nonce)，不要使用工具。"
        let draft = composer.value as? String ?? ""
        if draft != text {
          guard draft.isEmpty || draft == (composer.placeholderValue ?? "") else {
            XCTFail("Preserve the existing draft; do not append or replace an unrecognized input")
            return
          }
          typeComposerText(text)
        }
        guard composer.value as? String == text else { XCTFail("Exact restored follow-up required"); return }
        sendLiveComposer()
      }
      waitForDesktopAnswer("RESTORED_CHAT_ACTIONS_" + fixture.nonce)
    }
    try await witness("restored-reply")
    let menu = app.buttons["chat-menu"]
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: menu)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let delete = app.buttons["chat-delete"]
    XCTAssertTrue(delete.waitForExistence(timeout: 10))
    delete.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let confirm = app.buttons["chat-delete-confirm"].firstMatch
    XCTAssertTrue(confirm.waitForExistence(timeout: 10)); shot("original-restored-chat-delete-confirmation")
    confirm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    try await witness("delete")
  }

  @MainActor func testOriginalDesktopHostRestartReceiptDEV() async throws {
    let base = URL(string: "http://127.0.0.1:8795")!
    let (data, response) = try await URLSession.shared.data(from: base)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    let marker = "IOS_HOST_RECOVERY_" + fixture.nonce
    waitForDesktopAnswer(marker)
    let (bytes, restarted) = try await URLSession.shared.data(from: base.appendingPathComponent("restart-host"))
    XCTAssertEqual((restarted as? HTTPURLResponse)?.statusCode, 200, String(decoding: bytes, as: UTF8.self))
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 20))
    openDesktopTestConversation(fixture)
    waitForDesktopAnswer(marker)
    focusLiveComposer()
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let (proof, verified) = try await URLSession.shared.data(from: base.appendingPathComponent("witness-host"))
    XCTAssertEqual((verified as? HTTPURLResponse)?.statusCode, 200, String(decoding: proof, as: UTF8.self))
    shot("original-host-restart-existing-receipt-no-replay")
  }

  @MainActor func testOriginalDesktopArchivedTaskStatusesDEV() async throws {
    let base = URL(string: "http://127.0.0.1:8831")!
    func fixture(_ kind: String) async throws -> LiveFixture {
      let (bytes, response) = try await URLSession.shared.data(from: base.appendingPathComponent(kind))
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      return try JSONDecoder().decode(LiveFixture.self, from: bytes)
    }
    let control = try await fixture("tools-and-controls")
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    let live = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    if XCTWaiter.wait(for: [live], timeout: 20) != .completed { connectLive(control, supportsCreation: false) }
    openDesktopTestConversation(control)
    app.buttons["chat-menu"].tap()
    XCTAssertTrue(app.buttons["archived-chats"].waitForExistence(timeout: 10))
    app.buttons["archived-chats"].tap()
    for kind in ["background-archive", "completed-archive", "stopped-archive"] {
      let current = try await fixture(kind)
      let row = app.buttons["archived-chat-" + current.threadId]
      XCTAssertTrue(row.waitForExistence(timeout: 30))
      let expected = "已归档 · 任务状态：" + (kind.hasPrefix("stopped") ? "已停止" : "已完成")
      XCTAssertTrue(row.label.contains(expected), row.label)
      row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let menu = app.buttons["chat-menu"]
      XCTAssertTrue(menu.waitForExistence(timeout: 15))
      menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", expected)).firstMatch.waitForExistence(timeout: 15))
      if kind != "stopped-archive" { app.buttons["archived-chats"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
    }
    shot("original-desktop-archived-task-statuses")
  }

  @MainActor func testOriginalDesktopChatActions() async throws {
    guard let (data, _) = try? await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8819")!) else {
      throw XCTSkip("Run chat-actions-desktop-ios-acceptance.mjs with a dedicated simulator")
    }
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    app.terminate(); app.launchArguments = []; app.launchChineseTest()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    connectLive(fixture)
    if fixture.threadId.isEmpty {
    // A newly connected catalog may select an existing chat with an async
    // question panel. Dismiss that read-only panel before opening our draft.
    for _ in 0..<3 {
      if app.buttons["choose-projectless"].exists { break }
      if app.buttons["close-async-questions"].exists { app.buttons["close-async-questions"].firstMatch.tap() }
      if app.buttons["new-chat"].isHittable {
        app.buttons["new-chat"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      }
      if app.buttons["choose-projectless"].waitForExistence(timeout: 4) { break }
    }
    XCTAssertTrue(app.buttons["choose-projectless"].waitForExistence(timeout: 10))
    app.buttons["choose-projectless"].tap()
    let settings = app.buttons["generation-settings"]
    XCTAssertTrue(settings.waitForExistence(timeout: 20)); settings.tap()
    XCTAssertTrue(app.buttons["generation-done"].waitForExistence(timeout: 15))
    app.descendants(matching: .any)["model-picker"].firstMatch.tap()
    let model = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "GPT-6.1-Sol")).firstMatch
    XCTAssertTrue(model.waitForExistence(timeout: 10)); model.tap()
    app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
    XCTAssertTrue(app.buttons["极高"].waitForExistence(timeout: 10)); app.buttons["极高"].tap()
    app.buttons["generation-done"].tap()
    focusLiveComposer()
    let delay = fixture.chatActionsActivePin == true ? " In that same command, sleep 75 seconds after printing the marker." : ""
    composer.typeText("CHAT_ACTIONS_\(fixture.nonce): Run one shell command that prints TOOL_CHAT_ACTIONS_\(fixture.nonce)." + delay + " Then reply exactly DONE_CHAT_ACTIONS_\(fixture.nonce). Do not edit files or use other tools.")
    sendLiveComposer()
    if fixture.chatActionsActivePin == true {
      XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 45))
      func activeMenu() {
        let button = app.buttons["chat-menu"]
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: button)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
        button.tap(); XCTAssertTrue(app.buttons["chat-pin"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["chat-pin"].isEnabled)
        XCTAssertTrue(app.buttons["chat-archive"].isEnabled); XCTAssertTrue(app.buttons["chat-delete"].isEnabled)
        XCTAssertFalse(app.staticTexts["当前电脑连接暂不支持归档、恢复或删除已加载的聊天。"].exists)
      }
      for (label, phase) in [("置顶", "active-pin"), ("取消置顶", "active-unpin")] {
        activeMenu()
        XCTAssertEqual(app.buttons["chat-pin"].label, label)
        shot("chat-actions-" + phase)
        app.buttons["chat-pin"].tap()
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == nil OR value != %@", "正在更新…"), object: app.buttons["chat-menu"])
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
        let (data, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8819/witness?phase=" + phase)!)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, String(decoding: data, as: UTF8.self))
      }
    }
    waitForDesktopAnswer("DONE_CHAT_ACTIONS_" + fixture.nonce)
    } else {
      openDesktopTestConversation(fixture)
    }
    func witness(_ phase: String) async throws -> String {
      let (bytes, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8819/witness?phase=" + phase)!)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, String(decoding: bytes, as: UTF8.self))
      let object = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
      return try XCTUnwrap(object["threadId"] as? String)
    }
    let threadId = try await witness("model")
    func menu() {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: app.buttons["chat-menu"])
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 40), .completed)
      app.buttons["chat-menu"].tap()
      XCTAssertTrue(app.buttons["chat-search"].waitForExistence(timeout: 10))
    }
    menu()
    let pinEnabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: app.buttons["chat-pin"])
    XCTAssertEqual(XCTWaiter.wait(for: [pinEnabled], timeout: 30), .completed)
    app.buttons["chat-pin"].tap()
    let pinReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      self.app.buttons["chat-menu"].exists && !self.app.alerts.firstMatch.exists
    }, object: nil)
    XCTAssertEqual(XCTWaiter.wait(for: [pinReady], timeout: 15), .completed)
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["pinned-thread-" + threadId].waitForExistence(timeout: 30))
    shot("chat-actions-pinned")
    app.buttons["pinned-thread-" + threadId].tap()
    _ = try await witness("pin")
    menu(); XCTAssertEqual(app.buttons["chat-pin"].label, "取消置顶"); XCTAssertTrue(app.buttons["chat-pin"].isEnabled); app.buttons["chat-pin"].tap()
    menu()
    let unpinned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "置顶"), object: app.buttons["chat-pin"])
    XCTAssertEqual(XCTWaiter.wait(for: [unpinned], timeout: 20), .completed)
    app.buttons["chat-search"].tap()
    let search = app.textFields["chat-search-query"]
    if !search.waitForExistence(timeout: 5), app.buttons["chat-search"].exists && app.buttons["chat-search"].isHittable {
      // Reopening a read-only sheet is safe if a native menu refresh consumed its tap.
      app.buttons["chat-search"].tap()
    }
    XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("TOOL_CHAT_ACTIONS_" + fixture.nonce)
    let results = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat-search-result-'"))
    XCTAssertTrue(results.firstMatch.waitForExistence(timeout: 30))
    let tool = results.matching(NSPredicate(format: "label CONTAINS %@", "工具输出")).firstMatch
    XCTAssertTrue(tool.waitForExistence(timeout: 35)); tool.tap()
    let located = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'chat-search-located-'"))
    XCTAssertTrue(located.firstMatch.waitForExistence(timeout: 15)); shot("chat-actions-search-located-tool")
    _ = try await witness("unpin")
    if fixture.chatActionsPartial == true {
      menu()
      XCTAssertTrue(app.buttons["chat-archive"].isEnabled)
      XCTAssertTrue(app.buttons["chat-delete"].isEnabled)
      app.buttons["chat-archive"].tap()
      XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 20))
      XCTAssertTrue(app.alerts.staticTexts["电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。"].exists)
      shot("chat-actions-loaded-lifecycle-rejected"); app.alerts.buttons["好"].tap()
      menu()
      for _ in 0..<2 {
        if app.staticTexts["删除此对话？"].exists { break }
        if app.buttons["chat-delete"].exists { app.buttons["chat-delete"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        if app.staticTexts["删除此对话？"].waitForExistence(timeout: 3) { break }
      }
      XCTAssertTrue(app.staticTexts["删除此对话？"].waitForExistence(timeout: 10))
      let confirm = app.buttons["chat-delete-confirm"].firstMatch
      XCTAssertTrue(confirm.exists); confirm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 20))
      XCTAssertTrue(app.alerts.staticTexts["电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。"].exists)
      shot("chat-actions-loaded-delete-rejected"); app.alerts.buttons["好"].tap()
      _ = try await witness("lifecycle-blocked")
      return
    }
    menu(); app.buttons["chat-archive"].tap()
    menu(); app.buttons["archived-chats"].tap()
    let archive = app.buttons["archived-chat-" + threadId]
    XCTAssertTrue(archive.waitForExistence(timeout: 30)); shot("chat-actions-archive-entry")
    archive.tap()
    XCTAssertTrue(app.buttons["chat-menu"].waitForExistence(timeout: 10))
    _ = try await witness("archive")
    menu(); XCTAssertEqual(app.buttons["chat-archive"].label, "恢复对话"); app.buttons["chat-archive"].tap()
    menu()
    let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "归档"), object: app.buttons["chat-archive"])
    XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 20), .completed)
    app.buttons["chat-delete"].tap()
    XCTAssertTrue(app.buttons["chat-delete-confirm"].waitForExistence(timeout: 10))
    app.buttons["取消"].tap()
    _ = try await witness("restore")
    if fixture.sharedOriginalDesktop == true {
      focusLiveComposer()
      typeComposerText("恢复后原桌面跟进验收：仅原样回复 RESTORED_CHAT_ACTIONS_\(fixture.nonce)，不要使用工具。")
      sendLiveComposer()
      waitForDesktopAnswer("RESTORED_CHAT_ACTIONS_" + fixture.nonce)
      _ = try await witness("restored-reply")
    }
    menu(); app.buttons["chat-delete"].tap(); shot("chat-actions-delete-confirmation")
    app.buttons["chat-delete-confirm"].tap()
    let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: located.firstMatch)
    XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 30), .completed)
    _ = try await witness("delete")
    app.buttons["open-sidebar"].tap()
    XCTAssertFalse(app.buttons["recent-thread-" + threadId].exists)
    shot("chat-actions-deleted")
  }
}

extension CodexManageUITests {
  @MainActor func testOriginalQueueActionsPreserveDraftAndRequireExplicitConsentFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    func actions() async throws -> [[String: Any]] {
      let (bytes, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/queue-controls/stats"))
      return (try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any]))["actions"] as? [[String: Any]] ?? []
    }
    func count(_ expected: Int) async throws {
      for _ in 0..<100 { if try await actions().count == expected { return }; try await Task.sleep(for: .milliseconds(50)) }
      XCTFail("Expected exact original queue action count \(expected)")
    }
    let execute = app.buttons["execute-queued-native-original"], cancel = app.buttons["cancel-queued-native-original"]
    XCTAssertTrue(execute.waitForExistence(timeout: 15)); XCTAssertTrue(execute.isEnabled, app.debugDescription)
    XCTAssertTrue(app.staticTexts["queued-text-file-only"].exists)
    focusLiveComposer(); composer.typeText("independent queue draft")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    shot("compact-queue-idle-original-order")
    execute.tap(); XCTAssertTrue(allowTestAISharingIfNeeded()); try await count(0)
    XCTAssertTrue(execute.isEnabled); execute.tap(); try await count(1)
    XCTAssertFalse(execute.isEnabled); XCTAssertFalse(cancel.isEnabled)
    let other = app.buttons["cancel-queued-old-compatible"]
    XCTAssertTrue(other.isEnabled); other.tap(); try await count(2)
    XCTAssertTrue(other.waitForNonExistence(timeout: 10))
    focusLiveComposer()
    composer.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.98)).tap()
    composer.typeText(" edited while original pending")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    XCTAssertEqual(composer.value as? String, "independent queue draft edited while original pending")
    shot("compact-queue-independent-pending-and-draft")
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/queue-controls/release"))
    XCTAssertTrue(execute.waitForNonExistence(timeout: 15))
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/queue-controls/running"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    let append = app.buttons["execute-queued-file-only"]
    XCTAssertTrue(append.waitForExistence(timeout: 15))
    let progress = app.staticTexts.matching(identifier: "runtime-progress").firstMatch
    XCTAssertTrue(progress.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(progress.label.contains("正在执行"), app.debugDescription)
    shot("compact-queue-running-attachment-append")
    XCTAssertTrue(append.isEnabled); append.tap(); try await count(3)
    XCTAssertTrue(append.waitForNonExistence(timeout: 15))
    XCTAssertEqual(composer.value as? String, "independent queue draft edited while original pending")
  }

  @MainActor func testOriginalClientQueueMetadataFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    let first = app.buttons["cancel-queued-native-original"]
    XCTAssertTrue(first.waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(app.buttons["cancel-queued-old-compatible"].exists)
    for id in ["legacy-host", "legacy-disposition", "removed-native"] { XCTAssertFalse(app.buttons["cancel-queued-" + id].exists) }
    focusLiveComposer(); composer.typeText("independent current unsent draft")
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    XCTAssertTrue(app.staticTexts["accepted original client sync failure"].waitForExistence(timeout: 15), app.debugDescription)
    shot("queue-native-present-only-and-accepted-sync-error")
    let dismiss = app.buttons["dismiss-receipt-failed-sync"]
    XCTAssertTrue(dismiss.waitForExistence(timeout: 10)); dismiss.tap()
    XCTAssertTrue(app.staticTexts["failed original client sync failure"].exists)
    XCTAssertEqual(composer.value as? String, "independent current unsent draft")
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/queue-clear"))
    app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
    XCTAssertTrue(app.staticTexts["QUEUE_PHASE_CLEAR"].waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertTrue(app.staticTexts["accepted original client sync failure"].waitForNonExistence(timeout: 10))
    XCTAssertFalse(app.staticTexts["failed original client sync failure"].exists)
    XCTAssertTrue(first.exists); XCTAssertTrue(app.buttons["cancel-queued-old-compatible"].exists)
    XCTAssertEqual(composer.value as? String, "independent current unsent draft")
    shot("queue-source-clear-keeps-original-draft")
  }

  @MainActor func testEveryOriginalBatchRecordRemainsReachableFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    openWorkspaceManagement(); app.segmentedControls["management-kind"].buttons["对话"].tap()
    func stats() async throws -> [String: Any] {
      let (bytes, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/conflict-stats"))
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    var oldest: String?
    let search = app.searchFields.firstMatch
    XCTAssertTrue(search.waitForExistence(timeout: 10))
    for index in 0..<11 {
      let suffix = String(format: "%02d", index)
      let clear = search.buttons["清除文本"].firstMatch
      if clear.exists { clear.tap() }
      let blank = search.value as? String ?? ""
      XCTAssertTrue(blank.isEmpty || blank == (search.placeholderValue ?? ""), app.debugDescription)
      search.tap()
      let queryText = "BATCH_RECORD_" + suffix + "_" + fixture.nonce
      search.typeText(queryText + "\n")
      print("BATCH_RECORD_SEARCH", index, search.value ?? "nil")
      XCTAssertEqual(search.value as? String, queryText, app.debugDescription)
      let row = app.buttons["management-item-batch-record-" + suffix]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isEnabled == true"), object: row)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 15), .completed, app.debugDescription)
      row.tap(); app.buttons["management-submit"].tap(); app.alerts.buttons["归档对话"].firstMatch.tap()
      var originals: [[String: Any]] = []
      for _ in 0..<100 {
        originals = (try await stats())["batches"] as? [[String: Any]] ?? []
        if originals.count == index + 1 { break }; try await Task.sleep(for: .milliseconds(50))
      }
      XCTAssertEqual(originals.count, index + 1)
      if index == 0 { oldest = originals.first?["clientOperationId"] as? String }
      else { XCTAssertTrue(row.waitForNonExistence(timeout: 15)) }
    }
    let original = try XCTUnwrap(oldest)
    let query = app.buttons["management-check-" + original]
    let list = app.collectionViews.firstMatch
    for _ in 0..<18 {
      if query.exists && query.isHittable { break }
      if list.exists { list.swipeUp() } else { app.swipeUp() }
    }
    XCTAssertTrue(query.exists && query.isHittable, "The oldest unresolved original must remain reachable after ten later terminal records")
    shot("oldest-original-after-eleven-batches-before-query")
    query.tap()
    var observed: [String] = []
    for _ in 0..<100 {
      observed = (try await stats())["batchQueries"] as? [String] ?? []
      if observed.filter({ $0 == original }).count >= 2 { break }; try await Task.sleep(for: .milliseconds(50))
    }
    XCTAssertTrue(observed.count >= 2 && observed.allSatisfy { $0 == original })
    let final = try await stats()
    XCTAssertEqual((final["batches"] as? [[String: Any]])?.count, 11)
    shot("oldest-original-query-keeps-eleven-immutable-batches")
  }

  @MainActor func testChatManagementBatchConflictFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    func stats() async throws -> [String: Any] {
      let (bytes, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/conflict-stats"))
      return try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
    }
    app.buttons["chat-menu"].tap()
    let pin = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "chat-pin", "置顶")).firstMatch
    XCTAssertTrue(pin.waitForExistence(timeout: 10)); pin.tap()
    var posted = false
    for _ in 0..<100 {
      if ((try await stats())["single"] as? [[String: Any]])?.count == 1 { posted = true; break }
      try await Task.sleep(for: .milliseconds(50))
    }
    XCTAssertTrue(posted)
    openWorkspaceManagement()
    app.segmentedControls["management-kind"].buttons["对话"].tap()
    let first = app.buttons["management-item-" + fixture.threadId]
    XCTAssertTrue(first.waitForExistence(timeout: 10))
    XCTAssertFalse(first.isEnabled, "Original single operation still unknown: zero new batch must be selectable")
    XCTAssertFalse(app.buttons["management-submit"].isEnabled)
    let beforeBatch = try await stats()
    XCTAssertEqual((beforeBatch["batches"] as? [[String: Any]])?.count, 0)
    shot("single-original-pending-blocks-batch-selection")
    let other = app.buttons["management-item-search-second-thread"]
    XCTAssertTrue(other.isEnabled); other.tap(); app.buttons["management-submit"].tap()
    app.alerts.buttons["归档对话"].firstMatch.tap()
    var batchId: String?
    for _ in 0..<100 {
      batchId = ((try await stats())["batches"] as? [[String: Any]])?.first?["clientOperationId"] as? String
      if batchId != nil { break }; try await Task.sleep(for: .milliseconds(50))
    }
    let original = try XCTUnwrap(batchId)
    let originalQuery = app.buttons["management-check-" + original]
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isEnabled == true"), object: originalQuery)
    XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 15), .completed)
    let cleared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == false AND label CONTAINS %@", "0"), object: app.buttons["management-submit"])
    XCTAssertEqual(XCTWaiter.wait(for: [cleared], timeout: 10), .completed)
    XCTAssertTrue(app.staticTexts["management-selected-count"].label.contains("0"))
    XCTAssertFalse(other.isEnabled); shot("batch-original-pending-retains-query-and-blocks-selection")
    app.navigationBars.buttons.element(boundBy: 0).tap(); app.buttons["完成"].tap()
    app.buttons["open-sidebar"].tap()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    let second = app.buttons["recent-thread-search-second-thread"]
    XCTAssertTrue(second.waitForExistence(timeout: 10)); second.tap()
    XCTAssertTrue(app.staticTexts["SECOND_TARGET_ONLY"].waitForExistence(timeout: 15))
    app.buttons["chat-menu"].tap()
    for (id, label) in [("chat-pin", "置顶"), ("chat-archive", "归档"), ("chat-delete", "删除")] {
      let item = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", id, label)).firstMatch
      XCTAssertTrue(item.waitForExistence(timeout: 10)); XCTAssertFalse(item.isEnabled, "Unknown batch must block conflicting single write")
    }
    shot("batch-original-pending-blocks-single-management")
    let final = try await stats()
    XCTAssertEqual((final["single"] as? [[String: Any]])?.count, 1)
    XCTAssertEqual((final["batches"] as? [[String: Any]])?.count, 1)
    XCTAssertEqual(((final["batches"] as? [[String: Any]])?.first?["clientOperationId"] as? String), original)
  }

  @MainActor func testClientRunningCountsFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    for (state, expected) in [("running", "1"), ("waiting-for-approval", "1"), ("idle", "0")] {
      _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/menu-state").appending(queryItems: [URLQueryItem(name: "state", value: state)]))
      app.buttons["chat-menu"].tap(); app.buttons["刷新历史"].tap()
      let menu = app.buttons["chat-menu"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == nil OR value != %@", "正在更新…"), object: menu)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
      app.buttons["client-switch"].tap()
      let count = app.staticTexts["client-running-" + fixture.hostId]
      XCTAssertTrue(count.waitForExistence(timeout: 10))
      let updated = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "进行中 " + expected), object: count)
      XCTAssertEqual(XCTWaiter.wait(for: [updated], timeout: 15), .completed)
      shot("client-running-count-" + state)
      app.buttons["完成"].tap()
    }
  }

  @MainActor func testChatMenuRunningPinFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    func action(_ id: String, labels: [String]) -> XCUIElement {
      let predicate = NSPredicate(format: "identifier == %@ OR label IN %@", id, labels)
      return app.buttons.matching(predicate).firstMatch
    }
    func menu() {
      let button = app.buttons["chat-menu"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: button)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
      button.tap(); XCTAssertTrue(action("chat-search", labels: ["在聊天中查找"]).waitForExistence(timeout: 10))
    }
    func archiveLabel(_ label: String) {
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", label), object: action("chat-archive", labels: ["归档", "恢复对话"]))
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    }
    func deleteConfirmation() {
      // Opening a confirmation has no remote side effect. A menu being rebuilt
      // after a prior receipt can consume the first native menu selection.
      for _ in 0..<2 {
        if app.buttons["chat-delete-confirm"].exists || app.staticTexts["删除此对话？"].exists { break }
        if action("chat-delete", labels: ["删除"]).exists {
          action("chat-delete", labels: ["删除"]).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        if app.staticTexts["删除此对话？"].waitForExistence(timeout: 3) { break }
      }
      XCTAssertTrue(app.staticTexts["删除此对话？"].waitForExistence(timeout: 10))
    }
    func confirmDelete() -> XCUIElement {
      let exact = app.buttons["chat-delete-confirm"]
      return exact.exists ? exact.firstMatch : app.buttons["删除"].firstMatch
    }
    let states = [("running", "执行中"), ("waiting-for-approval", "等待审批"), ("idle", "空闲"), ("unloaded", "空闲"), ("failed", "执行失败"), ("completed", "已完成"), ("interrupted", "已停止"), ("unknown", "状态未确认")]
    for (state, status) in states {
      _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/menu-state").appending(queryItems: [URLQueryItem(name: "state", value: state)]))
      menu(); app.buttons["刷新历史"].tap()
      let active = ["running", "waiting-for-approval"].contains(state)
      let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == %@", NSNumber(value: active)), object: app.buttons["stop-turn"])
      XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 20), .completed)
      menu()
      shot("chat-menu-" + state)
      XCTAssertTrue(action("chat-pin", labels: ["置顶", "取消置顶"]).isEnabled)
      XCTAssertTrue(action("chat-archive", labels: ["归档", "恢复对话"]).isEnabled)
      XCTAssertTrue(action("chat-delete", labels: ["删除"]).isEnabled)
      XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "未归档 · 任务状态：" + status)).firstMatch.exists)
      XCTAssertFalse(app.staticTexts[fixture.threadTitle!].exists, "The menu must not repeat the current conversation title")
      if active {
        action("chat-pin", labels: ["置顶", "取消置顶"]).tap(); menu()
        let pinned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "取消置顶"), object: action("chat-pin", labels: ["置顶", "取消置顶"]))
        XCTAssertEqual(XCTWaiter.wait(for: [pinned], timeout: 20), .completed)
        action("chat-pin", labels: ["置顶", "取消置顶"]).tap(); menu()
        XCTAssertEqual(action("chat-pin", labels: ["置顶", "取消置顶"]).label, "置顶")
      }
      deleteConfirmation()
      XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "未归档 · 任务状态：" + status)).firstMatch.exists)
      if app.buttons["取消"].exists { app.buttons["取消"].firstMatch.tap() }
      else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.25)).tap() }
      XCTAssertTrue(app.staticTexts["删除此对话？"].waitForNonExistence(timeout: 10))
      menu(); action("chat-archive", labels: ["归档", "恢复对话"]).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      menu(); archiveLabel("恢复对话")
      XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "已归档 · 任务状态：" + status)).firstMatch.exists, "Archiving must not manufacture a stopped task")
      let row = app.buttons["archived-chat-" + fixture.threadId]
      // Only presentation navigation may be repeated: a catalog-triggered
      // native Menu rebuild can consume the first selection. Never repeat
      // archive/restore/model commands when their outcome is unknown.
      for _ in 0..<2 {
        if row.exists { break }
        let entry = action("archived-chats", labels: ["已归档对话"])
        if entry.exists { entry.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        if row.waitForExistence(timeout: 3) { break }
      }
      XCTAssertTrue(row.waitForExistence(timeout: 15))
      XCTAssertTrue(row.label.contains("已归档")); shot("archived-chat-status-" + state)
      row.tap()
      menu(); archiveLabel("恢复对话"); action("chat-archive", labels: ["归档", "恢复对话"]).tap()
      menu(); archiveLabel("归档")
      XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "未归档 · 任务状态：" + status)).firstMatch.exists)
      app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.65)).tap()
    }
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/reject-lifecycle"))
    menu(); action("chat-archive", labels: ["归档", "恢复对话"]).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 15))
    XCTAssertTrue(app.alerts.staticTexts["电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。"].exists)
    XCTAssertFalse(app.alerts.staticTexts["操作结果待确认，请在对话选项中查询原记录。"].exists)
    shot("chat-lifecycle-native-rejection"); app.alerts.buttons["好"].tap()
    menu(); archiveLabel("归档"); XCTAssertFalse(app.buttons["chat-action-query"].exists)
    deleteConfirmation()
    confirmDelete().tap()
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == nil OR value != %@", "正在更新…"), object: app.buttons["chat-menu"])
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    app.buttons["open-sidebar"].tap()
    XCTAssertTrue(app.buttons["close-sidebar"].waitForExistence(timeout: 15))
    XCTAssertTrue(app.buttons["recent-thread-" + fixture.threadId].waitForNonExistence(timeout: 15))
    shot("chat-lifecycle-deleted")
  }

  @MainActor func testChatRefreshDuringCatalogFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    func control(_ path: String) async throws -> [String: Any] {
      let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent(path))
      return (try JSONSerialization.jsonObject(with: data)) as! [String: Any]
    }
    func refreshFromMenu() {
      app.buttons["chat-menu"].tap()
      XCTAssertTrue(app.buttons["刷新历史"].waitForExistence(timeout: 10))
      app.buttons["刷新历史"].tap()
    }
    _ = try await control("fixture/hold-catalog-all")
    refreshFromMenu()
    var held = false
    for _ in 0..<100 {
      held = (try await control("fixture/catalog-state"))["held"] as? Bool == true
      if held { break }; try await Task.sleep(nanoseconds: 50_000_000)
    }
    XCTAssertTrue(held)
    let occupied = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "正在更新…"), object: app.buttons["chat-menu"])
    XCTAssertEqual(XCTWaiter.wait(for: [occupied], timeout: 10), .completed)
    _ = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/menu-state").appending(queryItems: [URLQueryItem(name: "state", value: "running")]))
    refreshFromMenu()
    _ = try await control("fixture/release-catalog")
    var followedUp = false
    for _ in 0..<100 {
      followedUp = ((try await control("fixture/catalog-state"))["historyReadsAfterRelease"] as? Int ?? 0) >= 2
      if followedUp { break }; try await Task.sleep(nanoseconds: 50_000_000)
    }
    XCTAssertTrue(followedUp, "The old read completing must not erase the newer explicit refresh")
    XCTAssertTrue(app.buttons["stop-turn"].waitForExistence(timeout: 15))
    shot("manual-refresh-after-occupied-catalog")
  }
  @MainActor func testChatCatalogManagementFenceFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    func control(_ path: String) async throws -> [String: Any] {
      let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent(path))
      return (try JSONSerialization.jsonObject(with: data)) as! [String: Any]
    }
    func menu() {
      app.buttons["chat-menu"].tap()
      XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "chat-search", "在聊天中查找")).firstMatch.waitForExistence(timeout: 10))
    }
    let archive = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label IN %@", "chat-archive", ["归档", "恢复对话"])).firstMatch
    _ = try await control("fixture/hold-catalog")
    menu(); app.buttons["刷新历史"].tap()
    var held = false
    for _ in 0..<100 {
      held = (try await control("fixture/catalog-state"))["held"] as? Bool == true
      if held { break }; try await Task.sleep(nanoseconds: 50_000_000)
    }
    XCTAssertTrue(held)
    menu(); archive.tap()
    var fresh = false
    for _ in 0..<100 {
      let state = try await control("fixture/catalog-state")
      fresh = state["held"] as? Bool == true && (state["freshCatalogReadsWhileHeld"] as? Int ?? 0) > 0
      if fresh { break }; try await Task.sleep(nanoseconds: 50_000_000)
    }
    XCTAssertTrue(fresh, "Confirmed management must retire the occupied catalog slot")
    menu()
    let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@ AND isEnabled == true", "恢复对话"), object: archive)
    XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 10), .completed)
    shot("confirmed-archive-fresh-catalog-before-old-release")
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.65)).tap()
    _ = try await control("fixture/release-catalog")
    try await Task.sleep(nanoseconds: 200_000_000)
    menu(); XCTAssertEqual(archive.label, "恢复对话")
    shot("old-catalog-cannot-restore-unarchived-row")
  }
  @MainActor func testChatSearchPagedHistoryFixture() async throws {
    let (data, _) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8820")!)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false)
    openDesktopTestConversation(fixture)
    func openSearch() {
      app.buttons["chat-menu"].tap()
      // Native Menu snapshots can preserve the localized label while omitting the identifier.
      let named = app.buttons["chat-search"]
      let entry = named.exists ? named : app.buttons["在聊天中查找"]
      XCTAssertTrue(entry.waitForExistence(timeout: 5)); XCTAssertTrue(entry.isHittable)
      entry.tap()
      XCTAssertTrue(app.textFields["chat-search-query"].waitForExistence(timeout: 10))
    }
    openSearch()
    let search = app.textFields["chat-search-query"]
    func closeSearch() {
      for _ in 0..<4 where app.textFields["chat-search-query"].exists {
        let explicit = app.buttons["chat-search-close"]
        if explicit.exists && explicit.isHittable { explicit.tap() }
        else if app.buttons["关闭"].exists && app.buttons["关闭"].isHittable { app.buttons["关闭"].tap() }
        else if app.buttons["取消"].exists && app.buttons["取消"].isHittable { app.buttons["取消"].tap() }
        else if app.buttons["完成"].exists && app.buttons["完成"].isHittable { app.buttons["完成"].tap() }
        else { XCTFail("The native search close action must remain accessible"); return }
      }
      XCTAssertFalse(app.textFields["chat-search-query"].exists)
    }
    search.tap(); search.typeText("PREVIOUS_PAGE_TOOL_MARKER_" + fixture.nonce)
    let tool = app.buttons["chat-search-result-older-tool"]
    XCTAssertTrue(tool.waitForExistence(timeout: 20)); tool.tap()
    let locatedTool = app.descendants(matching: .any)["chat-search-located-older-tool"].firstMatch
    XCTAssertTrue(locatedTool.waitForExistence(timeout: 15)); XCTAssertTrue(locatedTool.isHittable)
    shot("search-page-before-visible-window-tool-output")
    openSearch(); search.tap(); search.typeText("附件资料-" + fixture.nonce)
    let attachment = app.buttons["chat-search-result-older-attachment"]
    XCTAssertTrue(attachment.waitForExistence(timeout: 15)); attachment.tap()
    XCTAssertTrue(app.descendants(matching: .any)["chat-search-located-older-attachment"].firstMatch.waitForExistence(timeout: 15))
    shot("search-original-attachment-name")
    openSearch(); search.tap(); search.typeText("COMPACTION_SEARCH_" + fixture.nonce)
    let compaction = app.buttons["chat-search-result-older-compaction"]
    XCTAssertTrue(compaction.waitForExistence(timeout: 15)); compaction.tap()
    XCTAssertTrue(app.descendants(matching: .any)["chat-search-located-older-compaction"].firstMatch.waitForExistence(timeout: 15))
    shot("search-original-compaction-message")
    openSearch(); search.tap(); search.typeText("NO_SUCH_CONTENT_" + fixture.nonce)
    XCTAssertTrue(app.staticTexts["没有匹配结果"].waitForExistence(timeout: 15))
    app.buttons["清除搜索"].tap(); search.tap(); search.typeText("   ")
    XCTAssertEqual(search.value as? String, "   ")
    XCTAssertFalse(app.staticTexts["没有匹配结果"].exists, "Whitespace is an empty normalized query")
    closeSearch()
    _ = try await URLSession.shared.data(from: URL(string: fixture.endpoint + "/fixture/fail")!)
    openSearch(); search.tap(); search.typeText("Latest readable message 99")
    XCTAssertTrue(app.staticTexts["无法读取完整历史。可搜索已缓存内容；连接恢复后重试。"].waitForExistence(timeout: 15))
    XCTAssertTrue(app.buttons["chat-search-result-latest-99"].waitForExistence(timeout: 10)); shot("search-read-failure-cached-result")
    closeSearch()
    _ = try await URLSession.shared.data(from: URL(string: fixture.endpoint + "/fixture/delay")!)
    openSearch(); search.tap(); search.typeText("PREVIOUS_PAGE_TOOL_MARKER_" + fixture.nonce)
    closeSearch()
    app.buttons["open-sidebar"].tap()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    let second = app.buttons["recent-thread-search-second-thread"]
    XCTAssertTrue(second.waitForExistence(timeout: 10)); second.tap()
    // Clearing sidebar search can leave its keyboard dismissal consuming the first tap.
    let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["close-sidebar"])
    if XCTWaiter.wait(for: [selected], timeout: 2) != .completed { second.tap() }
    XCTAssertTrue(app.staticTexts["SECOND_TARGET_ONLY"].waitForExistence(timeout: 15))
    let lateJump = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true"), object: app.descendants(matching: .any)["chat-search-located-older-tool"].firstMatch)
    lateJump.isInverted = true
    XCTAssertEqual(XCTWaiter.wait(for: [lateJump], timeout: 10), .completed)
    XCTAssertTrue(app.staticTexts["SECOND_TARGET_ONLY"].exists)
    XCTAssertFalse(app.descendants(matching: .any)["chat-search-located-older-tool"].firstMatch.exists)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    shot("search-target-switch-cancels-late-read")
  }
}


extension CodexManageUITests {
  @MainActor func testPhysicalAgentHubFeatureAttachmentCurrentVersion() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the ordinary App on the paired physical iPhone")
    #else
    struct Fixture: Decodable {
      let hostId: String; let threadId: String; let titleQuery: String
      let linkLabel: String; let fileName: String
      let initialMarker: String; let editedMarker: String; let replacedMarker: String
      let controlURL: String; let controlToken: String
      let originalClientId: String?
      let controlMode: String?; let controlRunId: String?; let controlInvitationId: String?
    }
    let fixture = try JSONDecoder().decode(Fixture.self, from: Data(XCTUnwrap(ProcessInfo.processInfo.environment["ATTACHMENT_PHYSICAL_FIXTURE_JSON"]).utf8))
    dismissLiveQuestionPresentation(wait: 2)
    func tapVisible(_ control: XCUIElement) {
      XCTAssertTrue(control.exists && control.isEnabled, app.debugDescription)
      let frame = control.frame
      XCTAssertTrue(app.windows.firstMatch.frame.contains(frame) && frame.width > 1 && frame.height > 1,
        "Require a visible control before tapping: " + app.debugDescription)
      let origin = app.frame.origin
      app.coordinate(withNormalizedOffset: .zero)
        .withOffset(CGVector(dx: frame.midX - origin.x, dy: frame.midY - origin.y)).tap()
    }
    XCTAssertTrue(app.buttons["client-switch"].waitForExistence(timeout: 30))
    shot("physical-attachment-before-client-picker", fullScreen: true)
    tapVisible(app.buttons["client-switch"])
    shot("physical-attachment-client-picker", fullScreen: true)
    XCTAssertTrue(app.buttons["client-" + fixture.hostId].waitForExistence(timeout: 15), app.debugDescription)
    let clients = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-'"))
    let selected = try XCTUnwrap(clients.allElementsBoundByIndex.first(where: { $0.isSelected })?.identifier,
      "Capture the existing selected client before changing it: " + app.debugDescription)
    let original = fixture.originalClientId ?? selected
    XCTAssertTrue(app.buttons[original].exists, "Retain the original selection across a diagnostic retry")
    func selectClient(_ identifier: String) {
      let row = app.buttons[identifier]
      XCTAssertTrue(row.waitForExistence(timeout: 20), app.debugDescription)
      tapVisible(row)
      if app.buttons["完成"].exists && app.buttons["完成"].isHittable { tapVisible(app.buttons["完成"]) }
      XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    }
    selectClient("client-" + fixture.hostId)
    func openThread() {
      if !app.buttons["sidebar-search-toggle"].exists {
        tapVisible(app.buttons["open-sidebar"])
        XCTAssertTrue(app.buttons["sidebar-search-toggle"].waitForExistence(timeout: 10), app.debugDescription)
      }
      if !app.textFields["sidebar-search"].exists { tapVisible(app.buttons["sidebar-search-toggle"]) }
      let search = app.textFields["sidebar-search"]
      XCTAssertTrue(search.waitForExistence(timeout: 10)); tapVisible(search)
      let old = search.value as? String ?? ""
      search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + fixture.titleQuery + "\n")
      let row = app.buttons["recent-thread-" + fixture.threadId]
      XCTAssertTrue(row.waitForExistence(timeout: 45), app.debugDescription)
      for _ in 0..<10 where !row.isHittable { app.swipeUp() }
      XCTAssertTrue(row.isHittable); tapVisible(row)
    }
    let donePredicate = NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@", ["关闭", "Close", "Done", "完成"])
    func checkPreview(_ marker: String) {
      XCTAssertTrue(app.buttons.matching(donePredicate).firstMatch.waitForExistence(timeout: 30), app.debugDescription)
      // QuickLook exposes Markdown as an outer TextView value with nested
      // TextView labels. Keep version checks inside the presented preview so
      // matching text in the conversation behind it cannot satisfy the check.
      let preview = app.otherElements["QLPreviewControllerView"]
      let versionText = preview.descendants(matching: .textView).matching(
        NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", marker, marker)
      ).firstMatch
      XCTAssertTrue(versionText.waitForExistence(timeout: 20), app.debugDescription)
      XCTAssertFalse(app.alerts["无法打开链接"].exists)
    }
    func closePreview() { tapVisible(app.buttons.matching(donePredicate).firstMatch) }
    func tapLink() {
      let link = app.links.matching(NSPredicate(format: "label == %@", fixture.linkLabel)).firstMatch
      let fallback = app.staticTexts.matching(NSPredicate(format: "label == %@", fixture.linkLabel)).firstMatch
      let timeline = app.scrollViews["message-list"]
      for _ in 0..<20 where !(link.exists && link.isHittable) && !(fallback.exists && fallback.isHittable) {
        let element = link.exists ? link : fallback
        if element.exists && element.frame.minY < timeline.frame.minY + 100 { timeline.swipeDown() }
        else { timeline.swipeUp() }
      }
      if link.exists && link.isHittable { tapVisible(link) }
      else { XCTAssertTrue(fallback.isHittable, app.debugDescription); tapVisible(fallback) }
    }
    var pairedPhoneClaim: String?
    var pairedDriverClaim: String?
    var pairedClaimBytes: Data?
    var pairedPhase = 1
    func advance(_ stage: Int) async throws {
      if fixture.controlMode == "paired-files-v2" {
        struct Acknowledgement: Decodable {
          let version: Int; let runId: String; let invitationId: String
          let phoneClaimId: String; let driverClaimId: String
          let stage: Int; let verified: Bool; let error: String?
        }
        func require(_ value: Bool, _ message: String) throws {
          if !value { throw NSError(domain: "AttachmentPairedControl", code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]) }
        }
        let deadline = ProcessInfo.processInfo.systemUptime + 300
        let run = try XCTUnwrap(UUID(uuidString: fixture.controlRunId ?? "")).uuidString
        let invitation = try XCTUnwrap(UUID(uuidString: fixture.controlInvitationId ?? "")).uuidString
        try require(stage == pairedPhase + 1 && [2, 3].contains(stage), "Only the next stage of this claimed run may advance")
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
          .appendingPathComponent("AttachmentCurrentControl", isDirectory: true)
        let directory = root.appendingPathComponent(run, isDirectory: true)
        let requestURL = directory.appendingPathComponent("request.json")
        let acknowledgementURL = directory.appendingPathComponent("acknowledgement.json")
        if stage == 2 {
          try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
          // POSIX mkdir is exclusive even for an existing empty run directory.
          guard directory.path.withCString({ Darwin.mkdir($0, 0o700) }) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno),
              userInfo: [NSLocalizedDescriptionKey: "Refuse a reused phone run, including old requests or acknowledgements"])
          }
          let phone = UUID().uuidString
          let claim: [String: Any] = ["version": 1, "runId": run, "invitationId": invitation,
            "phoneClaimId": phone, "hostId": fixture.hostId, "threadId": fixture.threadId]
          let bytes = try JSONSerialization.data(withJSONObject: claim, options: [.sortedKeys])
          try bytes.write(to: directory.appendingPathComponent("phone-claim.json"), options: .withoutOverwriting)
          pairedPhoneClaim = phone; pairedClaimBytes = bytes
        } else {
          let previous = try Data(contentsOf: directory.appendingPathComponent("phone-claim.json"))
          try require(previous == pairedClaimBytes, "The original phone claim must remain unchanged")
          try require(pairedDriverClaim != nil, "Stage3 requires this run's verified driver claim")
          let old = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: requestURL)) as? [String: Any])
          try require(old["stage"] as? Int == 2 && old["runId"] as? String == run
            && old["invitationId"] as? String == invitation && old["phoneClaimId"] as? String == pairedPhoneClaim,
            "Stage3 must continue the exact original stage2 request")
        }
        let phone = try XCTUnwrap(pairedPhoneClaim)
        let request: [String: Any] = ["version": 1, "runId": run, "invitationId": invitation,
          "phoneClaimId": phone, "driverClaimId": pairedDriverClaim as Any? ?? NSNull(),
          "stage": stage, "hostId": fixture.hostId, "threadId": fixture.threadId]
        let bytes = try JSONSerialization.data(withJSONObject: request, options: [.sortedKeys])
        try bytes.write(to: requestURL, options: stage == 2 ? .withoutOverwriting : .atomic)
        print("PHYSICAL_ATTACHMENT_PAIRED_STAGE_REQUESTED", run, stage)
        while ProcessInfo.processInfo.systemUptime < deadline {
          if FileManager.default.fileExists(atPath: acknowledgementURL.path) {
            let acknowledgement = try JSONDecoder().decode(Acknowledgement.self,
              from: Data(contentsOf: acknowledgementURL))
            try require(acknowledgement.version == 1 && acknowledgement.runId == run
              && acknowledgement.invitationId == invitation && acknowledgement.phoneClaimId == phone,
              "Reject an acknowledgement from an old request or another run")
            try require(UUID(uuidString: acknowledgement.driverClaimId) != nil,
              "A driver must exclusively claim this run")
            if let driver = pairedDriverClaim {
              try require(acknowledgement.driverClaimId == driver, "The original driver claim must remain unchanged")
            }
            if acknowledgement.stage == stage {
              try require(acknowledgement.verified, acknowledgement.error ?? "Require exact Host bytes before continuing")
              try require(ProcessInfo.processInfo.systemUptime < deadline,
                "Paired acknowledgement exceeded the original 300s budget")
              pairedDriverClaim = acknowledgement.driverClaimId; pairedPhase = stage
              return
            }
            try require(stage == 3 && acknowledgement.stage == 2 && pairedPhase == 2,
              "An older acknowledgement cannot complete the current stage")
          }
          try await Task.sleep(for: .milliseconds(500))
        }
        throw NSError(domain: "AttachmentPairedControl", code: 2,
          userInfo: [NSLocalizedDescriptionKey: "Paired acknowledgement exceeded the original 300s budget; query original operations"])
      }
      if fixture.controlMode != nil {
        throw NSError(domain: "AttachmentPairedControl", code: 3,
          userInfo: [NSLocalizedDescriptionKey: "Unsupported control mode; do not fall back to legacy stage execution"])
      }
      var url = URLComponents(string: fixture.controlURL + "/advance")!
      url.queryItems = [URLQueryItem(name: "stage", value: String(stage))]
      var request = URLRequest(url: url.url!); request.timeoutInterval = 300
      request.setValue("Bearer " + fixture.controlToken, forHTTPHeaderField: "Authorization")
      let (_, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200,
        "Require the original remote owner to confirm the exact file update")
    }
    openThread(); tapLink(); checkPreview(fixture.initialMarker)
    shot("physical-attachment-original-link-preview")
    try await advance(2)
    checkPreview(fixture.initialMarker)
    shot("physical-attachment-link-preview-survives-edit")
    closePreview(); tapLink(); checkPreview(fixture.editedMarker)
    shot("physical-attachment-link-opens-edited-version"); closePreview()
    let preview = app.buttons["preview-" + fixture.fileName].firstMatch
    for _ in 0..<12 where !preview.isHittable { app.scrollViews["message-list"].swipeUp() }
    XCTAssertTrue(preview.waitForExistence(timeout: 30)); tapVisible(preview); checkPreview(fixture.editedMarker)
    try await advance(3)
    checkPreview(fixture.editedMarker)
    shot("physical-attachment-card-preview-survives-replacement")
    closePreview(); tapVisible(preview); checkPreview(fixture.replacedMarker)
    shot("physical-attachment-cached-card-opens-current-version"); closePreview()
    app.terminate(); app.launchArguments = []; app.activate()
    dismissLiveQuestionPresentation(wait: 2)
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30))
    openThread(); tapLink(); checkPreview(fixture.replacedMarker)
    shot("physical-attachment-relaunch-opens-current-version"); closePreview()
    tapVisible(app.buttons["client-switch"]); selectClient(original)
    print("PHYSICAL_ATTACHMENT_CURRENT_VERSION_PASSED", fixture.hostId, fixture.threadId)
    #endif
  }

  @MainActor func testPhysicalAgentHubFeaturePreflight() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the paired physical iPhone and the ordinary installation")
    #endif
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 30))
    XCTAssertFalse(app.buttons["onboarding-add"].exists, "The existing phone data must be retained")
    XCTAssertTrue(app.buttons["client-switch"].exists)
    shot("physical-integrated-candidate-existing-data-retained")
  }
}

extension CodexManageUITests {
  private func dismissPhysicalPasswordPrompt() {
    let later = app.buttons["以后"]
    if later.waitForExistence(timeout: 3), app.buttons["保存"].exists { later.tap() }
  }

  @MainActor private func physicalAction(_ id: String) -> XCUIElement {
    let labels = ["chat-pin": ["置顶", "取消置顶"], "chat-search": ["在聊天中查找"], "chat-archive": ["归档", "恢复对话"], "chat-delete": ["删除"], "chat-delete-confirm": ["删除"], "archived-chats": ["已归档对话"], "chat-action-query": ["查询聊天操作记录"]][id] ?? [id]
    let matches = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label IN %@", id, labels))
    let visible = matches.allElementsBoundByIndex.filter { $0.isHittable && $0.frame.width > 1 && $0.frame.height > 1 }
    if let button = visible.min(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }) { return button }
    return matches.firstMatch
  }
  private struct PhysicalFeatures: Decodable {
    struct Project: Decodable { let id: String; let name: String }
    struct Empty: Decodable { let id: String; let title: String }
    let originalHostId: String
    let connection: LiveFixture
    let projects: [Project]
    let emptyThreads: [Empty]
    let prompt: String
    let label: String
    let phonePreferences: [String: Bool]?
    let completionMarker: String?
  }
  @MainActor private func physicalConfig() throws -> PhysicalFeatures {
    #if targetEnvironment(simulator)
    throw XCTSkip("A paired physical iPhone is required")
    #else
    return try JSONDecoder().decode(PhysicalFeatures.self, from: Data(XCTUnwrap(ProcessInfo.processInfo.environment["PHYSICAL_FIXTURE_JSON"]).utf8))
    #endif
  }
  private var physicalPhase: String { ProcessInfo.processInfo.environment["PHYSICAL_PHASE"] ?? "" }
  @MainActor private func physicalMenu() {
    if physicalAction("archived-chats").exists && physicalAction("archived-chats").isHittable { return }
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: app.buttons["chat-menu"])
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 45), .completed)
    app.buttons["chat-menu"].tap()
    XCTAssertTrue(physicalAction("archived-chats").waitForExistence(timeout: 10))
  }
  @MainActor private func physicalSidebar() {
    let search = app.buttons["sidebar-search-toggle"].firstMatch
    if name.contains("testOriginalDesktopReadOnlySuggestionsDEV") {
      for attempt in 0..<3 {
        if search.exists { return }
        let open = app.buttons["open-sidebar"].firstMatch
        guard tapReadOnlySuggestionControl(open, step: "open-sidebar-\(attempt)") else {
          XCTFail("Cannot safely tap the visible sidebar control"); return
        }
        if search.waitForExistence(timeout: 3) { return }
      }
      captureSuggestionUIFailure("open-sidebar")
      XCTFail("The sidebar did not open after bounded center taps")
      return
    }
    for _ in 0..<3 {
      if search.exists && search.isHittable { return }
      let open = app.buttons["open-sidebar"].firstMatch
      if open.exists && open.isHittable { open.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      if search.waitForExistence(timeout: 3) && search.isHittable { return }
    }
    XCTAssertTrue(search.isHittable)
  }
  @MainActor private func physicalOpenThread(_ id: String) {
    physicalSidebar()
    if !app.textFields["sidebar-search"].exists { app.buttons["sidebar-search-toggle"].tap() }
    let search = app.textFields["sidebar-search"]
    XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap()
    let prefix = (try? physicalConfig())?.label ?? id
    search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (search.value as? String ?? "").count) + prefix + "\n")
    let row = app.buttons.matching(NSPredicate(format: "identifier IN %@", ["recent-thread-" + id, "pinned-thread-" + id])).firstMatch
    for _ in 0..<12 {
      if row.isHittable { break }
      app.swipeUp()
    }
    XCTAssertTrue(row.isHittable, "Exact owned thread must be reachable")
    row.tap()
  }
  @MainActor func testPhysicalAgentHubFeatureChatActions() throws {
    let fixture = try physicalConfig()
    if physicalPhase == "model" {
      XCTAssertTrue(fixture.connection.threadId.isEmpty, "No replay of a previously submitted model task")
      if !app.buttons["client-switch"].label.contains(fixture.label) { connectLive(fixture.connection) }
      app.buttons["new-chat"].tap()
      let project = app.buttons["choose-project-" + fixture.projects[0].id]
      for _ in 0..<14 { if project.isHittable { break }; app.swipeUp() }
      XCTAssertTrue(project.isHittable); project.tap()
      app.buttons["generation-settings"].tap()
      app.descendants(matching: .any)["model-picker"].firstMatch.tap()
      let model = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "GPT-6.1-Sol")).firstMatch
      XCTAssertTrue(model.waitForExistence(timeout: 10)); model.tap()
      app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
      app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
      focusLiveComposer(); composer.typeText(fixture.prompt); sendLiveComposer()
      waitForDesktopAnswer("DONE_" + fixture.connection.nonce)
      shot("physical-unified-new-chat-real-model-completed")
      return
    }
    XCTAssertFalse(fixture.connection.threadId.isEmpty)
    physicalOpenThread(fixture.connection.threadId)
    if physicalPhase == "pin" {
      physicalMenu(); XCTAssertEqual(physicalAction("chat-pin").label, "置顶"); XCTAssertTrue(physicalAction("chat-pin").isEnabled); physicalAction("chat-pin").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      app.buttons["open-sidebar"].tap()
      let pinned = app.buttons["pinned-thread-" + fixture.connection.threadId]
      XCTAssertTrue(pinned.waitForExistence(timeout: 30)); shot("physical-pinned-exact-original-thread"); pinned.tap()
      physicalMenu()
      XCTAssertTrue(physicalAction("chat-pin").isEnabled)
      return
    }
    XCTAssertEqual(physicalPhase, "search")
    physicalMenu()
    for _ in 0..<8 {
      if physicalAction("chat-pin").isEnabled || !physicalAction("chat-action-query").exists { break }
      physicalAction("chat-action-query").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); physicalMenu()
    }
    let manageable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: physicalAction("chat-pin"))
    XCTAssertEqual(XCTWaiter.wait(for: [manageable], timeout: 30), .completed)
    if physicalAction("chat-pin").label == "取消置顶" { physicalAction("chat-pin").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); physicalMenu() }
    else { XCTAssertEqual(physicalAction("chat-pin").label, "置顶") }
    let unpinned = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "置顶"), object: physicalAction("chat-pin"))
    XCTAssertEqual(XCTWaiter.wait(for: [unpinned], timeout: 30), .completed)
    let search = app.textFields["chat-search-query"]
    for _ in 0..<3 {
      if search.exists { break }
      if physicalAction("chat-search").exists && physicalAction("chat-search").isHittable { physicalAction("chat-search").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      if search.waitForExistence(timeout: 5) { break }
    }
    XCTAssertTrue(search.waitForExistence(timeout: 15)); search.tap(); search.typeText("NO_MATCH_" + fixture.connection.nonce)
    XCTAssertTrue(app.staticTexts["没有匹配结果"].waitForExistence(timeout: 30)); shot("physical-search-no-results")
    search.tap(); search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: ("NO_MATCH_" + fixture.connection.nonce).count) + "TOOL_" + fixture.connection.nonce)
    let tool = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat-search-result-' AND label CONTAINS %@", "工具输出")).firstMatch
    XCTAssertTrue(tool.waitForExistence(timeout: 40)); shot("physical-search-real-tool-result"); tool.tap()
    let located = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'chat-search-located-'" )).firstMatch
    XCTAssertTrue(located.waitForExistence(timeout: 20)); shot("physical-search-original-message-highlight")
    physicalMenu(); XCTAssertTrue(physicalAction("chat-archive").isEnabled); XCTAssertTrue(physicalAction("chat-delete").isEnabled)
    shot("physical-loaded-writer-lifecycle-blocked")
  }
  @MainActor func testPhysicalAgentHubFeatureUnloadedLifecycle() throws {
    let fixture = try physicalConfig()
    let id = fixture.emptyThreads[physicalPhase == "bulk-restored" ? 1 : 0].id
    if physicalPhase == "restored" || physicalPhase == "bulk-restored" {
      physicalOpenThread(fixture.connection.threadId)
      physicalMenu()
      let row = app.buttons["archived-chat-" + id]
      for _ in 0..<3 {
        if row.exists { break }
        if physicalAction("archived-chats").exists && physicalAction("archived-chats").isHittable { physicalAction("archived-chats").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        if row.waitForExistence(timeout: 5) { break }
      }
      XCTAssertTrue(row.waitForExistence(timeout: 15)); shot("physical-archive-history-retained"); row.tap()
      physicalMenu(); XCTAssertEqual(physicalAction("chat-archive").label, "恢复对话")
      for _ in 0..<8 {
        if physicalAction("chat-archive").isEnabled || !physicalAction("chat-action-query").exists { break }
        physicalAction("chat-action-query").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        physicalMenu()
      }
      let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: physicalAction("chat-archive"))
      XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 30), .completed)
      physicalAction("chat-archive").tap()
      physicalMenu(); XCTAssertEqual(physicalAction("chat-archive").label, "归档"); shot("physical-restore-confirmed-with-history")
      return
    }
    physicalOpenThread(id)
    physicalMenu()
    if physicalPhase == "archive" {
      XCTAssertTrue(physicalAction("chat-archive").isEnabled); physicalAction("chat-archive").tap(); physicalMenu(); XCTAssertEqual(physicalAction("chat-archive").label, "恢复对话"); shot("physical-owned-unloaded-thread-archive-confirmed"); return
    }
    for _ in 0..<8 {
      if physicalAction("chat-delete").isEnabled || !physicalAction("chat-action-query").exists { break }
      physicalAction("chat-action-query").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); physicalMenu()
    }
    XCTAssertTrue(physicalAction("chat-delete").isEnabled)
    let title = app.staticTexts["删除此对话？"]
    for _ in 0..<3 {
      if title.exists { break }
      if physicalAction("chat-delete").exists && physicalAction("chat-delete").isHittable { physicalAction("chat-delete").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
      if title.waitForExistence(timeout: 4) { break }
    }
    XCTAssertTrue(title.waitForExistence(timeout: 10))
    XCTAssertTrue(physicalAction("chat-delete-confirm").exists); shot("physical-red-delete-second-confirmation")
    if physicalPhase == "delete-cancelled" {
      if app.buttons["取消"].exists { app.buttons["取消"].firstMatch.tap() }
      else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.25)).tap() }
      XCTAssertTrue(title.waitForNonExistence(timeout: 10)); shot("physical-delete-cancelled-without-changing-record"); return
    }
    XCTAssertEqual(physicalPhase, "deleted"); physicalAction("chat-delete-confirm").tap()
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: physicalAction("chat-delete-confirm"))
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
    physicalMenu(); shot("physical-only-owned-unloaded-thread-delete-confirmed")
  }
  @MainActor func testPhysicalAgentHubFeatureWorkspaceManagement() throws {
    let fixture = try physicalConfig()
    openWorkspaceManagement()
    let kind = physicalPhase == "bulk-archive" ? "对话" : "项目"
    let expected = physicalPhase == "bulk-archive" ? "归档" : "删除"
    for _ in 0..<3 {
      if app.buttons["management-submit"].label.contains(expected) { break }
      app.segmentedControls["management-kind"].buttons[kind].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@", expected), object: app.buttons["management-submit"])
      if XCTWaiter.wait(for: [ready], timeout: 3) == .completed { break }
    }
    XCTAssertTrue(app.buttons["management-submit"].label.contains(expected))
    let search = app.searchFields.firstMatch
    search.tap(); search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (search.value as? String ?? "").count) + (physicalPhase == "bulk-archive" ? fixture.label + "_UNLOADED_" : fixture.label + "_PROJECT_") + "\n")
    if physicalPhase == "bulk-archive" {
      let targets = Array(fixture.emptyThreads.dropFirst())
      XCTAssertGreaterThanOrEqual(targets.count, 2)
      for target in targets { let row = app.buttons["management-item-" + target.id]; XCTAssertTrue(row.waitForExistence(timeout: 20)); row.tap() }
      XCTAssertTrue(app.buttons["management-submit"].label.contains(String(targets.count)))
      app.buttons["management-submit"].tap(); app.alerts.buttons["归档对话"].firstMatch.tap()
      for target in targets { XCTAssertTrue(app.buttons["management-item-" + target.id].waitForNonExistence(timeout: 60)) }
      shot("physical-bulk-archive-original-desktop-confirmed"); return
    }
    XCTAssertEqual(physicalPhase, "projects-deleted")
    for project in fixture.projects { XCTAssertTrue(app.buttons["management-item-" + project.id].waitForExistence(timeout: 15)) }
    app.buttons["management-select-all"].tap()
    XCTAssertTrue(app.buttons["management-submit"].label.contains("2")); shot("physical-bulk-two-owned-projects")
    app.buttons["management-submit"].tap(); app.alerts.buttons["取消"].firstMatch.tap()
    for project in fixture.projects { XCTAssertTrue(app.buttons["management-item-" + project.id].exists) }
    app.buttons["management-submit"].tap(); app.alerts.buttons["删除项目"].firstMatch.tap()
    for project in fixture.projects { XCTAssertTrue(app.buttons["management-item-" + project.id].waitForNonExistence(timeout: 60)) }
    shot("physical-bulk-projects-deleted-files-and-chat-preserved")
  }
  @MainActor private func physicalNotifications() {
    physicalSidebar(); app.buttons["open-settings"].tap()
    XCTAssertTrue(app.buttons["通知"].firstMatch.waitForExistence(timeout: 10)); app.buttons["通知"].firstMatch.tap()
    XCTAssertTrue(app.switches["notifications-enabled"].waitForExistence(timeout: 10))
  }
  @MainActor private func physicalSwitch(_ id: String) -> XCUIElement {
    let toggle = app.switches[id]
    let rank: Int
    if id == "notifications-enabled" { rank = 0 }
    else if id == "notification-kind-approval" { rank = 10 }
    else if id == "notification-kind-completed" { rank = 20 }
    else if id == "notification-content-preview" { rank = 50 }
    else { rank = id == "notification-host-" + ((try? physicalConfig())?.originalHostId ?? "") ? 30 : 40 }
    let down = rank < physicalNotificationSection
    for _ in 0..<24 {
      if toggle.isHittable { physicalNotificationSection = rank; return toggle }
      if down { app.swipeDown() } else { app.swipeUp() }
    }
    XCTFail("Missing exact switch " + id); return toggle
  }
  @MainActor private func physicalSetSwitch(_ id: String, _ value: Bool) {
    let toggle = physicalSwitch(id)
    if (toggle.value as? String == "1") != value { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap() }
    XCTAssertEqual(toggle.value as? String, value ? "1" : "0")
  }
  @MainActor func testPhysicalAgentHubFeatureNotificationSettings() throws {
    let fixture = try physicalConfig()
    physicalNotifications()
    if physicalPhase == "restore-preferences" {
      guard let values = fixture.phonePreferences else { XCTFail("Missing original values"); return }
      for (id, value) in values.sorted(by: { $0.key < $1.key }) { physicalSetSwitch(id, value) }
      _ = physicalSwitch("notification-host-" + fixture.originalHostId)
      let originalStatus = app.staticTexts["notification-status-" + fixture.originalHostId]
      let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "后台推送已连接"), object: originalStatus)
      XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 60), .completed)
      shot("physical-original-notification-settings-restored"); return
    }
    let values: [(String, Bool)] = [
      ("notifications-enabled", true), ("notification-kind-approval", true), ("notification-kind-completed", true),
      ("notification-host-" + fixture.originalHostId, false),
      ("notification-host-" + fixture.connection.hostId, true), ("notification-content-preview", true)]
    var original: [String: Bool] = [:]
    for (id, _) in values { original[id] = physicalSwitch(id).value as? String == "1" }
    let saved = XCTAttachment(data: try JSONEncoder().encode(original), uniformTypeIdentifier: "public.json")
    saved.name = "physical-original-notification-preferences"; saved.lifetime = .keepAlways; add(saved)
    for (id, value) in values { physicalSetSwitch(id, value) }
    let status = app.staticTexts["notification-status-" + fixture.connection.hostId]
    _ = physicalSwitch("notification-host-" + fixture.connection.hostId)
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "后台推送已连接"), object: status)
    XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 60), .completed)
    shot("physical-real-apns-registered-and-preview-enabled")
  }
  @MainActor func testPhysicalAgentHubFeatureNotification() throws {
    let fixture = try physicalConfig()
    physicalOpenThread(fixture.connection.threadId)
    let draft = "PHONE_DRAFT_" + fixture.connection.nonce
    focusLiveComposer(); composer.typeText(draft)
    if app.buttons["composer-dismiss-keyboard"].exists { app.buttons["composer-dismiss-keyboard"].tap() }
    let screen = app.frame
    XCUIDevice.shared.press(.home)
    print("PHYSICAL_NOTIFICATION_READY")
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let start = springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: 1))
    let finish = springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: screen.height * 0.85))
    start.press(forDuration: 0.1, thenDragTo: finish)
    let notice = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "PHONE_NOTIFICATION_" + fixture.connection.nonce)).firstMatch
    XCTAssertTrue(notice.waitForExistence(timeout: 180)); shot("physical-actual-background-apns-notice", fullScreen: true)
    func coordinate(_ element: XCUIElement) -> XCUICoordinate {
      let f = element.frame
      let scale = f.midY > screen.height ? springboard.frame.width / screen.width : 1
      return springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX / scale, dy: f.midY / scale))
    }
    coordinate(notice).press(forDuration: 1.2)
    XCTAssertTrue(springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "PHONE_NOTIFICATION_" + fixture.connection.nonce)).firstMatch.waitForExistence(timeout: 15))
    XCTAssertTrue(springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "待审批")).firstMatch.exists)
    shot("physical-expanded-real-title-summary-computer-kind", fullScreen: true)
    let hierarchy = XCTAttachment(string: springboard.debugDescription); hierarchy.name = "physical-real-notice-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
    coordinate(notice).tap()
    if !app.wait(for: .runningForeground, timeout: 5), notice.exists { coordinate(notice).tap() }
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
    XCTAssertTrue(app.buttons["client-switch"].label.contains(fixture.label))
    XCTAssertEqual(composer.value as? String, draft)
    physicalResolveApproval()
  }
  @MainActor private func physicalResolveApproval() {
    let decline = app.buttons.matching(NSPredicate(format: "identifier IN %@", ["approval-decline", "approval-deny", "approval-cancel", "approval-abort"])).firstMatch
    XCTAssertTrue(decline.waitForExistence(timeout: 30))
    shot("physical-notice-opened-original-thread-and-approval")
    let response = XCTAttachment(string: decline.identifier); response.name = "physical-actual-approval-response"; response.lifetime = .keepAlways; add(response)
    decline.tap()
    XCTAssertTrue(decline.waitForNonExistence(timeout: 120))
    shot("physical-notification-approval-resolved")
  }
  @MainActor func testPhysicalAgentHubFeatureNotificationResolve() throws {
    let fixture = try physicalConfig()
    physicalOpenThread(fixture.connection.threadId)
    XCTAssertEqual(composer.value as? String, "PHONE_DRAFT_" + fixture.connection.nonce)
    physicalResolveApproval()
  }
}

extension CodexManageUITests {
  @MainActor func testPhysicalAgentHubFeatureCleanupRestoreOriginal() throws {
    let fixture = try physicalConfig()
    app.buttons["client-switch"].tap()
    XCTAssertFalse(app.buttons["client-" + fixture.connection.hostId].exists)
    let original = app.buttons["client-" + fixture.originalHostId]
    for _ in 0..<8 { if original.isHittable { break }; app.swipeDown() }
    XCTAssertTrue(original.isHittable); original.tap()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    shot("physical-dev-connection-removed-original-client-retained")
  }
  @MainActor func testPhysicalAgentHubFeatureCleanup() throws {
    let fixture = try physicalConfig()
    app.buttons["client-switch"].tap()
    let owned = app.buttons["client-" + fixture.connection.hostId]
    for _ in 0..<14 { if owned.isHittable { break }; app.swipeUp() }
    XCTAssertTrue(owned.isHittable); XCTAssertTrue(owned.label.contains(fixture.label))
    owned.swipeLeft()
    XCTAssertTrue(app.buttons["移除连接"].waitForExistence(timeout: 10)); app.buttons["移除连接"].tap()
    XCTAssertTrue(owned.waitForNonExistence(timeout: 30))
    if !app.navigationBars["客户端"].exists { app.buttons["client-switch"].tap() }
    let original = app.buttons["client-" + fixture.originalHostId]
    for _ in 0..<16 { if original.isHittable { break }; app.swipeDown() }
    XCTAssertTrue(original.isHittable); original.tap()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20)); shot("physical-dev-connection-removed-original-client-retained")
  }
}

extension CodexManageUITests {
  @MainActor func testPhysicalAgentHubFeatureCompletionNotification() throws {
    let fixture = try physicalConfig()
    let marker = fixture.completionMarker ?? ("PHONE_COMPLETE_" + fixture.connection.nonce)
    physicalOpenThread(fixture.connection.threadId)
    XCTAssertEqual(composer.value as? String, "PHONE_DRAFT_" + fixture.connection.nonce)
    let screen = app.frame
    XCUIDevice.shared.press(.home)
    print("PHYSICAL_NOTIFICATION_READY")
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: 1)).press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: screen.width * 0.2, dy: screen.height * 0.85)))
    let notice = springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
    XCTAssertTrue(notice.waitForExistence(timeout: 180)); shot("physical-real-completion-apns", fullScreen: true)
    func point(_ element: XCUIElement) -> XCUICoordinate {
      let f = element.frame
      let scale = f.midY > screen.height ? springboard.frame.width / screen.width : 1
      return springboard.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX / scale, dy: f.midY / scale))
    }
    point(notice).press(forDuration: 1.2)
    XCTAssertTrue(springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "任务完成")).firstMatch.waitForExistence(timeout: 15))
    XCTAssertTrue(springboard.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fixture.label)).firstMatch.exists)
    shot("physical-expanded-completion-summary-computer-kind", fullScreen: true)
    point(notice).tap()
    if !app.wait(for: .runningForeground, timeout: 5), notice.exists { point(notice).tap() }
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
    XCTAssertTrue(app.buttons["client-switch"].label.contains(fixture.label))
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label == %@", marker)).firstMatch.waitForExistence(timeout: 60))
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: app.buttons["client-switch"])
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 60), .completed)
    XCTAssertFalse(app.alerts.firstMatch.exists)
    XCTAssertFalse(app.buttons["stop-turn"].exists)
    XCTAssertEqual(composer.value as? String, "PHONE_DRAFT_" + fixture.connection.nonce)
    shot("physical-completion-notice-opened-exact-original-chat")
  }
}

extension CodexManageUITests {
  @MainActor func testChatPendingRecordsMenuFixture() async throws {
    let base = URL(string: "http://127.0.0.1:8820")!
    let (data, _) = try await URLSession.shared.data(from: base)
    let fixture = try JSONDecoder().decode(LiveFixture.self, from: data)
    connectLive(fixture, supportsCreation: false); openDesktopTestConversation(fixture)
    if app.buttons["close-sidebar"].exists { app.buttons["recent-thread-" + fixture.threadId].tap() }
    XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
    func menu() {
      let button = app.buttons["chat-menu"]
      let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true AND (value == nil OR value != %@)", "正在更新…"), object: button)
      XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 30), .completed)
      button.tap(); XCTAssertTrue(app.buttons["archived-chats"].waitForExistence(timeout: 10))
    }
    func waitPosts(_ count: Int) async throws {
      for _ in 0..<50 {
        let (statsData, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/record-stats"))
        let stats = try JSONSerialization.jsonObject(with: statsData) as! [String: Any]
        if (stats["posts"] as? [[String: Any]])?.count == count { return }
        try await Task.sleep(for: .milliseconds(200))
      }
      XCTFail("Expected exact named action count without any retry")
    }
    menu(); app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "chat-pin", "置顶")).firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    try await waitPosts(1)
    app.buttons["open-sidebar"].tap()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    let other = app.buttons["recent-thread-search-second-thread"]
    XCTAssertTrue(other.waitForExistence(timeout: 10)); other.tap()
    if app.buttons["close-sidebar"].waitForNonExistence(timeout: 2) == false { other.tap() }
    XCTAssertTrue(app.staticTexts["SECOND_TARGET_ONLY"].waitForExistence(timeout: 15))
    menu(); app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "chat-archive", "归档")).firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    try await waitPosts(2)
    for _ in 0..<30 {
      let (statsData, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/record-stats"))
      let stats = try JSONSerialization.jsonObject(with: statsData) as! [String: Any]
      if (stats["posts"] as? [[String: Any]])?.count == 2 { break }
      try await Task.sleep(for: .milliseconds(200))
    }
    menu()
    let queryPredicate = NSPredicate(format: "identifier == %@ OR label CONTAINS %@", "chat-action-query", "查询聊天操作记录")
    let queries = app.buttons.matching(queryPredicate)
    XCTAssertEqual(queries.count, 2)
    XCTAssertTrue(queries.matching(NSPredicate(format: "label CONTAINS %@", "置顶")).firstMatch.exists)
    XCTAssertTrue(queries.matching(NSPredicate(format: "label CONTAINS %@", "归档")).firstMatch.exists)
    let original = queries.matching(NSPredicate(format: "label CONTAINS %@", "Chat Search Fixture")).firstMatch
    XCTAssertTrue(original.exists)
    let (recordData, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/record-stats"))
    let recordStats = try JSONSerialization.jsonObject(with: recordData) as! [String: Any]
    let operations = try XCTUnwrap(recordStats["posts"] as? [[String: Any]])
    let expected = try XCTUnwrap(operations.first { $0["threadId"] as? String == fixture.threadId })
    let expectedId = try XCTUnwrap(expected["clientOperationId"] as? String)
    XCTAssertTrue(original.label.contains(String(expectedId.prefix(8)))); shot("pending-record-actions-targets-identifiable")
    original.tap()
    for _ in 0..<30 {
      let (statsData, _) = try await URLSession.shared.data(from: base.appendingPathComponent("fixture/record-stats"))
      let stats = try JSONSerialization.jsonObject(with: statsData) as! [String: Any]
      if let checks = stats["queries"] as? [[String: Any]], let check = checks.last {
        XCTAssertEqual(check["threadId"] as? String, fixture.threadId)
        XCTAssertEqual(check["id"] as? String, expectedId); break
      }
      try await Task.sleep(for: .milliseconds(200))
    }
    menu(); shot("pending-record-original-query-keeps-other-target")
    XCTAssertEqual(app.buttons.matching(queryPredicate).count, 2)
  }

  @MainActor func testPhysicalAgentHubFeatureStatusAndHistoricalFilePreview() throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the existing formal installation on the paired physical iPhone")
    #else
    struct ExistingFile: Decodable {
      let hostId: String; let threadId: String; let title: String
      let messageId: String; let linkLabel: String; let previewMarker: String
      let expectedErrorCode: String?
    }
    let fixture = try JSONDecoder().decode(ExistingFile.self, from: Data(XCTUnwrap(
      ProcessInfo.processInfo.environment["STATUS_PREVIEW_PHYSICAL_JSON"]).utf8))
    dismissLiveQuestionPresentation(wait: 2)
    let switcher = app.buttons["client-switch"]
    XCTAssertTrue(switcher.waitForExistence(timeout: 30)); switcher.tap()
    let original = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'client-' AND selected == true")).firstMatch
    let originalId = original.exists ? original.identifier : nil
    let target = app.buttons["client-" + fixture.hostId]
    XCTAssertTrue(target.waitForExistence(timeout: 15)); target.tap()
    if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    let search = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    search.tap(); search.typeText(fixture.title + "\n")
    let row = app.buttons.matching(NSPredicate(format: "identifier IN %@", [
      "recent-thread-" + fixture.threadId, "thread-" + fixture.threadId, "pinned-thread-" + fixture.threadId])).firstMatch
    XCTAssertTrue(row.waitForExistence(timeout: 40)); row.tap()
    let connected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Codex · 实时连接"), object: switcher)
    XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 60), .completed)
    XCTAssertTrue(app.buttons["conversation-sync-retry"].waitForNonExistence(timeout: 60), app.debugDescription)
    XCTAssertFalse(app.staticTexts["实时状态暂不可用"].exists)
    shot("physical-easyai03-connected-without-false-warning")
    physicalMenu(); physicalAction("chat-search").tap()
    let find = app.textFields["chat-search-query"]
    XCTAssertTrue(find.waitForExistence(timeout: 15)); find.tap(); find.typeText(fixture.linkLabel)
    let result = app.buttons["chat-search-result-" + fixture.messageId]
    XCTAssertTrue(result.waitForExistence(timeout: 90), app.debugDescription); result.tap()
    let located = app.descendants(matching: .any)["chat-search-located-" + fixture.messageId].firstMatch
    XCTAssertTrue(located.waitForExistence(timeout: 30), app.debugDescription)
    let timeline = app.scrollViews["message-list"]
    let expand = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@",
      "chat-search-located-" + fixture.messageId, "完整消息")).firstMatch
    for _ in 0..<20 where expand.exists && !expand.isHittable {
      if expand.frame.minY < timeline.frame.minY + 100 { timeline.swipeDown() }
      else { timeline.swipeUp() }
    }
    if expand.exists { XCTAssertTrue(expand.isHittable); expand.tap() }
    let link = app.links.matching(NSPredicate(format: "label == %@", fixture.linkLabel)).firstMatch
    let text = app.staticTexts.matching(NSPredicate(format: "label == %@", fixture.linkLabel)).firstMatch
    for _ in 0..<16 where !(link.exists && link.isHittable) && !(text.exists && text.isHittable) {
      let element = link.exists ? link : text
      if element.exists && element.frame.minY < timeline.frame.minY + 100 { timeline.swipeDown() }
      else { timeline.swipeUp() }
    }
    shot("physical-original-screenshot-file-link")
    if link.exists && link.isHittable { link.tap() }
    else { XCTAssertTrue(text.isHittable, app.debugDescription); text.tap() }
    if let code = fixture.expectedErrorCode {
      let alert = app.alerts["无法打开链接"]
      XCTAssertTrue(alert.waitForExistence(timeout: 40), app.debugDescription)
      let detail = alert.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@ AND label CONTAINS %@", code, "HTTP 410", "诊断编号")).firstMatch
      XCTAssertTrue(detail.exists, app.debugDescription)
      shot("physical-missing-file-real-error-and-diagnostic-id")
      print("PHYSICAL_RESOURCE_ERROR", detail.label)
      alert.buttons["好"].tap()
      if let originalId, originalId != "client-" + fixture.hostId {
        switcher.tap(); app.buttons[originalId].tap()
        if app.buttons["完成"].exists { app.buttons["完成"].tap() }
      }
      return
    }
    let close = app.buttons.matching(NSPredicate(format:
      "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@",
      ["关闭", "Close", "Done", "完成"])).firstMatch
    XCTAssertTrue(close.waitForExistence(timeout: 40), app.debugDescription)
    let content = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", fixture.previewMarker)).firstMatch
    XCTAssertTrue(content.waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertFalse(app.alerts["无法打开链接"].exists)
    shot("physical-original-file-current-content-preview"); close.tap()
    XCTAssertFalse(app.buttons["conversation-sync-retry"].exists)
    if let originalId, originalId != "client-" + fixture.hostId {
      switcher.tap(); app.buttons[originalId].tap()
      if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    }
    print("PHYSICAL_STATUS_PREVIEW_PASSED", fixture.hostId, fixture.threadId, fixture.messageId)
    #endif
  }
}

extension CodexManageUITests {
  @MainActor func testAttachmentMetadataTriggerDEV() throws {
    struct Fixture: Decodable {
      let connection: LiveFixture
      let phase: String
      let messageId: String
      let marker: String
    }
    let input = try XCTUnwrap(ProcessInfo.processInfo.environment["ATTACHMENT_METADATA_DEV_JSON"])
    let fixture = try JSONDecoder().decode(Fixture.self, from: Data(input.utf8))
    XCTAssertTrue(["baseline", "candidate"].contains(fixture.phase))
    let monitor = addUIInterruptionMonitor(withDescription: "Isolated metadata acceptance wireless permission") { alert in
      for label in ["无线局域网与蜂窝网络", "允许"] where alert.buttons[label].exists {
        alert.buttons[label].tap(); return true
      }
      return false
    }
    defer { removeUIInterruptionMonitor(monitor) }
    let switcher = app.buttons["client-switch"]
    XCTAssertTrue(switcher.waitForExistence(timeout: 30)); switcher.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    let target = app.buttons["client-" + fixture.connection.hostId]
    if target.waitForExistence(timeout: 10) {
      target.tap()
      if app.buttons["完成"].exists { app.buttons["完成"].tap() }
    } else {
      if app.buttons["完成"].exists { app.buttons["完成"].tap() }
      connectLive(fixture.connection, supportsCreation: false, nickname: "MacBook ctime DEV")
    }
    _ = openDesktopSidebarSearch()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap() }
    let row = app.buttons.matching(NSPredicate(format: "identifier IN %@", [
      "recent-thread-" + fixture.connection.threadId, "thread-" + fixture.connection.threadId,
      "pinned-thread-" + fixture.connection.threadId])).firstMatch
    XCTAssertTrue(row.waitForExistence(timeout: 45), app.debugDescription); row.tap()
    let menu = app.buttons["chat-menu"]
    for _ in 0..<3 {
      if physicalAction("chat-search").exists { break }
      menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      _ = physicalAction("chat-search").waitForExistence(timeout: 3)
    }
    XCTAssertTrue(physicalAction("chat-search").waitForExistence(timeout: 10), app.debugDescription)
    physicalAction("chat-search").tap()
    let query = app.textFields["chat-search-query"]
    XCTAssertTrue(query.waitForExistence(timeout: 15)); query.tap()
    query.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: (query.value as? String ?? "").count))
    query.typeText(fixture.marker)
    let result = app.buttons["chat-search-result-" + fixture.messageId]
    XCTAssertTrue(result.waitForExistence(timeout: 90), app.debugDescription); result.tap()
    let timeline = app.scrollViews["message-list"]
    let expand = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@",
      "chat-search-located-" + fixture.messageId, "完整消息")).firstMatch
    for _ in 0..<12 where expand.exists && !expand.isHittable {
      if expand.frame.minY < timeline.frame.minY + 100 { timeline.swipeDown() } else { timeline.swipeUp() }
    }
    if expand.exists { XCTAssertTrue(expand.isHittable); expand.tap() }
    func tapLink(_ label: String) {
      let link = app.links.matching(NSPredicate(format: "label == %@", label)).firstMatch
      let text = app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch
      for _ in 0..<18 where !(link.exists && link.isHittable) && !(text.exists && text.isHittable) {
        let element = link.exists ? link : text
        if element.exists && element.frame.minY < timeline.frame.minY + 100 { timeline.swipeDown() }
        else { timeline.swipeUp() }
      }
      if link.exists && link.isHittable { link.tap() }
      else { XCTAssertTrue(text.isHittable, app.debugDescription); text.tap() }
    }
    func expectError(_ code: String, label: String) {
      let alert = app.alerts["无法打开链接"]
      XCTAssertTrue(alert.waitForExistence(timeout: 40), app.debugDescription)
      XCTAssertTrue(alert.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "HTTP 409", code)).firstMatch.exists, app.debugDescription)
      shot("metadata-" + fixture.phase + "-" + label + "-" + code)
      alert.buttons["好"].tap()
    }
    func expectPreview(_ marker: String, label: String) {
      let done = app.buttons.matching(NSPredicate(format:
        "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@",
        ["关闭", "Close", "Done", "完成"])).firstMatch
      XCTAssertTrue(done.waitForExistence(timeout: 40), app.debugDescription)
      let preview = app.otherElements["QLPreviewControllerView"]
      XCTAssertTrue(preview.descendants(matching: .textView).matching(NSPredicate(format:
        "label CONTAINS %@ OR value CONTAINS %@", marker, marker)).firstMatch.waitForExistence(timeout: 20), app.debugDescription)
      XCTAssertFalse(app.alerts["无法打开链接"].exists)
      shot("metadata-" + fixture.phase + "-" + label + "-preview"); done.tap()
    }
    for (label, marker) in [("ctime 瞬时下载", "CTIME_READ_"), ("ctime 瞬时解析", "CTIME_RESOLVE_")] {
      tapLink(label)
      if fixture.phase == "baseline" { expectError("FILE_CHANGED", label: label) }
      else { expectPreview(marker + fixture.connection.nonce, label: label) }
    }
    tapLink("ctime 持续变化")
    expectError(fixture.phase == "baseline" ? "FILE_CHANGED" : "FILE_METADATA_UNSTABLE", label: "continuous")
    tapLink("ctime 不变对照")
    expectPreview("CTIME_STABLE_" + fixture.connection.nonce, label: "control")
    print("CTIME_TRIGGER_PHASE_PASSED", fixture.phase, fixture.connection.hostId, fixture.connection.threadId)
  }
}

extension CodexManageUITests {
  @MainActor func testFileInteractionFeedbackDEV() throws {
    struct Fixture: Decodable { let connection: LiveFixture; let linkLabel: String; let fileName: String; let marker: String; let pairingUri: String? }
    let fixture = try JSONDecoder().decode(Fixture.self, from: Data(XCTUnwrap(
      ProcessInfo.processInfo.environment["FILE_INTERACTION_FIXTURE_JSON"]).utf8))
    prepareOwnAcceptanceNetworkPermission()
    if fixture.connection.endpoint.hasPrefix("http://127.0.0.1:") {
      connectLive(fixture.connection, supportsCreation: false)
    } else {
      app.buttons["client-switch"].tap()
      let add = app.buttons["add-client"]
      for _ in 0..<6 where !add.isHittable { app.swipeUp() }
      XCTAssertTrue(add.waitForExistence(timeout: 15)); add.tap()
      if let uri = fixture.pairingUri {
        selectConnectionMethod("扫码连接", agent: "codex")
        let qr = app.textFields["codex-qr-description"]
        XCTAssertTrue(qr.waitForExistence(timeout: 15)); qr.tap(); qr.typeText(uri)
        if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
        app.buttons["codex-connect-qr"].tap()
        XCTAssertTrue(qr.waitForNonExistence(timeout: 90), app.debugDescription)
      } else {
        let host = app.buttons["codex-discovered-" + fixture.connection.hostId]
        XCTAssertTrue(host.waitForExistence(timeout: 40), app.debugDescription); host.tap()
        let key = app.secureTextFields["codex-host-key"]
        XCTAssertTrue(key.waitForExistence(timeout: 15)); key.tap(); key.typeText(fixture.connection.token)
        app.buttons["codex-authorize-connect"].tap()
        XCTAssertTrue(key.waitForNonExistence(timeout: 90), app.debugDescription)
      }
    }
    if !fixture.connection.endpoint.hasPrefix("http://127.0.0.1:") { dismissSystemPasswordOffer() }
    openDesktopTestConversation(fixture.connection)
    let timeline = app.scrollViews["message-list"]
    let share = app.buttons["share-" + fixture.fileName].firstMatch
    let preview = app.buttons["preview-" + fixture.fileName].firstMatch
    for _ in 0..<15 where !share.isHittable { timeline.swipeUp() }
    XCTAssertTrue(share.isHittable && preview.isHittable, app.debugDescription)
    XCTAssertFalse(app.buttons["download-" + fixture.fileName].exists)
    shot("file-card-direct-save-before-download")
    share.tap()
    XCTAssertTrue(app.staticTexts["file-preparation-loading"].waitForExistence(timeout: 3), app.debugDescription)
    XCTAssertFalse(share.isEnabled)
    shot("file-card-preparing-share")
    let save = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@", "存储到", "保存到", "Save to Files")).firstMatch
    XCTAssertTrue(save.waitForExistence(timeout: 45), app.debugDescription)
    shot("file-card-system-save-sheet")
    let closeShare = app.buttons.matching(NSPredicate(format: "label IN %@", ["关闭", "Close"])).firstMatch
    if closeShare.exists { closeShare.tap() }
    else { app.swipeDown() }
    XCTAssertTrue(save.waitForNonExistence(timeout: 10), app.debugDescription)
    func tapLink(_ label: String) {
      let link = app.links.matching(NSPredicate(format: "label == %@", label)).firstMatch
      let text = app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch
      for _ in 0..<20 where !(link.exists && link.isHittable) && !(text.exists && text.isHittable) {
        let element = link.exists ? link : text
        if element.exists && element.frame.minY < timeline.frame.minY + 100 { timeline.swipeDown() }
        else { timeline.swipeUp() }
      }
      let element = link.exists && link.isHittable ? link : text
      XCTAssertTrue(element.isHittable, app.debugDescription); element.tap()
    }
    tapLink(fixture.linkLabel)
    let loading = app.descendants(matching: .any)["message-link-loading"].firstMatch
    XCTAssertTrue(loading.waitForExistence(timeout: 3), app.debugDescription)
    XCTAssertTrue(loading.isHittable, "Loading must stay beside the selected link, inside the visible paragraph")
    XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", "正在下载并打开文件…")).count, 1,
      "Other links to the same file must not show a second loading state")
    shot("message-link-selected-with-visible-loading")
    let done = app.buttons.matching(NSPredicate(format: "identifier == 'QLOverlayDoneButtonAccessibilityIdentifier' OR identifier == 'QLDoneButton' OR label IN %@", ["关闭", "Close", "Done", "完成"])).firstMatch
    XCTAssertTrue(done.waitForExistence(timeout: 40), app.debugDescription)
    let content = app.otherElements["QLPreviewControllerView"].descendants(matching: .textView).matching(
      NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", fixture.marker, fixture.marker)).firstMatch
    XCTAssertTrue(content.waitForExistence(timeout: 15), app.debugDescription)
    shot("message-link-real-file-preview"); done.tap()
    XCTAssertTrue(loading.waitForNonExistence(timeout: 5))
    tapLink("缺失文件")
    XCTAssertTrue(app.alerts["无法打开链接"].waitForExistence(timeout: 30), app.debugDescription)
    shot("message-link-missing-file-feedback")
    app.alerts["无法打开链接"].buttons["好"].tap()
    XCTAssertFalse(loading.exists)
  }
}

extension CodexManageUITests {
  @MainActor func testPhysicalCompletedLoadedLifecycleDEV() async throws {
    #if targetEnvironment(simulator)
    throw XCTSkip("Requires the explicitly paired physical iPhone")
    #else
    struct Configuration: Decodable {
      let bundleId: String
      let expectedHostId: String
      let phase: String
      let projectId: String
      let marker: String
      let prompt: String
      let connection: LiveFixture
    }
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["LOADED_LIFECYCLE_PHYSICAL_JSON"])
    let config = try JSONDecoder().decode(Configuration.self, from: Data(encoded.utf8))
    XCTAssertEqual(config.bundleId, "com.easyai.agenthub.loadedlifecycle")
    let fixture = config.connection
    XCTAssertTrue(fixture.endpoint.hasPrefix("https://"))
    XCTAssertFalse(config.expectedHostId.isEmpty)
    XCTAssertEqual(fixture.hostId, config.expectedHostId)
    if !(app.buttons["client-switch"].value as? String ?? "").contains("Codex · 实时连接") {
      connectLive(fixture, supportsCreation: false, nickname: "MacBook 128G 归档删除验收")
    }
    if config.phase == "model" {
      XCTAssertTrue(fixture.threadId.isEmpty, "Never resubmit an existing model task")
      chooseDesktopProject(config.projectId)
      let settings = app.buttons["generation-settings"]
      XCTAssertTrue(settings.waitForExistence(timeout: 30)); settings.tap()
      app.descendants(matching: .any)["model-picker"].firstMatch.tap()
      let model = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "GPT-6.1-Sol")).firstMatch
      XCTAssertTrue(model.waitForExistence(timeout: 15)); model.tap()
      app.descendants(matching: .any)["effort-picker"].firstMatch.tap()
      app.buttons["极高"].tap(); app.buttons["generation-done"].tap()
      focusLiveComposer()
      let draft = composer.value as? String ?? ""
      XCTAssertTrue(draft.isEmpty || draft == composer.placeholderValue, "Preserve unknown drafts")
      typeComposerText(config.prompt)
      XCTAssertEqual(composer.value as? String, config.prompt)
      sendLiveComposer()
      waitForDesktopAnswer(config.marker)
      shot("physical-loaded-lifecycle-unique-model-completed")
      return
    }
    XCTAssertTrue(["archive", "delete"].contains(config.phase))
    XCTAssertFalse(fixture.threadId.isEmpty)
    openDesktopTestConversation(fixture)
    waitForDesktopAnswer(config.marker)
    physicalMenu()
    XCTAssertTrue(app.descendants(matching: .any).matching(
      NSPredicate(format: "label == %@", "未归档 · 任务状态：已完成")).firstMatch.exists,
      app.debugDescription)
    XCTAssertTrue(physicalAction("chat-archive").isEnabled)
    XCTAssertTrue(physicalAction("chat-delete").isEnabled)
    // A live original owner and an explicitly completed turn are required
    // immediately before the native action. Persisted history alone is insufficient.
    func originalOwnerWitness(_ label: String) async throws {
      var url = try XCTUnwrap(URL(string: fixture.endpoint)).appendingPathComponent("v1/history")
      url.append(queryItems: [URLQueryItem(name: "hostId", value: fixture.hostId),
        URLQueryItem(name: "threadId", value: fixture.threadId)])
      var request = URLRequest(url: url); request.timeoutInterval = 45
      request.setValue("Bearer " + fixture.token, forHTTPHeaderField: "Authorization")
      let (data, response) = try await URLSession.shared.data(for: request)
      XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
      let value = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
      XCTAssertEqual(value["hostId"] as? String, fixture.hostId)
      XCTAssertEqual(value["threadId"] as? String, fixture.threadId)
      let runtime = try XCTUnwrap(value["runtime"] as? [String: Any])
      let thread = try XCTUnwrap(value["thread"] as? [String: Any])
      XCTAssertEqual(value["ready"] as? Bool, true)
      XCTAssertEqual(runtime["canControl"] as? Bool, true)
      XCTAssertEqual(runtime["state"] as? String, "idle")
      XCTAssertEqual(thread["lastTurnStatus"] as? String, "completed")
      XCTAssertEqual(thread["archived"] as? Bool, false)
      let settings = try XCTUnwrap(runtime["generationSettings"] as? [String: Any])
      XCTAssertEqual(settings["effort"] as? String, "xhigh")
      let proof: [String: Any] = ["at": ISO8601DateFormatter().string(from: Date()),
        "hostId": fixture.hostId, "threadId": fixture.threadId, "phase": label,
        "ready": true, "canControl": true, "state": "idle", "lastTurnStatus": "completed",
        "archived": false, "effort": "xhigh"]
      let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: proof),
        uniformTypeIdentifier: "public.json")
      attachment.name = "physical-original-owner-" + label; attachment.lifetime = .keepAlways; add(attachment)
    }
    try await originalOwnerWitness("before-" + config.phase)
    shot("physical-completed-still-loaded-before-" + config.phase)
    if config.phase == "archive" {
      physicalAction("chat-archive").tap()
      physicalMenu()
      let restored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "恢复对话"),
        object: physicalAction("chat-archive"))
      XCTAssertEqual(XCTWaiter.wait(for: [restored], timeout: 45), .completed)
      XCTAssertFalse(app.alerts.firstMatch.exists)
      shot("physical-completed-loaded-archive-confirmed")
    } else {
      physicalAction("chat-delete").tap()
      let title = app.staticTexts["删除此对话？"]
      XCTAssertTrue(title.waitForExistence(timeout: 10))
      XCTAssertTrue(physicalAction("chat-delete-confirm").isEnabled)
      shot("physical-completed-loaded-delete-confirmation")
      app.buttons["取消"].firstMatch.tap()
      XCTAssertTrue(title.waitForNonExistence(timeout: 10))
      try await originalOwnerWitness("delete-cancelled")
      physicalMenu(); physicalAction("chat-delete").tap()
      XCTAssertTrue(title.waitForExistence(timeout: 10))
      physicalAction("chat-delete-confirm").tap()
      XCTAssertTrue(title.waitForNonExistence(timeout: 45))
      let removedAnswer = app.staticTexts.matching(NSPredicate(format: "label == %@", config.marker)).firstMatch
      XCTAssertTrue(removedAnswer.waitForNonExistence(timeout: 45), "The deleted conversation must leave the native view")
      XCTAssertFalse(app.alerts.firstMatch.exists)
      shot("physical-completed-loaded-delete-confirmed")
    }
    #endif
  }
}

extension CodexManageUITests {
  @MainActor private func dismissUnreadFixtureInboxAlert() {
    // This independent build omits the unrelated share-extension App Group.
    // Its exact setup warning must not cover the reading UI after relaunch.
    let message = app.staticTexts["暂时无法读取共享文件，请稍后重试。"]
    if message.waitForExistence(timeout: 3) {
      let dismiss = app.buttons["好"].firstMatch
      XCTAssertTrue(dismiss.isHittable); dismiss.tap()
      XCTAssertTrue(message.waitForNonExistence(timeout: 10))
    }
  }
  @MainActor func testMobileReadingPhysicalAttachmentReplyDEV() throws {
    struct Config: Decodable { var connection: LiveFixture; var pairingUri: String }
    let encoded = try XCTUnwrap(ProcessInfo.processInfo.environment["UNREAD_PHYSICAL_JSON"])
    let fixture = try JSONDecoder().decode(Config.self, from: Data(encoded.utf8))
    addUIInterruptionMonitor(withDescription: "Unread acceptance own network") { alert in
      let allow = alert.buttons.matching(NSPredicate(format: "label IN %@",
        ["无线局域网与蜂窝网络", "无线局域网与蜂窝数据", "允许", "Allow", "好", "OK"])).firstMatch
      guard allow.exists else { return false }; allow.tap(); return true
    }
    allowAcceptanceLocalNetworkPermission()
    dismissUnreadFixtureInboxAlert()
    app.buttons["client-switch"].tap()
    let existing = app.buttons["client-" + fixture.connection.hostId]
    if existing.exists { existing.tap() } else {
      app.buttons["add-client"].tap(); selectConnectionMethod("扫码连接", agent: "codex")
      let qr = app.textFields["codex-qr-description"]
      XCTAssertTrue(qr.waitForExistence(timeout: 15)); qr.tap(); qr.typeText(fixture.pairingUri)
      if app.buttons["完成输入"].exists { app.buttons["完成输入"].tap() }
      app.buttons["codex-connect-qr"].tap()
      XCTAssertTrue(qr.waitForNonExistence(timeout: 90), app.debugDescription)
      dismissSystemPasswordOffer()
    }
    physicalSidebar()
    if !app.textFields["sidebar-search"].exists { app.buttons["sidebar-search-toggle"].tap() }
    let search = app.textFields["sidebar-search"]
    XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap()
    if app.buttons["清除搜索"].exists { app.buttons["清除搜索"].tap(); search.tap() }
    search.typeText(fixture.connection.nonce + "\n")
    let row = app.buttons["recent-thread-" + fixture.connection.threadId]
    XCTAssertTrue(row.waitForExistence(timeout: 30), app.debugDescription)
    row.tap()
    XCTAssertTrue(app.buttons["close-sidebar"].waitForNonExistence(timeout: 10))
    let latest = app.buttons["跳转最新消息"]
    if latest.waitForExistence(timeout: 3) { latest.tap() }
    let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fixture.connection.nonce)).firstMatch
    XCTAssertTrue(answer.waitForExistence(timeout: 30), app.debugDescription)
    let loading = app.progressIndicators["history-loading"]
    XCTAssertTrue(loading.waitForNonExistence(timeout: 30))
    // Ensure the long attachment response is at the latest visible position.
    app.scrollViews["message-list"].swipeUp()
    Thread.sleep(forTimeInterval: 1)
    shot("physical-attachment-reply-latest-visible")
    physicalSidebar()
    let cleared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == ''"), object: row)
    XCTAssertEqual(XCTWaiter.wait(for: [cleared], timeout: 15), .completed, app.debugDescription)
    shot("physical-attachment-reply-unread-cleared")
    pullSidebarRefresh()
    XCTAssertEqual(row.value as? String, "")
    shot("physical-attachment-reply-refresh-stays-read")
    app.terminate()
    app.launchArguments = ["-ui-testing", "-restore-testing"]
    app.launchChineseTest()
    dismissUnreadFixtureInboxAlert()
    XCTAssertTrue(app.buttons["open-sidebar"].waitForExistence(timeout: 20))
    physicalSidebar()
    // Search preference is ephemeral; find the exact same recent conversation.
    if !row.exists {
      if !app.textFields["sidebar-search"].exists { app.buttons["sidebar-search-toggle"].tap() }
      let restoredSearch = app.textFields["sidebar-search"]
      restoredSearch.tap(); restoredSearch.typeText(fixture.connection.nonce + "\n")
    }
    XCTAssertTrue(row.waitForExistence(timeout: 20))
    XCTAssertEqual(row.value as? String, "")
    pullSidebarRefresh(); XCTAssertEqual(row.value as? String, "")
    shot("physical-attachment-reply-relaunch-stays-read")
  }
}

extension CodexManageUITests {
  @MainActor func testMarkdownFilePreviewPhysicalDEV() async throws {
    struct Fixture: Decodable {
      let bundleId: String
      let connection: LiveFixture
      let markdownLabel: String
      let plainLabel: String
      let sourceSha256: String
    }
    let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["FILE_PREVIEW_FIXTURE"])
    let fixture = try JSONDecoder().decode(Fixture.self, from: Data(raw.utf8))
    XCTAssertTrue(fixture.bundleId.hasPrefix("com.easyai.agenthub.filepreview."))
    connectLive(fixture.connection, supportsCreation: false, nickname: "Markdown DEV")
    openDesktopTestConversation(fixture.connection)
    let timeline = app.scrollViews["message-list"]
    func tapLink(_ label: String) {
      let link = app.links.matching(NSPredicate(format: "label == %@", label)).firstMatch
      let fallback = app.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch
      for _ in 0..<10 where !link.isHittable && !fallback.isHittable { timeline.swipeUp() }
      if link.isHittable { link.tap() }
      else { XCTAssertTrue(fallback.isHittable, app.debugDescription); fallback.tap() }
    }
    func section(_ title: String) {
      app.buttons["file-preview-outline"].tap()
      let entry = app.buttons.matching(NSPredicate(format: "label == %@", title)).firstMatch
      XCTAssertTrue(entry.waitForExistence(timeout: 10), app.debugDescription); entry.tap()
    }
    tapLink(fixture.markdownLabel)
    XCTAssertTrue(app.buttons["file-preview-close"].waitForExistence(timeout: 30), app.debugDescription)
    XCTAssertTrue(app.staticTexts["Markdown 验收"].waitForExistence(timeout: 15), app.debugDescription)
    XCTAssertFalse(app.staticTexts["file-preview-error"].exists)
    shot("markdown-preview-native-opening")
    section("工具清单")
    let horizontal = app.scrollViews["file-preview-table-5"]
    XCTAssertTrue(horizontal.waitForExistence(timeout: 10), app.debugDescription)
    let visibleTable = horizontal.frame.intersection(app.scrollViews["file-preview-content"].frame).insetBy(dx: 20, dy: 20)
    XCTAssertGreaterThan(visibleTable.width, 100); XCTAssertGreaterThan(visibleTable.height, 60)
    let origin = app.coordinate(withNormalizedOffset: .zero)
    for _ in 0..<2 {
      origin.withOffset(CGVector(dx: visibleTable.maxX - 12, dy: visibleTable.minY + 30))
        .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: visibleTable.minX + 12, dy: visibleTable.minY + 30)))
    }
    XCTAssertTrue(app.staticTexts["完整说明"].isHittable, app.debugDescription)
    shot("markdown-preview-table-horizontal")
    // Restore a position inside a tall table, not just its top-level block ID.
    for _ in 0..<2 {
      origin.withOffset(CGVector(dx: visibleTable.minX + 12, dy: visibleTable.minY + 30))
        .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: visibleTable.maxX - 12, dy: visibleTable.minY + 30)))
    }
    let twentieth = app.staticTexts["tool-20"]
    let contentFrame = app.scrollViews["file-preview-content"].frame
    for _ in 0..<10 where !twentieth.isHittable {
      origin.withOffset(CGVector(dx: contentFrame.midX, dy: contentFrame.minY + contentFrame.height * 0.75))
        .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: contentFrame.midX, dy: contentFrame.minY + contentFrame.height * 0.5)))
    }
    XCTAssertTrue(twentieth.isHittable, app.debugDescription)
    app.buttons["file-preview-close"].tap()
    tapLink(fixture.markdownLabel)
    XCTAssertTrue(app.buttons["file-preview-close"].waitForExistence(timeout: 20))
    let tableRestored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: twentieth)
    XCTAssertEqual(XCTWaiter.wait(for: [tableRestored], timeout: 10), .completed, app.debugDescription)
    shot("markdown-preview-table-offset-restored")
    section("末尾章节")
    let ending = app.staticTexts["END_" + fixture.connection.nonce]
    XCTAssertTrue(ending.waitForExistence(timeout: 10), app.debugDescription)
    shot("markdown-preview-outline-end")
    app.buttons["file-preview-search"].tap()
    let query = app.textFields["file-preview-query"]
    XCTAssertTrue(query.waitForExistence(timeout: 5)); query.tap()
    query.typeText("BEGIN_" + fixture.connection.nonce + "\n")
    let beginning = app.staticTexts["BEGIN_" + fixture.connection.nonce]
    let visible = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: beginning)
    XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 15), .completed, app.debugDescription)
    XCTAssertEqual(app.staticTexts["file-preview-match-count"].label, "1/1")
    shot("markdown-preview-search-position")
    app.buttons["file-preview-search"].tap()
    app.segmentedControls["file-preview-mode"].buttons["源码"].tap()
    let source = app.textViews["file-preview-source"]
    XCTAssertTrue(source.waitForExistence(timeout: 10), app.debugDescription)
    let value = try XCTUnwrap(source.value as? String)
    XCTAssertEqual(SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined(), fixture.sourceSha256)
    shot("markdown-preview-original-source")
    app.segmentedControls["file-preview-mode"].buttons["预览"].tap()
    section("末尾章节")
    tapLink("未授权相对文件")
    XCTAssertTrue(app.alerts["无法打开链接"].waitForExistence(timeout: 5), app.debugDescription)
    shot("markdown-preview-relative-reference-denied")
    app.alerts["无法打开链接"].buttons["好"].tap()
    app.buttons["file-preview-close"].tap()
    tapLink(fixture.markdownLabel)
    XCTAssertTrue(app.buttons["file-preview-close"].waitForExistence(timeout: 20))
    XCTAssertTrue(ending.waitForExistence(timeout: 10))
    let endRestored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: ending)
    XCTAssertEqual(XCTWaiter.wait(for: [endRestored], timeout: 10), .completed,
      "Reopening the same snapshot restores its reading position")
    shot("markdown-preview-reading-position-restored")
    app.buttons["file-preview-close"].tap()
    tapLink(fixture.plainLabel)
    let done = app.buttons.matching(NSPredicate(format: "identifier IN %@ OR label IN %@", ["QLOverlayDoneButtonAccessibilityIdentifier", "QLDoneButton"], ["关闭", "Close", "Done", "完成"])).firstMatch
    XCTAssertTrue(done.waitForExistence(timeout: 20), app.debugDescription)
    XCTAssertFalse(app.buttons["file-preview-close"].exists, "Other file types retain system preview")
    shot("markdown-preview-plain-file-system-fallback")
    done.tap()
  }
}
