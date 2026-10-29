import MobileCore
import PhotosUI
import QuickLook
import QuartzCore
import SwiftUI
import UniformTypeIdentifiers
import os

enum Palette {
  static let background = Color(uiColor: .systemBackground)
  static let sidebar = Color(uiColor: .secondarySystemBackground)
  static let secondary = Color(uiColor: .secondaryLabel)
  static let link = Color(uiColor: .link)
  static let toolDetail = Color(
    uiColor: UIColor { UIColor(white: $0.userInterfaceStyle == .dark ? MobileDesignColors.toolDetailDark : MobileDesignColors.toolDetailLight, alpha: 1) })
  static let bubble = Color(
    uiColor: UIColor {
      $0.userInterfaceStyle == .dark
        ? UIColor(red: MobileDesignColors.userBubbleDark[0], green: MobileDesignColors.userBubbleDark[1], blue: MobileDesignColors.userBubbleDark[2], alpha: 1)
        : UIColor(red: MobileDesignColors.userBubbleLight[0], green: MobileDesignColors.userBubbleLight[1], blue: MobileDesignColors.userBubbleLight[2], alpha: 1)
    })
  static let bubbleText = Color(
    uiColor: UIColor {
      $0.userInterfaceStyle == .dark
        ? UIColor(red: MobileDesignColors.userBubbleTextDark[0], green: MobileDesignColors.userBubbleTextDark[1], blue: MobileDesignColors.userBubbleTextDark[2], alpha: 1)
        : UIColor(red: MobileDesignColors.userBubbleTextLight[0], green: MobileDesignColors.userBubbleTextLight[1], blue: MobileDesignColors.userBubbleTextLight[2], alpha: 1)
    })
}

struct IconButton: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let symbol: String
  let label: String
  let id: String
  var action: () -> Void
  var body: some View {
    Button(action: action) {
      Image(systemName: symbol).font(.system(size: 17)).frame(minWidth: 44, minHeight: 44)
        .contentShape(Rectangle())
    }
    .buttonStyle(.plain).accessibilityLabel(label).accessibilityIdentifier(id)
  }
}

private struct FloatingSurface: ViewModifier {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  var cornerRadius: CGFloat = MobileDesignGeometry.floatingRadius

  func body(content: Content) -> some View {
    let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    content.background(.regularMaterial, in: shape)
      .overlay { shape.strokeBorder(Color.primary.opacity(MobileDesignGeometry.floatingStrokeAlpha), lineWidth: MobileDesignGeometry.floatingStrokeWidth) }
      .shadow(color: .black.opacity(MobileDesignGeometry.floatingShadowAlpha), radius: MobileDesignGeometry.floatingShadowRadius, y: MobileDesignGeometry.floatingShadowYOffset)
  }
}

private struct ComposerActionAppearance: ViewModifier {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let enabled: Bool
  var busy = false

  func body(content: Content) -> some View {
    content
      .foregroundStyle(enabled || busy ? Palette.background : Color(uiColor: .tertiaryLabel))
      .background(
        enabled || busy ? Color.primary : Color(uiColor: .tertiarySystemFill), in: Circle())
  }
}

extension View {
  func floatingSurface(cornerRadius: CGFloat = MobileDesignGeometry.floatingRadius) -> some View {
    modifier(FloatingSurface(cornerRadius: cornerRadius))
  }
}

private struct ComposerHeightPreference: PreferenceKey {
  static let defaultValue: CGFloat = 0
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct RunningTaskIndicator: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let isVisible: Bool
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.scenePhase) private var scenePhase
  @ScaledMetric(relativeTo: .callout) private var size = 17.0

  var body: some View {
    RunningTaskSymbol(isAnimating: isVisible && scenePhase == .active && !reduceMotion, size: size)
      .frame(width: size, height: size)
      .accessibilityHidden(true)
  }
}

private struct RunningTaskSymbol: UIViewRepresentable {
  let isAnimating: Bool
  let size: CGFloat

  func makeUIView(context: Context) -> RunningTaskSymbolView {
    RunningTaskSymbolView()
  }

  func updateUIView(_ view: RunningTaskSymbolView, context: Context) {
    view.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: size)
    view.shouldAnimate = isAnimating
  }

  static func dismantleUIView(_ view: RunningTaskSymbolView, coordinator: ()) {
    view.shouldAnimate = false
  }
}

private final class RunningTaskSymbolView: UIImageView {
  private static let animationKey = "task-running-rotation"
  var shouldAnimate = false { didSet { updateAnimation() } }

  init() {
    super.init(image: UIImage(systemName: "circle.dotted"))
    tintColor = .systemBlue
    contentMode = .scaleAspectFit
    isAccessibilityElement = false
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    updateAnimation()
  }

  private func updateAnimation() {
    guard shouldAnimate, window != nil else {
      layer.removeAnimation(forKey: Self.animationKey)
      return
    }
    // Workspace refreshes must not restart an existing rotation.
    guard layer.animation(forKey: Self.animationKey) == nil else { return }
    let rotation = CABasicAnimation(keyPath: "transform.rotation.z")
    rotation.fromValue = 0
    rotation.toValue = Double.pi * 2
    rotation.duration = 4
    rotation.repeatCount = .infinity
    rotation.timingFunction = CAMediaTimingFunction(name: .linear)
    rotation.preferredFrameRateRange = CAFrameRateRange(minimum: 15, maximum: 30, preferred: 30)
    layer.add(rotation, forKey: Self.animationKey)
  }
}

private struct ChatBottomVisibility: Equatable {
  let targetKey: String
  let isVisible: Bool
  let isNearBottom: Bool

  init(targetKey: String, bottom: CGFloat, visibleBottom: CGFloat) {
    self.targetKey = targetKey
    isVisible = bottom > 0 && bottom <= visibleBottom + 24
    isNearBottom = bottom > 0 && bottom < visibleBottom + 120
  }
}

private struct ChatBottomPosition: PreferenceKey {
  static let defaultValue: ChatBottomVisibility? = nil
  static func reduce(value: inout ChatBottomVisibility?, nextValue: () -> ChatBottomVisibility?) {
    value = nextValue() ?? value
  }
}

private struct ChatScrollLayout: Equatable {
  let viewportHeight: CGFloat
  let composerHeight: CGFloat
}

@MainActor private final class EarlierHistoryPosition {
  private weak var scroll: UIScrollView?
  private var sizeObservation: NSKeyValueObservation?
  private var offsetObservation: NSKeyValueObservation?
  private var releasedPull: CGPoint?
  private struct WeakAnchor { weak var view: UIView? }
  private var anchors: [String: WeakAnchor] = [:]
  private var pending: (height: CGFloat, offset: CGPoint, anchor: String?, viewportY: CGFloat?)?
  private var anchorLink: CADisplayLink?
  private var anchorDriver: HistoryAnchorFrameDriver?
  private var settleDeadline: CFTimeInterval = 0
  private var settleMaximum: CFTimeInterval = 0

  func registerAnchor(_ key: String, view: UIView) {
    anchors[key] = WeakAnchor(view: view)
  }
  func removeAnchor(_ key: String, view: UIView) {
    if anchors[key]?.view === view { anchors.removeValue(forKey: key) }
  }
  func anchorDidLayout() { restore(renewDeadline: false, recordTrace: false) }

  private func anchorRect(_ key: String, in scroll: UIScrollView) -> CGRect? {
    guard let view = anchors[key]?.view, view.window != nil,
      view.isDescendant(of: scroll), view.bounds.height > 0 else { return nil }
    return view.convert(view.bounds, to: scroll)
  }
  private func stopAnchorFrames() {
    anchorLink?.invalidate(); anchorLink = nil; anchorDriver = nil
  }
  func resolveAnchorFrame() {
    guard pending != nil else { stopAnchorFrames(); return }
    restore(renewDeadline: false, recordTrace: false)
    if CACurrentMediaTime() >= settleDeadline {
      pending = nil
      stopAnchorFrames()
      #if DEBUG
      trace(4)
      #endif
    }
  }
  #if DEBUG
  private var positionTrace: [[String: Double]] = []
  func markAttempt(_ phase: Double, loading: Bool, local: Bool) {
    trace(phase, details: ["loading": loading ? 1 : 0, "local": local ? 1 : 0])
  }
  func markPan(_ phase: Double, pull: CGFloat, maximum: CGFloat, movement: CGFloat,
    startedAtTop: Bool, canLoad: Bool, busy: Bool) {
    trace(phase, details: ["pull": Double(pull), "maximumPull": Double(maximum),
      "movement": Double(movement), "startedAtTop": startedAtTop ? 1 : 0,
      "canLoad": canLoad ? 1 : 0, "busy": busy ? 1 : 0])
  }
  private func trace(_ phase: Double, details: [String: Double] = [:]) {
    guard ProcessInfo.processInfo.arguments.contains("-history-position-probe") else { return }
    var row: [String: Double] = ["phase": phase, "time": ProcessInfo.processInfo.systemUptime,
      "content": Double(scroll?.contentSize.height ?? -1),
      "offset": Double(scroll?.contentOffset.y ?? -1), "bounds": Double(scroll?.bounds.height ?? -1),
      "top": Double(scroll?.adjustedContentInset.top ?? -1), "bottom": Double(scroll?.adjustedContentInset.bottom ?? -1),
      "pendingHeight": Double(pending?.height ?? -1), "pendingOffset": Double(pending?.offset.y ?? 0)]
    row.merge(details) { _, newest in newest }
    positionTrace.append(row)
    let file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("history-position-metrics.json")
    if let data = try? JSONSerialization.data(withJSONObject: positionTrace) { try? data.write(to: file, options: .atomic) }
  }
  #endif

  func attach(to scroll: UIScrollView) {
    guard self.scroll !== scroll else { return }
    cancel(returnToTop: false)
    self.scroll = scroll
    sizeObservation = scroll.observe(\.contentSize, options: [.new]) { [weak self] _, _ in
      MainActor.assumeIsolated { self?.restore() }
    }
    offsetObservation = scroll.observe(\.contentOffset, options: [.new]) { [weak self] _, _ in
      MainActor.assumeIsolated { self?.retainReleasedPull() }
    }
  }

  func holdReleasedPull() {
    guard let scroll else { return }
    releasedPull = scroll.contentOffset
    scroll.setContentOffset(scroll.contentOffset, animated: false)
    #if DEBUG
    trace(0)
    #endif
  }

  private func retainReleasedPull() {
    guard let scroll, let releasedPull,
      abs(scroll.contentOffset.y - releasedPull.y) > 0.25 else { return }
    scroll.setContentOffset(releasedPull, animated: false)
  }

  func prepareForPrepend() -> CGFloat {
    guard let scroll else { return 0 }
    scroll.layoutIfNeeded()
    // Retain the actual released pull position. The new prefix fills the space
    // above it instead of first snapping the old content back to the top.
    let trailingRoom = max(0, scroll.contentOffset.y + scroll.bounds.height
      - scroll.adjustedContentInset.bottom - scroll.contentSize.height)
    // Retain the visible empty room below a short list so UIKit can represent
    // the restored offset. This room is below the reading anchor: include it in
    // the baseline rather than compensating for it as if it were a new prefix.
    anchors = anchors.filter { $0.value.view != nil }
    let top = scroll.contentOffset.y + scroll.adjustedContentInset.top
    let bottom = scroll.contentOffset.y + scroll.bounds.height - scroll.adjustedContentInset.bottom
    let rows = anchors.keys.compactMap { key -> (String, CGRect)? in
      anchorRect(key, in: scroll).map { (key, $0) }
    }
    let visible = rows.filter { $0.1.maxY > top && $0.1.minY < bottom }
    let anchor = visible.min { $0.1.minY < $1.1.minY }
      ?? rows.min { abs($0.1.minY - top) < abs($1.1.minY - top) }
    pending = (scroll.contentSize.height + trailingRoom, scroll.contentOffset,
      anchor?.0, anchor.map { $0.1.minY - scroll.contentOffset.y })
    releasedPull = nil
    stopAnchorFrames()
    settleDeadline = CACurrentMediaTime() + 0.2
    settleMaximum = CACurrentMediaTime() + 0.8
    let driver = HistoryAnchorFrameDriver(self)
    anchorDriver = driver
    let link = CADisplayLink(target: driver, selector: #selector(HistoryAnchorFrameDriver.frame(_:)))
    anchorLink = link
    link.add(to: .main, forMode: .common)
    #if DEBUG
    trace(1)
    #endif
    return trailingRoom
  }

  private func restore(renewDeadline: Bool = true, recordTrace: Bool = true) {
    guard let scroll, let pending else { return }
    if renewDeadline { settleDeadline = min(settleMaximum, CACurrentMediaTime() + 0.2) }
    #if DEBUG
    if recordTrace { trace(2, details: ["anchor": pending.anchor == nil ? 0 : 1]) }
    #endif
    // Follow an existing row through every layout pass. Total content height
    // also includes rows below the viewport and is only a fallback.
    let y: CGFloat
    if let key = pending.anchor, let viewportY = pending.viewportY,
      let rect = anchorRect(key, in: scroll) {
      y = rect.minY - viewportY
    } else {
      y = pending.offset.y + scroll.contentSize.height - pending.height
    }
    if abs(scroll.contentOffset.y - y) > 0.25 {
      scroll.setContentOffset(CGPoint(x: pending.offset.x, y: y), animated: false)
    }
    #if DEBUG
    if recordTrace { trace(3) }
    #endif
  }

  func finishPull() {
    guard let scroll, releasedPull != nil else { return }
    releasedPull = nil
    // A failed read adds no prefix to fill the pull gap. Let it close normally.
    scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x,
      y: max(-scroll.adjustedContentInset.top, scroll.contentOffset.y)), animated: true)
  }

  func cancel(returnToTop: Bool = true) {
    pending = nil
    stopAnchorFrames()
    if returnToTop { finishPull() } else { releasedPull = nil }
  }
}

@MainActor private final class HistoryAnchorFrameDriver: NSObject {
  private weak var position: EarlierHistoryPosition?
  init(_ position: EarlierHistoryPosition) { self.position = position }
  @objc func frame(_ link: CADisplayLink) {
    guard let position else { link.invalidate(); return }
    position.resolveAnchorFrame()
  }
}

private struct HistoryRowAnchor: UIViewRepresentable {
  let key: String
  let position: EarlierHistoryPosition
  func makeUIView(context: Context) -> HistoryRowAnchorView { HistoryRowAnchorView() }
  func updateUIView(_ view: HistoryRowAnchorView, context: Context) {
    if let previous = view.rowKey, previous != key { view.position?.removeAnchor(previous, view: view) }
    view.rowKey = key; view.position = position
    position.registerAnchor(key, view: view)
  }
  static func dismantleUIView(_ view: HistoryRowAnchorView, coordinator: ()) {
    if let key = view.rowKey { view.position?.removeAnchor(key, view: view) }
  }
}

private final class HistoryRowAnchorView: UIView {
  var rowKey: String?
  weak var position: EarlierHistoryPosition?
  init() {
    super.init(frame: .zero)
    isHidden = true; isUserInteractionEnabled = false; isAccessibilityElement = false
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
  override var frame: CGRect { didSet { position?.anchorDidLayout() } }
  override var bounds: CGRect { didSet { position?.anchorDidLayout() } }
  override func layoutSubviews() { super.layoutSubviews(); position?.anchorDidLayout() }
}

private struct EarlierHistoryPullControl: UIViewRepresentable {
  let canLoad: Bool
  let position: EarlierHistoryPosition
  let action: @MainActor () async -> Void

  func makeUIView(context: Context) -> EarlierHistoryPullView { EarlierHistoryPullView() }

  func updateUIView(_ view: EarlierHistoryPullView, context: Context) {
    view.canLoad = canLoad
    view.position = position
    view.action = action
    view.attach()
  }

  static func dismantleUIView(_ view: EarlierHistoryPullView, coordinator: ()) { view.detach() }
}

/// Resolve anchors after SwiftUI has committed the changed message layout.
@MainActor private final class HistoryLayoutFrames: NSObject {
  private var remaining = 2
  private var displayLink: CADisplayLink?
  private var continuation: CheckedContinuation<Void, Never>?

  func wait() async {
    await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        self.continuation = continuation
        guard !Task.isCancelled else { finish(); return }
        let link = CADisplayLink(target: self, selector: #selector(frame))
        displayLink = link
        link.add(to: .main, forMode: .common)
      }
    } onCancel: {
      Task { @MainActor in self.finish() }
    }
  }

  @objc private func frame() {
    remaining -= 1
    if remaining == 0 { finish() }
  }

  private func finish() {
    displayLink?.invalidate()
    displayLink = nil
    let pending = continuation
    continuation = nil
    pending?.resume()
  }
}

/// Observe the scroll view's own pan, so text selection and drawer gestures stay native.
private final class EarlierHistoryPullView: UIView {
  var canLoad = false
  var position: EarlierHistoryPosition?
  var action: (@MainActor () async -> Void)?
  private weak var scroll: UIScrollView?
  private var startedAtTop = false
  private var maximumPull: CGFloat = 0
  private var requestedThisPull = false
  private var requestTask: Task<Void, Never>?

  init() {
    super.init(frame: .zero)
    isUserInteractionEnabled = false
    isAccessibilityElement = false
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { detach() } else { attach() }
  }

  override func layoutSubviews() { super.layoutSubviews(); attach() }

  func attach() {
    guard window != nil else { return }
    var parent = superview
    while let view = parent {
      if let candidate = view as? UIScrollView {
        guard scroll !== candidate else { return }
        detach()
        scroll = candidate
        position?.attach(to: candidate)
        candidate.panGestureRecognizer.addTarget(self, action: #selector(didPan))
        return
      }
      parent = view.superview
    }
  }

  func detach() {
    // SwiftUI can remount this zero-size attachment during a short-list refresh.
    // Keep the read alive; the store's target/generation and the view's request
    // ID discard results after a real target change.
    scroll?.panGestureRecognizer.removeTarget(self, action: #selector(didPan))
    scroll = nil
  }

  @objc private func didPan(_ pan: UIPanGestureRecognizer) {
    guard let scroll else { return }
    #if DEBUG
    if pan.state == .began { HistoryScrollProbe.shared.begin(scroll) }
    #endif
    // adjustedContentInset includes the floating toolbar and system safe area.
    // Measure actual overscroll, not finger travel or movement within history.
    let pull = -(scroll.contentOffset.y + scroll.adjustedContentInset.top)
    switch pan.state {
    case .began:
      position?.cancel(returnToTop: false)
      startedAtTop = pull >= -1
      maximumPull = max(0, pull)
      requestedThisPull = false
      #if DEBUG
      position?.markPan(-10, pull: pull, maximum: maximumPull, movement: pan.translation(in: scroll).y,
        startedAtTop: startedAtTop, canLoad: canLoad, busy: requestTask != nil)
      #endif
    case .changed:
      maximumPull = max(maximumPull, pull)
    case .ended:
      let movement = pan.translation(in: scroll)
      #if DEBUG
      position?.markPan(-11, pull: pull, maximum: maximumPull, movement: movement.y,
        startedAtTop: startedAtTop, canLoad: canLoad, busy: requestTask != nil)
      #endif
      if startedAtTop, max(maximumPull, pull) >= 60,
        movement.y > abs(movement.x) {
        requestEarlier()
      }
    default: break
    }
  }

  private func requestEarlier() {
    // Only the native pan release submits a page; the compact history row owns
    // the loading indicator without a second refresh gesture or extra inset.
    guard requestTask == nil, !requestedThisPull else { return }
    guard canLoad, let action else { return }
    requestedThisPull = true
    position?.holdReleasedPull()
    requestTask = Task { @MainActor [weak self, weak scroll] in
      defer { self?.position?.finishPull(); self?.requestTask = nil }
      guard let self, let scroll else { return }
      // UIKit's release bounce can override a programmatic anchor. Let the
      // native pan settle before expanding a cached window or requesting a page.
      await HistoryLayoutFrames().wait()
      while scroll.isTracking || scroll.isDragging || scroll.isDecelerating {
        guard !Task.isCancelled, self.window != nil, self.scroll === scroll else { return }
        do { try await Task.sleep(for: .milliseconds(16)) } catch { return }
      }
      guard !Task.isCancelled, self.window != nil, self.scroll === scroll else { return }
      await action()
    }
  }
}

#if DEBUG
/// Opt-in frame and CPU-work evidence, without conversation text or identities.
@MainActor final class HistoryScrollProbe: NSObject, ObservableObject {
  static let shared = HistoryScrollProbe()
  static let enabled = ProcessInfo.processInfo.arguments.contains("-history-scroll-probe")
  static let workEnabled = ProcessInfo.processInfo.arguments.contains("-history-work-probe")
  private static let expectedTarget: (host: String?, thread: String?) = {
    let env = ProcessInfo.processInfo.environment
    return (env["HISTORY_SCROLL_EXPECTED_HOST"], env["HISTORY_SCROLL_EXPECTED_THREAD"])
  }()
  static func matches(host: String?, thread: String?) -> Bool {
    guard let expectedHost = expectedTarget.host, let expectedThread = expectedTarget.thread else { return false }
    return expectedHost == host && expectedThread == thread
  }
  static func matchesHost(_ host: String?) -> Bool {
    expectedTarget.host != nil && expectedTarget.host == host
  }
  @Published private(set) var evidence = "{}"
  var timelineMS: [Double] = []
  var markdownBodies = 0
  private var link: CADisplayLink?
  private weak var scroll: UIScrollView?
  private var previousTimestamp: Double?
  private var previousOffset: CGFloat = 0
  private var frameMS: [Double] = []
  private var gestures = 0
  private var work: [[String: Double]] = []
  private var slowFrames: [[String: Double]] = []
  private var frames: [[String: Double]] = []
  private var gestureTimes: [[String: Double]] = []
  private let evidenceWriter = DispatchQueue(label: "history-scroll-probe-evidence", qos: .utility)

  static func workStart() -> Double? {
    guard workEnabled, shared.link != nil else { return nil }
    return CACurrentMediaTime()
  }
  static func workEnd(_ phase: Double, _ start: Double?) {
    guard let start else { return }
    let end = CACurrentMediaTime(), duration = (end - start) * 1000
    guard duration >= 1 else { return }
    shared.work.append(["phase": phase, "start": start, "end": end, "ms": duration])
    if shared.work.count > 256 { shared.work.removeFirst(128) }
  }

  func begin(_ scroll: UIScrollView) {
    guard Self.enabled else { return }
    gestures += 1
    if Self.workEnabled { gestureTimes.append(["at": CACurrentMediaTime(), "id": Double(gestures)]) }
    self.scroll = scroll
    previousTimestamp = nil
    previousOffset = scroll.contentOffset.y
    if link == nil {
      let link = CADisplayLink(target: self, selector: #selector(frame))
      // Frame-budget stress regressions explicitly request ProMotion. Power
      // comparisons must observe the system's natural refresh policy.
      if !ProcessInfo.processInfo.arguments.contains("-thermal-performance-probe") {
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
      }
      link.add(to: .main, forMode: .common)
      self.link = link
    }
  }

  @objc private func frame(_ link: CADisplayLink) {
    guard let scroll else { finish(); return }
    if let previousTimestamp, abs(scroll.contentOffset.y - previousOffset) > 0.5 {
      let interval = (link.timestamp - previousTimestamp) * 1000
      frameMS.append(interval)
      if Self.workEnabled {
        let sample = ["start": previousTimestamp, "end": link.timestamp, "ms": interval, "gesture": Double(gestures)]
        frames.append(sample)
        if interval > 40 { slowFrames.append(sample) }
      }
    }
    previousTimestamp = link.timestamp
    previousOffset = scroll.contentOffset.y
    if !scroll.isTracking && !scroll.isDragging && !scroll.isDecelerating { finish() }
  }

  private func finish() {
    link?.invalidate(); link = nil; previousTimestamp = nil
    let sorted = frameMS.sorted()
    let values: [String: Double] = ["gestures": Double(gestures), "movingFrames": Double(sorted.count),
      "p95FrameMS": sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))],
      "maxFrameMS": sorted.last ?? 0, "framesOver50MS": Double(sorted.filter { $0 > 50 }.count),
      "timelineBuilds": Double(timelineMS.count), "maxTimelineMS": timelineMS.max() ?? 0,
      "totalTimelineMS": timelineMS.reduce(0, +), "markdownBodies": Double(markdownBodies)]
    let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("history-scroll-metrics.json")
    if let data = try? JSONSerialization.data(withJSONObject: values, options: [.sortedKeys]) {
      evidence = String(decoding: data, as: UTF8.self)
      evidenceWriter.async { try? data.write(to: url, options: .atomic) }
    }
    if Self.workEnabled {
      let snapshot = ["work": work, "slowFrames": slowFrames, "frames": frames, "gestureTimes": gestureTimes,
        "clock": [["utc": Date().timeIntervalSince1970, "uptime": CACurrentMediaTime()]]]
      evidenceWriter.async {
        if let data = try? JSONSerialization.data(withJSONObject: snapshot) {
          try? data.write(to: url.deletingLastPathComponent().appendingPathComponent("history-work-metrics.json"), options: .atomic)
        }
      }
    }
  }
}

private struct HistoryScrollEvidenceView: View {
  @ObservedObject private var probe = HistoryScrollProbe.shared
  let availableMessages: Int
  let messageLimit: Int
  let targetMatches: Bool
  let hostMatches: Bool
  let threadSelected: Bool
  let selectedThreadId: String?
  var body: some View {
    ZStack(alignment: .topLeading) {
      Text(probe.evidence).font(.system(size: 1)).frame(width: 1, height: 1).clipped()
      .accessibilityIdentifier("history-scroll-evidence").allowsHitTesting(false)
      .accessibilityValue("{\"messages\":\(availableMessages),\"window\":\(messageLimit),\"target\":\(targetMatches ? 1 : 0),\"host\":\(hostMatches ? 1 : 0),\"threadSelected\":\(threadSelected ? 1 : 0),\"thermal\":\(ProcessInfo.processInfo.thermalState.rawValue)}")
      // Opt-in DEV identity read only: never persisted with numeric metrics.
      if ProcessInfo.processInfo.arguments.contains("-thermal-thread-identity-probe") {
        Text("selected-thread").font(.system(size: 1))
          .accessibilityIdentifier("thermal-selected-thread")
          .accessibilityValue(selectedThreadId ?? "none")
      }
    }.frame(width: 1, height: 1).clipped().allowsHitTesting(false)
  }
}

/// Opt-in DEV evidence only; no message content or identities are recorded.
@MainActor private enum SidebarGestureProbe {
  static let enabled = ProcessInfo.processInfo.arguments.contains("-sidebar-performance-fixture")
  private static var started: Double?
  private static var renders = 0
  private static var initialRenders = 0
  private static var positions: [(Double, CGFloat)] = []
  private static var initialPosition: CGFloat = 0
  private static var reports: [[String: Double]] = []
  static func chatRender() { if enabled { renders += 1 } }
  static func begin(position: CGFloat) {
    guard enabled else { return }
    started = ProcessInfo.processInfo.systemUptime
    initialRenders = renders
    initialPosition = position
    positions = []
  }
  static func position(_ value: CGFloat, dragging: Bool) {
    guard enabled, dragging, let started else { return }
    positions.append(((ProcessInfo.processInfo.systemUptime - started) * 1000, value))
  }
  static func finish() {
    guard enabled, let started else { return }
    reports.append(["durationMS": (ProcessInfo.processInfo.systemUptime - started) * 1000,
      "firstVisualMS": positions.first(where: { abs($0.1 - initialPosition) > 0.5 })?.0 ?? -1,
      "visualUpdates": Double(positions.count),
      "chatRenders": Double(renders - initialRenders),
      "minVisibleWidth": Double(positions.map(\.1).min() ?? 0),
      "maxVisibleWidth": Double(positions.map(\.1).max() ?? 0)])
    self.started = nil
    let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("sidebar-gesture-metrics.json")
    if let data = try? JSONSerialization.data(withJSONObject: reports, options: [.sortedKeys]) {
      try? data.write(to: url, options: .atomic)
    }
  }
}
#endif

private struct SidebarPullRefresh: UIViewRepresentable {
  let targetKey: String
  let action: @MainActor () async -> Void
  func makeUIView(context: Context) -> SidebarPullRefreshView { SidebarPullRefreshView() }
  func updateUIView(_ view: SidebarPullRefreshView, context: Context) {
    if view.targetKey != targetKey { view.cancel(); view.targetKey = targetKey }
    view.action = action
    view.attach()
  }
  static func dismantleUIView(_ view: SidebarPullRefreshView, coordinator: ()) {
    // A hosting update can replace this observer while the logical read is
    // pending. The request survives that view lifetime, just as drawer closure does.
    view.detach()
  }
}

#if DEBUG
private struct SidebarRefreshEvidence: ViewModifier {
  let refreshing: Bool
  func body(content: Content) -> some View {
    if ProcessInfo.processInfo.arguments.contains("-sidebar-refresh-hold-fixture") {
      content.accessibilityValue(refreshing ? "refreshing" : "idle")
    } else {
      content
    }
  }
}
#endif

/// Observe the list's native pan without adding a refresh inset. SwiftUI's
/// refreshable inset can move this hosted sidebar's pixels without its hit regions.
private final class SidebarPullRefreshView: UIView {
  var targetKey = ""
  var action: (@MainActor () async -> Void)?
  private weak var scroll: UIScrollView?
  private var startedAtTop = false
  private var maximumPull: CGFloat = 0
  private var request: Task<Void, Never>?
  private var requestToken: UUID?
  init() {
    super.init(frame: .zero)
    isUserInteractionEnabled = false
    isAccessibilityElement = false
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { detach() } else { attach() }
  }
  override func layoutSubviews() { super.layoutSubviews(); attach() }
  func attach() {
    guard window != nil else { return }
    var parent = superview
    while let view = parent {
      if let candidate = view as? UIScrollView {
        guard scroll !== candidate else { return }
        detach()
        scroll = candidate
        candidate.panGestureRecognizer.addTarget(self, action: #selector(panned(_:)))
        return
      }
      parent = view.superview
    }
  }
  func detach() {
    scroll?.panGestureRecognizer.removeTarget(self, action: #selector(panned(_:)))
    scroll = nil
    startedAtTop = false
  }
  func cancel() { request?.cancel(); request = nil; requestToken = nil }
  @objc private func panned(_ pan: UIPanGestureRecognizer) {
    guard let scroll else { return }
    let pull = max(0, -scroll.adjustedContentInset.top - scroll.contentOffset.y)
    switch pan.state {
    case .began:
      startedAtTop = scroll.contentOffset.y <= -scroll.adjustedContentInset.top + 1
      maximumPull = pull
    case .changed:
      maximumPull = max(maximumPull, pull)
    case .ended:
      let movement = pan.translation(in: scroll)
      guard startedAtTop, max(maximumPull, pull) >= 60,
        movement.y > abs(movement.x), request == nil, let action else { return }
      let token = UUID()
      requestToken = token
      request = Task { @MainActor [weak self] in
        defer { if self?.requestToken == token { self?.request = nil; self?.requestToken = nil } }
        guard !Task.isCancelled else { return }
        await action()
      }
    default: break
    }
  }
}

private final class SidebarPanGestureView: UIView, UIGestureRecognizerDelegate {
  var onBegin: (() -> Void)?
  var onChange: ((CGFloat) -> Void)?
  var onEnd: ((CGFloat, CGFloat, Bool) -> Void)?
  private weak var gestureWindow: UIWindow?
  private var acceptedTouch = false
  private var touchOrigin = CGPoint.zero
  lazy var pan: UIPanGestureRecognizer = {
    let gesture = UIPanGestureRecognizer(target: self, action: #selector(didPan))
    gesture.maximumNumberOfTouches = 1
    gesture.delegate = self
    return gesture
  }()

  override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
    let movement = pan.translation(in: self)
    // Capture reverse horizontal drags too, so they cannot become row taps.
    return abs(movement.x) > abs(movement.y) * 1.25
  }

  init() {
    super.init(frame: .zero)
    isUserInteractionEnabled = false
    isAccessibilityElement = false
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    detach()
    gestureWindow = window
    // Observe touches without placing a hit-testing layer over the SwiftUI controls.
    window?.addGestureRecognizer(pan)
  }

  func detach() {
    gestureWindow?.removeGestureRecognizer(pan)
    gestureWindow = nil
    acceptedTouch = false
  }

  @objc private func didPan() {
    // UIPan resets its translation when recognition begins. Use the original
    // touch point so the first recognized movement is already visible.
    let translation = pan.location(in: gestureWindow).x - touchOrigin.x
    switch pan.state {
    case .began:
      onBegin?()
      dismissKeyboard()
      onChange?(translation)
    case .changed: onChange?(translation)
    case .ended:
      #if DEBUG
      SidebarGestureProbe.finish()
      #endif
      onEnd?(translation, pan.velocity(in: self).x, false)
      dismissKeyboard()
    case .cancelled, .failed:
      #if DEBUG
      SidebarGestureProbe.finish()
      #endif
      onEnd?(translation, 0, true)
    default: break
    }
  }

  private func dismissKeyboard() {
    window?.endEditing(true)
    // SwiftUI may restore the covered input's responder when removing the drawer.
    DispatchQueue.main.async { [weak self] in self?.window?.endEditing(true) }
  }

  func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
    acceptedTouch = false
    guard bounds.contains(touch.location(in: self)),
      window?.rootViewController?.presentedViewController == nil else { return false }
    var touchedView = touch.view
    while let view = touchedView {
      // Text editing, sliders and horizontally scrolling code/tables keep their gestures.
      if view is UITextField || view is UITextView || view is UISlider
        || view is UISwitch || view is UIStepper || view is UISegmentedControl { return false }
      if let scroll = view as? UIScrollView, scroll.isScrollEnabled,
        scroll.alwaysBounceHorizontal || scroll.contentSize.width > scroll.bounds.width + 1 {
        return false
      }
      touchedView = view.superview
    }
    acceptedTouch = true
    touchOrigin = touch.location(in: window)
    return true
  }

  func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer,
    shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
  ) -> Bool { otherGestureRecognizer is UIPanGestureRecognizer }

  func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer,
    shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
  ) -> Bool {
    // A horizontal drag cancels row taps; a vertical drag fails immediately.
    acceptedTouch && otherGestureRecognizer !== pan
      && !(otherGestureRecognizer is UIPanGestureRecognizer)
  }
}

/// The mounted panel moves as one UIKit layer; dragging never changes SwiftUI state.
private final class SidebarMotionState: Sendable {
  private let active = OSAllocatedUnfairLock(initialState: false)
  var interacting: Bool { active.withLock { $0 } }
  func setInteracting(_ value: Bool) { active.withLock { $0 = value } }
}

@MainActor private final class SidebarDrawerCommands: ObservableObject {
  weak var controller: SidebarDrawerController?
  let motionState = SidebarMotionState()
  func setOpen(_ open: Bool, fallback: () -> Void) {
    if let controller { controller.requestOpen(open) } else { fallback() }
  }
  func setInteracting(_ value: Bool) {
    guard motionState.interacting != value else { return }
    motionState.setInteracting(value)
    // Catch up after a cancelled/partial drag too, even if drawerOpen did not change.
    if !value { DispatchQueue.main.async { [weak self] in self?.objectWillChange.send() } }
  }
}

/// Background refreshes may publish during a drag; defer the expensive chat diff until it settles.
private struct SidebarBackground<Content: View>: View, Equatable {
  let motionState: SidebarMotionState
  @ViewBuilder var content: () -> Content
  nonisolated static func == (lhs: Self, rhs: Self) -> Bool { rhs.motionState.interacting }
  var body: some View { content() }
}

private struct SidebarDrawer<Content: View>: UIViewControllerRepresentable {
  let isOpen: Bool
  let isEnabled: Bool
  let width: CGFloat
  let content: @MainActor () -> Content
  let commands: SidebarDrawerCommands
  let onBegin: () -> Void
  let onSettle: (Bool) -> Void
  let onInteractionEnd: () -> Void
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  func makeUIViewController(context: Context) -> SidebarDrawerController {
    let controller = SidebarDrawerController(content: { AnyView(content()) }, width: width, isOpen: isOpen)
    commands.controller = controller
    return controller
  }

  func updateUIViewController(_ controller: SidebarDrawerController, context: Context) {
    controller.update(content: { AnyView(content()) }, width: width, isOpen: isOpen,
      isEnabled: isEnabled, reduceMotion: reduceMotion, onBegin: {
        commands.setInteracting(true)
        onBegin()
      }, onSettle: onSettle, onInteractionEnd: {
        commands.setInteracting(false)
        onInteractionEnd()
      })
  }

  static func dismantleUIViewController(_ controller: SidebarDrawerController, coordinator: ()) {
    controller.detach()
  }
}

private final class SidebarContainerView: UIView {
  var capturesTouches = false
  override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
    capturesTouches ? super.hitTest(point, with: event) : nil
  }
}

private final class SidebarDrawerController: UIViewController {
  private let panel: UIHostingController<AnyView>
  private let panelSurface = UIView()
  private let panelBackground = UIView()
  private let backdrop = UIControl()
  private let gestures = SidebarPanGestureView()
  private var latestContent: @MainActor () -> AnyView
  private var contentPending = false
  private var panelWidth: CGFloat
  private var position: CGFloat
  private var targetOpen: Bool
  private var externalOpen: Bool
  private var pendingSettlement: Bool?
  private var dragging = false
  private var interacting = false
  private var startedOpen = false
  private var dragOrigin: CGFloat = 0
  private var reduceMotion = false
  private var animator: UIViewPropertyAnimator?
  private var onBegin: (() -> Void)?
  private var onSettle: ((Bool) -> Void)?
  private var onInteractionEnd: (() -> Void)?
  #if DEBUG
  private let animationProbe = SidebarAnimationProbe()
  #endif

  init(content: @escaping @MainActor () -> AnyView, width: CGFloat, isOpen: Bool) {
    panel = UIHostingController(rootView: content())
    latestContent = content
    panelWidth = width
    targetOpen = isOpen
    externalOpen = isOpen
    position = isOpen ? width : 0
    super.init(nibName: nil, bundle: nil)
    panel.safeAreaRegions = []
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

  override func loadView() { view = SidebarContainerView() }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .clear
    backdrop.backgroundColor = .black
    backdrop.isAccessibilityElement = false
    backdrop.addTarget(self, action: #selector(close), for: .touchUpInside)
    view.addSubview(backdrop)
    addChild(panel)
    view.addSubview(panelSurface)
    panelSurface.addSubview(panelBackground)
    panelSurface.addSubview(panel.view)
    panel.didMove(toParent: self)
    panel.view.backgroundColor = .clear
    panelSurface.backgroundColor = .secondarySystemBackground
    panelBackground.backgroundColor = .secondarySystemBackground
    panelBackground.isUserInteractionEnabled = false
    panelSurface.isHidden = !targetOpen
    panelSurface.accessibilityViewIsModal = targetOpen
    view.addSubview(gestures)
    gestures.onBegin = { [weak self] in self?.beginDrag() }
    gestures.onChange = { [weak self] translation in self?.drag(translation) }
    gestures.onEnd = { [weak self] translation, velocity, cancelled in
      self?.endDrag(translation: translation, velocity: velocity, cancelled: cancelled)
    }
    applyPosition(position)
    updateInteraction()
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    // Moving the panel does not change its bounds or its list's layout viewport.
    panelSurface.bounds = CGRect(x: 0, y: 0, width: panelWidth, height: view.bounds.height)
    panelSurface.center = CGPoint(x: panelWidth / 2, y: view.bounds.height / 2)
    panel.view.frame = panelSurface.bounds
    let insets = view.window?.safeAreaInsets ?? .zero
    panelBackground.frame = CGRect(x: 0, y: -insets.top, width: panelWidth,
      height: view.bounds.height + insets.top + insets.bottom)
    backdrop.frame = view.bounds.inset(by: UIEdgeInsets(top: -(view.window?.safeAreaInsets.top ?? 0),
      left: 0, bottom: -(view.window?.safeAreaInsets.bottom ?? 0), right: 0))
    gestures.frame = view.bounds
  }

  func update(content: @escaping @MainActor () -> AnyView, width: CGFloat, isOpen: Bool, isEnabled: Bool,
    reduceMotion: Bool, onBegin: @escaping () -> Void, onSettle: @escaping (Bool) -> Void,
    onInteractionEnd: @escaping () -> Void) {
    self.onBegin = onBegin
    self.onSettle = onSettle
    self.onInteractionEnd = onInteractionEnd
    self.reduceMotion = reduceMotion
    // A hidden hosting tree must not rebuild for every chat scroll/geometry
    // update. Keep the newest builder, then apply it before opening the panel.
    latestContent = content
    contentPending = true
    loadViewIfNeeded()
    let externalChanged = externalOpen != isOpen
    externalOpen = isOpen
    if !isEnabled {
      dragging = false
      gestures.pan.isEnabled = false
      pendingSettlement = nil
      settle(open: isOpen, animated: false)
      return
    }
    gestures.pan.isEnabled = isEnabled
    if panelWidth != width {
      freezeAnimation()
      let fraction = panelWidth > 0 ? position / panelWidth : 0
      let dragFraction = panelWidth > 0 ? dragOrigin / panelWidth : 0
      panelWidth = width
      dragOrigin = width * dragFraction
      applyPosition(width * fraction)
      view.setNeedsLayout()
      if !dragging { settle(open: isOpen, animated: false) }
    } else if !dragging && externalChanged && targetOpen != isOpen {
      pendingSettlement = nil
      settle(open: isOpen, animated: isEnabled && !reduceMotion)
    } else if !interacting && (targetOpen || externalChanged) {
      refreshContent()
    }
  }

  private func refreshContent() {
    guard contentPending else { return }
    contentPending = false
    panel.rootView = latestContent()
  }

  func requestOpen(_ open: Bool) {
    pendingSettlement = open != externalOpen ? open : nil
    settle(open: open, animated: !reduceMotion)
  }

  private func beginInteraction() {
    guard !interacting else { return }
    interacting = true
    onBegin?()
  }

  private func beginDrag() {
    freezeAnimation()
    refreshContent()
    startedOpen = targetOpen
    dragOrigin = position
    dragging = true
    beginInteraction()
    panelSurface.isHidden = false
    updateInteraction()
    #if DEBUG
    SidebarGestureProbe.begin(position: position)
    #endif
  }

  private func drag(_ translation: CGFloat) {
    guard dragging else { return }
    applyPosition(min(panelWidth, max(0, dragOrigin + translation)))
    #if DEBUG
    SidebarGestureProbe.position(position, dragging: true)
    #endif
  }

  private func endDrag(translation: CGFloat, velocity: CGFloat, cancelled: Bool) {
    guard dragging else { return }
    dragging = false
    let distance = startedOpen ? -translation : translation
    let speed = startedOpen ? -velocity : velocity
    let commit = !cancelled && (distance > panelWidth * 0.25 || (distance > 24 && speed > 650))
    let open = commit ? !startedOpen : startedOpen
    requestOpen(open)
  }

  @objc private func close() {
    guard targetOpen else { return }
    requestOpen(false)
  }

  private func settle(open: Bool, animated: Bool) {
    freezeAnimation()
    if open { refreshContent() }
    targetOpen = open
    let destination: CGFloat = open ? panelWidth : 0
    guard animated, abs(destination - position) > 0.5, view.window != nil else {
      applyPosition(destination)
      finishInteraction()
      return
    }
    beginInteraction()
    panelSurface.isHidden = false
    updateInteraction()
    // One transform animates from the current position; no insertion/removal transition.
    let motion = UIViewPropertyAnimator(duration: 0.22, curve: .easeOut)
    animator = motion
    motion.addAnimations { [weak self] in self?.applyPosition(destination) }
    motion.addCompletion { [weak self, weak motion] _ in
      guard let self, self.animator === motion else { return }
      self.animator = nil
      self.applyPosition(destination)
      self.finishInteraction()
    }
    #if DEBUG
    animationProbe.begin(panel: panelSurface, width: panelWidth, from: position, to: destination)
    #endif
    motion.startAnimation()
  }

  private func freezeAnimation() {
    guard let animator else { return }
    let current = (panelSurface.layer.presentation()?.affineTransform().tx ?? panelSurface.transform.tx)
      + panelWidth
    self.animator = nil
    animator.stopAnimation(true)
    applyPosition(min(panelWidth, max(0, current)))
    #if DEBUG
    animationProbe.finish(interrupted: true)
    #endif
  }

  private func applyPosition(_ value: CGFloat) {
    position = value
    panelSurface.transform = CGAffineTransform(translationX: value - panelWidth, y: 0)
    backdrop.alpha = panelWidth > 0 ? 0.22 * value / panelWidth : 0
  }

  private func updateInteraction() {
    (view as? SidebarContainerView)?.capturesTouches = targetOpen || interacting
    panelSurface.isUserInteractionEnabled = targetOpen && !interacting
    panelSurface.accessibilityElementsHidden = !targetOpen || interacting
    panelSurface.accessibilityViewIsModal = targetOpen && !interacting
    backdrop.isUserInteractionEnabled = targetOpen && !interacting
  }

  private func finishInteraction() {
    #if DEBUG
    animationProbe.finish(interrupted: false)
    #endif
    panelSurface.isHidden = !targetOpen
    let wasInteracting = interacting
    interacting = false
    updateInteraction()
    // Content/workspace updates resume after the visual motion has finished.
    // Apply closure state once at settlement as well: running indicators must
    // receive drawerOpen=false and pause after the panel becomes hidden.
    refreshContent()
    let settled = pendingSettlement
    pendingSettlement = nil
    if let settled { onSettle?(settled) }
    if wasInteracting { onInteractionEnd?() }
  }

  func detach() {
    freezeAnimation()
    gestures.detach()
    if interacting { interacting = false; onInteractionEnd?() }
  }
}

#if DEBUG
/// Opt-in presentation-layer samples. These are not touch-to-photon measurements.
@MainActor private final class SidebarAnimationProbe: NSObject {
  private weak var panel: UIView?
  private var displayLink: CADisplayLink?
  private var started: Double = 0
  private var width: CGFloat = 0
  private var from: CGFloat = 0
  private var to: CGFloat = 0
  private var samples: [[String: Double]] = []
  private var reports: [[String: Any]] = []

  func begin(panel: UIView, width: CGFloat, from: CGFloat, to: CGFloat) {
    guard SidebarGestureProbe.enabled else { return }
    self.panel = panel
    self.width = width
    self.from = from
    self.to = to
    started = ProcessInfo.processInfo.systemUptime
    samples = []
    let link = CADisplayLink(target: self, selector: #selector(sample))
    link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 60, preferred: 60)
    displayLink = link
    link.add(to: .main, forMode: .common)
  }

  @objc private func sample() {
    guard let panel else { return }
    let value = (panel.layer.presentation()?.affineTransform().tx ?? panel.transform.tx) + width
    samples.append(["elapsedMS": (ProcessInfo.processInfo.systemUptime - started) * 1000,
      "visibleWidth": Double(value), "opacity": Double(panel.layer.presentation()?.opacity ?? panel.layer.opacity)])
  }

  func finish(interrupted: Bool) {
    guard let displayLink else { return }
    displayLink.invalidate()
    self.displayLink = nil
    reports.append(["durationMS": (ProcessInfo.processInfo.systemUptime - started) * 1000,
      "from": Double(from), "to": Double(to), "interrupted": interrupted, "frames": samples])
    panel = nil
    let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("sidebar-animation-metrics.json")
    if let data = try? JSONSerialization.data(withJSONObject: reports, options: [.sortedKeys]) {
      try? data.write(to: url, options: .atomic)
    }
  }
}
#endif

struct RootView: View {
  private static var initialHistoryWindow: Int {
    #if DEBUG
    if ProcessInfo.processInfo.arguments.contains("-history-short-paging-fixture") { return 4 }
    #endif
    return 20
  }
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @AppStorage(VoiceTranscriptionPreference.storageKey) private var voicePreference = VoiceTranscriptionPreference.desktop
  @StateObject private var store = SessionStore()
  @StateObject private var taskHub = TaskHubStore()
  @StateObject private var voice = VoiceInputController()
  @StateObject private var sidebarMotion = SidebarDrawerCommands()
  @ObservedObject private var notifications = TaskNotificationController.shared
  @ObservedObject private var businessNotifications = TaskHubBusinessNotifications.shared
  @State private var showComposerActions = false
  @State private var attachmentModalPresented = false
  @State private var voiceDEVPairingApplied = false
  @State private var followsLatest = true
  @State private var latestMessageVisibility: ChatBottomVisibility?
  @State private var visibleMessageLimit = RootView.initialHistoryWindow
  @State private var timelineCache = ChatTimelineCache()
  @State private var readySearchJump: UUID?
  @State private var deleteChatTarget: Conversation?
  @State private var endChatActionTarget: ChatActionReceipt?
  @State private var showsReplyRecords = false
  @State private var earlierHistoryRequest: UUID?
  @State private var earlierHistoryPosition = EarlierHistoryPosition()
  @State private var earlierHistoryBottomSlack: CGFloat = 0
  #if DEBUG
  @State private var earlierLayoutFixtureSlack: CGFloat = 0
  #endif
  @State private var revealedEarlierHistory = false
  @State private var composerHeight: CGFloat = 104
  @State private var userScrolling = false
  @State private var sidebarSearchVisible = false
  @State private var sidebarRefreshRequest: UUID?
  @State private var expandedRecentClients: Set<String> = []
  @State private var expandedProjectLists: Set<String> = []
  @State private var collapsedProjectSections: Set<String> = []
  @AppStorage("sidebar-collapsed-recent-clients") private var collapsedRecentClients = "[]"
  @AppStorage("sidebar-collapsed-pinned-clients") private var collapsedPinnedClients = "[]"
  @FocusState private var composerFocused: Bool
  @FocusState private var sidebarSearchFocused: Bool
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    GeometryReader { geometry in
      ZStack(alignment: .leading) {
        SidebarBackground(motionState: sidebarMotion.motionState) {
        chatBody.frame(maxWidth: .infinity, maxHeight: .infinity)
          .overlay(alignment: .top) { toolbar }
          .overlay(alignment: .bottom) {
            if store.client != nil {
              composer.background {
                GeometryReader { size in
                  Color.clear.preference(key: ComposerHeightPreference.self, value: size.size.height)
                }
              }
            }
          }
          .onPreferenceChange(ComposerHeightPreference.self) { height in
            if abs(composerHeight - height) > 0.5 { composerHeight = height }
          }
        .background(Palette.background)
        }.equatable()
        .accessibilityHidden(store.drawerOpen).allowsHitTesting(!store.drawerOpen)
        SidebarDrawer(isOpen: store.drawerOpen,
          isEnabled: scenePhase == .active && store.sheet == nil && !attachmentModalPresented,
          width: max(0, min(geometry.size.width - 44, 350)), content: { sidebar }, commands: sidebarMotion,
          onBegin: {
            store.sidebarInteracting = true
            composerFocused = false
            sidebarSearchFocused = false
            if voice.isActive { voice.cancel() }
          }, onSettle: { open in
            store.drawerOpen = open
          }, onInteractionEnd: { store.sidebarInteracting = false })
      }
    }
    .overlay {
      if store.localRecoveryRequired {
        VStack(spacing: 20) {
          Image(systemName: "externaldrive.badge.exclamationmark").font(.largeTitle)
          Text(L10n.tr("本地数据暂时无法读取")).font(.headline)
          Text(L10n.tr("原有连接、草稿和聊天已保留。请解锁设备后重试，恢复前不会保存空数据。"))
            .multilineTextAlignment(.center)
          Button(L10n.tr("重试读取")) { store.retryLocalRecovery() }
            .buttonStyle(.borderedProminent).accessibilityIdentifier("retry-local-recovery")
        }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity)
          .background(Palette.background).accessibilityIdentifier("local-recovery-required")
      }
    }
    .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
      if store.localRecoveryRequired { store.retryLocalRecovery() }
    }
    .preferredColorScheme(store.colorScheme)
    .task {
      TaskNotificationController.shared.attach(store)
      #if DEBUG
      if !voiceDEVPairingApplied, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
        ProcessInfo.processInfo.arguments.contains("-voice-open-settings") {
        voiceDEVPairingApplied = true
        UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
      }
      if !voiceDEVPairingApplied, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
        ProcessInfo.processInfo.arguments.contains("-voice-pairing-fixture"),
        let payload = ProcessInfo.processInfo.environment["VOICE_PAIRING_URI"],
        let url = URL(string: payload), url.scheme == "codex-host", url.host == "pair" {
        voiceDEVPairingApplied = true
        AgentConfigurationViews.open(url, store: store)
      }
      #endif
    }
    .task(id: scenePhase == .active) {
      guard scenePhase == .active else { return }
      await EasyWorkKnownHosts.shared.run(store)
    }
    .task(id: scenePhase == .active && voicePreference == .desktop ? store.voiceReadinessScope : nil) {
      guard scenePhase == .active, voicePreference == .desktop else { return }
      await store.monitorVoiceReadiness()
    }
    .task(id: scenePhase == .active) {
      guard scenePhase == .active else { return }
      await CodexHostDiscovery.shared.run(store)
    }
    .task(id: scenePhase == .active) {
      guard scenePhase == .active else { return }
      await VPNHostDiscovery.shared.run(store)
    }
    .onChange(of: scenePhase) { _, phase in
      if phase != .active { store.sidebarInteracting = false; taskHub.stop() }
      else { Task { await taskHub.refresh() } }
      TaskNotificationController.shared.setActive(phase == .active)
      if phase != .active { EasyWorkKnownHosts.shared.stop(); CodexHostDiscovery.shared.stop(); VPNHostDiscovery.shared.stop() }
    }
    .onChange(of: businessNotifications.pendingNavigation?.id) { _, eventId in
      if eventId != nil { store.sheet = .taskHub }
    }
    .tint(.primary)
    .environmentObject(voice)
    .modifier(ExternalAttachmentIntake(store: store, modalPresented: attachmentModalPresented))
      .confirmationDialog(L10n.tr("结束等待？"), isPresented: Binding(get: { endChatActionTarget != nil }, set: { if !$0 { endChatActionTarget = nil } }), titleVisibility: .visible) {
        Button(L10n.tr("结束等待")) { if let receipt = endChatActionTarget { store.endChatActionWaiting(receipt) }; endChatActionTarget = nil }
        Button(L10n.tr("取消"), role: .cancel) { endChatActionTarget = nil }
      } message: { Text(L10n.tr("仅解除本机等待，原操作仍可查询，不会取消电脑端操作或重新提交。")) }
      .confirmationDialog(L10n.tr("删除此对话？"), isPresented: Binding(get: { deleteChatTarget != nil }, set: { if !$0 { deleteChatTarget = nil } }), titleVisibility: .visible) {
        Button(L10n.tr("删除"), role: .destructive) {
          let target = deleteChatTarget; deleteChatTarget = nil
          if target?.id == store.selectedThreadId { store.manageChat(.delete, thread: target) }
        }.accessibilityIdentifier("chat-delete-confirm")
        Button(L10n.tr("取消"), role: .cancel) { deleteChatTarget = nil }
      } message: {
        Text((deleteChatTarget.map { target in store.chatLifecycleStatus(store.threads.first { $0.id == target.id && $0.hostId == target.hostId } ?? target) + "\n\n" } ?? "")
          + L10n.tr("将同步删除电脑端的原始聊天及其子对话，无法恢复。仅想保留历史时请选择归档。"))
      }
      .sheet(item: $store.sheet, onDismiss: {
        if let jump = store.chatSearchJump, jump.targetKey == store.targetKey, jump.token != readySearchJump { readySearchJump = jump.token }
      }) { route in sheets(route).presentationDragIndicator(.visible) }
    .alert(store.errorTitle, isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
      Button(L10n.tr("好"), role: .cancel) { store.error = nil }
    } message: {
      Text(store.error ?? "")
    }
    .task {
      await store.recoverLocalAttachmentImports()
      if store.client?.isDemo == false { await store.refresh() }
    }
    .task(id: scenePhase == .active) {
      guard scenePhase == .active else { return }
      while !Task.isCancelled {
        await store.refreshMobileUnread()
        do { try await Task.sleep(for: .seconds(10)) } catch { return }
      }
    }
    .task(id: sidebarRefreshKey) {
      guard store.drawerOpen, scenePhase == .active, store.client?.isDemo == false,
        store.client?.online == true else { return }
      // Let the opening/settling animation finish before applying a workspace response.
      do { try await Task.sleep(for: .milliseconds(220)) } catch { return }
      while !Task.isCancelled {
        if !store.loading { await store.refresh(includeHistory: false) }
        do { try await Task.sleep(for: .seconds(3)) } catch { return }
      }
    }
    .onChange(of: scenePhase) { _, phase in
      if phase == .active {
        if store.localRecoveryRequired { store.retryLocalRecovery() }
        store.prepareForegroundConnections()
        if store.client?.isDemo == false { Task { await store.refresh() } }
      }
      if phase == .background || (phase == .inactive && voice.phase != .requesting) { voice.suspend() }
      if phase != .active { store.suspendEvents(); store.persistForBackground() }
    }
    .onReceive(NotificationCenter.default.publisher(for: Notification.Name("CodexNetworkPathChanged"))) { _ in
      if scenePhase == .active { store.recoverNetworkReads() }
    }
    .onChange(of: store.targetKey) { _, _ in
      voice.cancel()
      store.chatSearchJump = nil
      readySearchJump = nil
      if store.sheet == .chatSearch { store.sheet = nil }
      deleteChatTarget = nil
      endChatActionTarget = nil
      showsReplyRecords = false
      latestMessageVisibility = nil
      visibleMessageLimit = RootView.initialHistoryWindow
      earlierHistoryRequest = nil
      earlierHistoryPosition.cancel()
      earlierHistoryBottomSlack = 0
      revealedEarlierHistory = false
      followsLatest = true
      userScrolling = false
    }
    .task(id: store.modelLoadingKey) { if store.modelLoadingKey != nil { await store.loadModels() } }
    .onChange(of: store.drawerOpen) { _, open in if open { voice.cancel() } }
    .onChange(of: sidebarClientKey) { _, _ in sidebarRefreshRequest = nil }
    .onChange(of: store.sheet) { _, sheet in if sheet != nil { voice.cancel() } }
    .onChange(of: voice.isActive && scenePhase == .active, initial: true) { _, keepAwake in
      // Keep the live transcript visible throughout setup, capture and finalization.
      UIApplication.shared.isIdleTimerDisabled = keepAwake
    }
    .onDisappear {
      store.sidebarInteracting = false
      voice.cancel()
      UIApplication.shared.isIdleTimerDisabled = false
    }
  }

  private var toolbar: some View {
    HStack(spacing: 8) {
      IconButton(symbol: "line.3.horizontal", label: L10n.tr("打开项目和对话"), id: "open-sidebar", action: openSidebar)
        .floatingSurface()
      Spacer(minLength: 0)
      clientSelector.layoutPriority(-1)
      Spacer(minLength: 0)
      chatActions
    }.padding(.horizontal, 12).padding(.top, 1).padding(.bottom, 9)
  }

  private func openSidebar() {
    sidebarMotion.setOpen(true) { store.drawerOpen = true }
  }

  private func closeSidebar() {
    sidebarMotion.setOpen(false) { store.drawerOpen = false }
  }

  private var chatActions: some View {
      HStack(spacing: 0) {
        if composerFocused {
          IconButton(symbol: "keyboard.chevron.compact.down", label: L10n.tr("收起键盘"), id: "composer-dismiss-keyboard") {
            composerFocused = false
          }
        }
        IconButton(symbol: "square.and.pencil", label: L10n.tr("新建对话"), id: "new-chat") {
          store.sheet = .projects
        }
        .disabled(!store.canCreateThread || store.client?.online != true)
        IconButton(symbol: "checklist", label: L10n.tr("项目与任务"), id: "task-hub-entry") { store.sheet = .taskHub }
        Menu {
          if let conversation = store.conversation {
            Section {
              Text(store.chatLifecycleStatus(conversation)).accessibilityIdentifier("chat-lifecycle-status")
            }
            Section {
              if store.supportsChatManagement {
                Button(store.currentChatPinned ? L10n.tr("取消置顶") : L10n.tr("置顶"), systemImage: store.currentChatPinned ? "pin.slash" : "pin") {
                  store.manageChat(store.currentChatPinned ? .unpin : .pin, thread: conversation)
                }.disabled(!store.canManageChat(conversation) || conversation.archived == true).accessibilityIdentifier("chat-pin")
              }
              Button(L10n.tr("在聊天中查找"), systemImage: "magnifyingglass") { composerFocused = false; store.sheet = .chatSearch }
                .accessibilityIdentifier("chat-search")
              if store.supportsChatManagement {
                Button(conversation.archived == true ? L10n.tr("恢复对话") : L10n.tr("归档"), systemImage: "archivebox") {
                  store.manageChat(conversation.archived == true ? .unarchive : .archive, thread: conversation)
                }.disabled(!store.canManageChat(conversation)).accessibilityIdentifier("chat-archive")
                Button(L10n.tr("删除"), systemImage: "trash", role: .destructive) { deleteChatTarget = conversation }
                  .disabled(!store.canManageChat(conversation)).accessibilityIdentifier("chat-delete")
              }
            }
          }
          if store.supportsChatManagement {
            Button(L10n.tr("已归档对话"), systemImage: "archivebox") { store.sheet = .archivedChats }.accessibilityIdentifier("archived-chats")
          }
          ForEach(store.pendingChatActions()) { receipt in
            Button { store.queryChatAction(receipt) } label: {
              Label {
                VStack(alignment: .leading) {
                  Text(store.chatActionRecordLabel(receipt))
                  Text(L10n.tr("查询聊天操作记录")).font(.caption)
                }
              } icon: { Image(systemName: "arrow.clockwise") }
            }
              .accessibilityIdentifier("chat-action-query")
              .accessibilityLabel(L10n.tr("查询聊天操作记录") + "，" + store.chatActionRecordLabel(receipt))
              .accessibilityValue(receipt.threadId + " · " + receipt.id)
            if receipt.waitingEnded != true {
              Button(L10n.tr("结束等待") + " · " + store.chatActionRecordLabel(receipt)) { endChatActionTarget = receipt }
            }
          }
          if store.client?.isDemo == false {
            Button(L10n.tr("刷新历史"), systemImage: "arrow.clockwise") {
              Task { await store.refresh(userInitiated: true) }
            }
          }
          if store.supportsGenerationSettings {
            Button(L10n.tr("模型、推理与权限"), systemImage: "slider.horizontal.3") {
              store.sheet = .generation
            }
          }
          if !store.detachedReplies(for: store.targetKey).isEmpty {
            Button(L10n.tr("发送记录"), systemImage: "clock.arrow.circlepath") { showsReplyRecords = true }
              .accessibilityIdentifier("reply-delivery-records")
          }
          if !store.detachedDesktopCreations.isEmpty {
            Section(L10n.tr("新建记录")) {
              ForEach(Array(store.detachedDesktopCreations.suffix(3)), id: \.clientOperationId) { receipt in
                Button(receipt.phase == "confirmed" ? L10n.tr("查询记录 · 已确认") : receipt.phase == "rejected" ? L10n.tr("查询记录 · 未提交") : L10n.tr("查询记录 · 待确认")) {
                  store.queryDetachedCreation(receipt, target: store.targetKey)
                }.accessibilityIdentifier("detached-creation-receipt")
              }
            }
          }
          Button(L10n.tr("设置"), systemImage: "gearshape") { store.sheet = .settings }
        } label: {
          Image(systemName: "ellipsis").frame(width: 44, height: 44)
        }
        .id(store.targetKey)
        .accessibilityLabel(L10n.tr("对话选项")).accessibilityIdentifier("chat-menu")
        .accessibilityValue(store.currentChatActionBusy || store.loading ? L10n.tr("正在更新…") : "")
      }.floatingSurface()
        .sheet(isPresented: $showsReplyRecords) { ReplyDeliveryRecordsView(store: store, target: store.targetKey) }
  }

  private var clientSelector: some View {
      Button {
        composerFocused = false
        store.sheet = .clients
      } label: {
        HStack(spacing: 5) {
          if let client = store.client, store.isConnecting(client) {
            ProgressView().controlSize(.mini).accessibilityLabel(store.connectionLabel(for: client))
          } else {
            Circle().fill(store.client?.online == true ? Color.green : Color.gray).frame(width: 6, height: 6)
          }
          VStack(alignment: .leading, spacing: 1) {
            Text(store.client?.name ?? L10n.tr("添加电脑")).font(.caption).lineLimit(1)
            if let project = store.project {
              Text(project.name).font(.caption2).foregroundStyle(Palette.secondary)
                .lineLimit(1)
            }
          }
          Image(systemName: "chevron.down").font(.caption2)
        }.padding(.horizontal, 10).frame(minHeight: 44).frame(maxWidth: 145)
          .floatingSurface()
      }.buttonStyle(.plain).accessibilityIdentifier("client-switch")
        .accessibilityLabel(
          L10n.tr("切换客户端，\(store.client?.name ?? L10n.tr("未连接"))，\(store.project?.name ?? L10n.tr("无项目"))"))
        .accessibilityValue(store.modeLabel + L10n.tr("，手机共\(notifications.unreadCount)个未读对话"))
  }

  @ViewBuilder private var chatBody: some View {
    #if DEBUG
    let _ = store.recordTypingChatRender()
    let _ = SidebarGestureProbe.chatRender()
    #endif
    if store.client == nil {
      VStack(spacing: 16) {
        Spacer()
        Image(systemName: "laptopcomputer").font(.system(size: 36)).foregroundStyle(
          Palette.secondary)
        Text(L10n.tr("把电脑上的工作带在身边")).font(.title3).fontWeight(.medium)
        Text(L10n.tr("切换客户端，查看项目和对话。")).font(.subheadline).foregroundStyle(Palette.secondary)
        Button(L10n.tr("添加客户端")) { store.sheet = .addClient }.buttonStyle(.borderedProminent).tint(
          .primary
        ).accessibilityIdentifier("onboarding-add")
        Spacer()
      }.frame(maxWidth: .infinity).padding(16)
    } else if store.selectedThreadId == nil && store.pendingDesktopCreation == nil {
      VStack(spacing: 8) {
        Spacer()
        Text(store.projects.isEmpty && !store.isProjectlessDraft ? L10n.tr("还没有项目") : L10n.tr("有什么可以帮忙的？")).font(.title2).fontWeight(
          .medium)
        Text(store.project.map { L10n.tr("在 \($0.name) 中开始新对话") } ?? (store.isProjectlessDraft ? L10n.tr("开始一个无项目对话") : L10n.tr("从左侧栏选择项目与对话"))).font(
          .subheadline
        ).foregroundStyle(Palette.secondary)
        if store.projects.isEmpty, store.client?.isDemo == true {
          Button(L10n.tr("添加示例项目")) { store.sheet = .addProject }
        }
        Spacer()
      }.frame(maxWidth: .infinity).padding(16).accessibilityIdentifier("empty-chat")
      #if DEBUG
      .overlay(alignment: .topLeading) {
        if HistoryScrollProbe.enabled {
          HistoryScrollEvidenceView(availableMessages: store.messages.count, messageLimit: visibleMessageLimit,
            targetMatches: HistoryScrollProbe.matches(host: store.client?.id, thread: store.selectedThreadId),
            hostMatches: HistoryScrollProbe.matchesHost(store.client?.id), threadSelected: store.selectedThreadId != nil,
            selectedThreadId: store.selectedThreadId)
        }
      }
      #endif
    } else {
      ScrollViewReader { proxy in
        GeometryReader { viewport in
          let target = store.targetKey
          let timeline = timelineCache.project(targetKey: target, messages: store.messages, messageLimit: visibleMessageLimit,
            activeRunId: store.runtime?.turnId, isRunning: store.isRunning || store.runtime?.state == "paused", focusedMessageId: store.chatSearchJump?.message.id)
          ScrollView {
            VStack(alignment: .leading, spacing: 0) {
              earlierHistoryControl(timeline: timeline)
              VStack(alignment: .leading, spacing: 18) {
                ForEach(timeline.items) { item in
                  Group {
                  switch item {
                  case .message(let message):
                    if store.chatSearchJump?.targetKey == store.targetKey, store.chatSearchJump?.message.id == message.id {
                      LocatedChatMessageView(message: store.chatSearchJump!.message, query: store.chatSearchJump!.query, client: store.client, threadId: store.selectedThreadId)
                    } else if message.kind == "turnFailure" {
                      VStack(alignment: .leading, spacing: 4) {
                        Label(L10n.tr("任务失败记录"), systemImage: "exclamationmark.circle")
                        Text(message.text).textSelection(.enabled)
                      }.font(.caption).foregroundStyle(Palette.secondary)
                        .accessibilityIdentifier("task-failure-history-\(message.id)")
                    } else if message.kind == "plan" {
                      ProposedPlanMessageView(message: message)
                    } else {
                      MessageView(message: message, client: store.client, threadId: store.selectedThreadId).equatable()
                    }
                  case .tools(let id, let messages):
                    ToolCallGroupView(id: id, messages: messages, client: store.client)
                      .padding(.vertical, messages.count > 1 ? -6 : 0)
                  case .replyActions(let id, let text, let turnId):
                    ReplyActionsView(store: store, id: id, text: text, turnId: turnId, source: store.forkSource)
                  case .compaction(let message):
                    ContextCompactionView(message: message,
                      isLive: store.client?.online == true && store.isWritable)
                  case .errors(let id, let messages):
                    VStack(alignment: .leading, spacing: 4) {
                      Label(store.isRunning ? L10n.tr("电脑报告运行异常，仍在处理中") : L10n.tr("运行异常记录（\(messages.count) 条）"), systemImage: "exclamationmark.arrow.trianglehead.2.clockwise.rotate.90")
                      if let last = messages.last {
                        let detail = last.detail?.isEmpty == false ? last.detail! : last.text
                        if !detail.isEmpty && detail != "error" { Text(detail).lineLimit(3) }
                      }
                    }.font(.caption).foregroundStyle(Palette.secondary)
                      .accessibilityIdentifier("runtime-error-group-\(id)")
                  }
                  }.id(item.id)
                    .background {
                      switch item {
                      case .message, .compaction:
                        HistoryRowAnchor(key: item.id, position: earlierHistoryPosition)
                      default: EmptyView()
                      }
                    }
                }
                if let creation = store.pendingDesktopCreation {
                  PendingCreationMessageView(creation: creation)
                }
                ForEach(store.visibleForks()) { pending in
                  ThreadForkReceiptView(store: store, pending: pending)
                }
                if let runtime = store.runtime {
                  RuntimePanel(store: store, runtime: runtime)
                }
                if (store.initialHistoryLoading || (store.loading && store.messages.isEmpty)) && earlierHistoryRequest == nil {
                  ProgressView(L10n.tr("读取历史…")).font(.caption).frame(maxWidth: .infinity)
                    .accessibilityIdentifier("history-loading")
                }
                if !store.loading && !store.historyLoading, store.messages.isEmpty,
                  store.pendingDesktopCreation == nil {
                  Text(L10n.tr("此对话暂时没有可显示的文字消息。")).foregroundStyle(Palette.secondary)
                }
                Color.clear.frame(height: 1).id("bottom").background {
                  GeometryReader { position in
                    Color.clear.preference(
                      key: ChatBottomPosition.self,
                      value: ChatBottomVisibility(targetKey: target,
                        bottom: position.frame(in: .named("chat-scroll")).maxY,
                        visibleBottom: max(0, viewport.size.height - composerHeight)))
                  }
                }
              }
              if earlierHistoryBottomSlack > 0 { Color.clear.frame(height: earlierHistoryBottomSlack) }
              #if DEBUG
              if earlierLayoutFixtureSlack > 0 { Color.clear.frame(height: earlierLayoutFixtureSlack) }
              #endif
            }.padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 12)
              .background {
                EarlierHistoryPullControl(
                  canLoad: earlierHistoryRequest == nil
                    && (timeline.hasEarlierMessages || store.historyCursor != nil),
                  position: earlierHistoryPosition,
                  action: { await loadEarlierHistory(timeline: timeline) })
                  .frame(width: 0, height: 0)
              }
          }.scrollDismissesKeyboard(.interactively).accessibilityIdentifier("message-list")
            .scrollBounceBehavior(.always, axes: .vertical)
            .safeAreaInset(edge: .top, spacing: 0) {
              VStack(spacing: 0) {
                Color.clear.frame(height: 54).allowsHitTesting(false)
                if store.conversation?.archived == true {
                  Label(L10n.tr("此对话已归档，恢复后可继续。"), systemImage: "archivebox")
                    .font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10).background(.regularMaterial)
                    .accessibilityIdentifier("archived-conversation-notice")
                }
                if let failure = store.conversationSyncFailure {
                  HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.arrow.trianglehead.2.clockwise.rotate.90")
                    VStack(alignment: .leading, spacing: 3) {
                      Text(store.conversationSyncFailureTitle).fontWeight(.semibold)
                        .accessibilityIdentifier("conversation-sync-failure")
                      Text(failure).lineLimit(3)
                    }
                    Spacer(minLength: 0)
                    Button(L10n.tr("重试")) { Task { await store.loadHistory() } }
                      .disabled(store.historyLoading).accessibilityIdentifier("conversation-sync-retry")
                  }.font(.caption).padding(10).background(.regularMaterial)
                }
              }
            }
            // Let the native scroll view account for the floating input and
            // keyboard in its visible area while keeping this inset transparent.
            .safeAreaInset(edge: .bottom, spacing: 0) {
              Color.clear.frame(height: composerHeight).allowsHitTesting(false)
            }
            .contentMargins(.bottom, composerHeight, for: .scrollIndicators)
            .coordinateSpace(name: "chat-scroll")
            .onPreferenceChange(ChatBottomPosition.self) { visibility in
              guard let visibility, visibility.targetKey == store.targetKey else { return }
              // Geometry changes on every scroll frame. Only crossing the read
              // visibility boundary changes UI state; pixel offsets must not
              // rebuild the timeline, Markdown and mounted sidebar together.
              if latestMessageVisibility?.targetKey != visibility.targetKey
                || latestMessageVisibility?.isVisible != visibility.isVisible {
                latestMessageVisibility = visibility
              }
              if userScrolling {
                let follows = visibility.isNearBottom
                if followsLatest != follows { followsLatest = follows }
              }
            }
            .simultaneousGesture(
              DragGesture().onChanged { _ in if !userScrolling { userScrolling = true } }
                .onEnded { _ in userScrolling = false }
            )
            .task(id: readingVisibilityKey) {
              await Task.yield()
              // A read that starts during drawer settlement must resume after it,
              // including a cancelled drag that leaves drawerOpen unchanged.
              do { try await store.waitForSidebarInteraction() } catch { return }
              guard !Task.isCancelled, scenePhase == .active, !store.drawerOpen,
                store.sheet == nil, latestMessageVisible, !store.historyLoading else { return }
              store.recordVisibleContent()
            }
            .onAppear {
              followsLatest = true
              proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onChange(of: store.chatSearchJump?.token) { _, _ in
              guard let jump = store.chatSearchJump, jump.targetKey == store.targetKey,
                let index = store.messages.firstIndex(where: { $0.id == jump.message.id }) else { return }
              visibleMessageLimit = max(visibleMessageLimit, store.messages.count - index)
              followsLatest = false
              if store.sheet != .chatSearch { readySearchJump = jump.token }
            }
            .onChange(of: readySearchJump) { _, token in
              guard let jump = store.chatSearchJump, jump.targetKey == store.targetKey,
                jump.token == token else { return }
              followsLatest = false
              Task { @MainActor in
                await Task.yield()
                guard store.chatSearchJump?.token == token else { return }
                withAnimation { proxy.scrollTo("message-" + jump.message.id, anchor: .center) }
              }
            }
            .onChange(of: store.selectedThreadId) { _, _ in
              followsLatest = true
              proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onChange(of: store.messages.last?.id) { _, _ in
              if followsLatest { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: store.messages.last?.text) { _, _ in
              if followsLatest { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: store.messages.last?.detail) { _, _ in
              if followsLatest { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: store.messages.last?.status) { _, _ in
              if followsLatest { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: store.pendingDesktopCreation?.clientOperationId) { _, _ in
              if followsLatest { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: store.runtime?.approvals.count) { _, _ in
              if followsLatest { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: composerFocused) { _, focused in
              if focused { earlierHistoryPosition.cancel() }
              if focused { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            }
            .onChange(of: followsLatest) { _, follows in
              if follows { earlierHistoryBottomSlack = 0 }
            }
            .task(id: ChatScrollLayout(viewportHeight: viewport.size.height, composerHeight: composerHeight)) {
              // Wait for the new keyboard/expanded-input layout before resolving
              // the bottom anchor. Switching chats cancels this view's task.
              await Task.yield()
              guard !Task.isCancelled, followsLatest else { return }
              proxy.scrollTo("bottom", anchor: .bottom)
            }
            .overlay(alignment: .bottomTrailing) {
              if !followsLatest {
                Button {
                  followsLatest = true
                  proxy.scrollTo("bottom", anchor: .bottom)
                } label: {
                  Image(systemName: "arrow.down").frame(width: 44, height: 44)
                    .floatingSurface()
                }.accessibilityLabel(L10n.tr("跳转最新消息")).padding(.horizontal, 12)
                  .padding(.bottom, composerHeight + 8)
              }
            }
            #if DEBUG
            .overlay(alignment: .topLeading) {
              if HistoryScrollProbe.enabled {
                HistoryScrollEvidenceView(availableMessages: store.messages.count, messageLimit: visibleMessageLimit,
                  targetMatches: HistoryScrollProbe.matches(host: store.client?.id, thread: store.selectedThreadId),
                  hostMatches: HistoryScrollProbe.matchesHost(store.client?.id), threadSelected: store.selectedThreadId != nil,
            selectedThreadId: store.selectedThreadId)
              }
            }
            #endif
        }
      }.id(store.targetKey)
    }
  }

  @ViewBuilder private func earlierHistoryControl(timeline: ChatTimeline) -> some View {
    if timeline.hasEarlierMessages || store.historyCursor != nil || earlierHistoryRequest != nil {
      Button {
        #if DEBUG
        earlierHistoryPosition.markAttempt(-1, loading: store.historyLoading, local: timeline.hasEarlierMessages)
        #endif
        Task { await loadEarlierHistory(timeline: timeline) }
      } label: {
        HStack(spacing: 6) {
          if earlierHistoryRequest != nil {
            ProgressView().controlSize(.mini)
          } else {
            Image(systemName: "chevron.up").font(.caption2)
          }
          Text(earlierHistoryRequest != nil ? L10n.tr("正在加载更早消息…") : L10n.tr("显示更早消息"))
            .font(.caption)
        }.foregroundStyle(Palette.secondary)
          .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
      }.buttonStyle(.plain)
        .disabled(earlierHistoryRequest != nil)
        .accessibilityHint(L10n.tr("也可以在列表顶部下拉加载"))
        .accessibilityIdentifier(timeline.hasEarlierMessages ? "history-local-more" : "history-more")
        .accessibilityValue(earlierHistoryRequest != nil ? L10n.tr("正在加载") : "")
        // Keep the full touch target while sharing its empty edges with the
        // surrounding whitespace instead of adding another full-height row.
        .padding(.vertical, -6)
        .padding(.bottom, 6)
    } else if revealedEarlierHistory {
      Text(L10n.tr("已显示全部消息")).font(.caption2).foregroundStyle(Palette.secondary)
        .frame(maxWidth: .infinity, minHeight: 28).padding(.bottom, 6)
        .accessibilityIdentifier("history-start")
    }
  }

  @MainActor private func loadEarlierHistory(timeline: ChatTimeline) async {
    #if DEBUG
    earlierHistoryPosition.markAttempt(-2, loading: store.historyLoading, local: timeline.hasEarlierMessages)
    #endif
    guard !Task.isCancelled, earlierHistoryRequest == nil,
      timeline.hasEarlierMessages || store.historyCursor != nil else { return }
    let target = store.targetKey
    let request = UUID()
    earlierHistoryRequest = request
    followsLatest = false
    defer { if earlierHistoryRequest == request { earlierHistoryRequest = nil } }
    if timeline.hasEarlierMessages {
      earlierHistoryBottomSlack += earlierHistoryPosition.prepareForPrepend()
      visibleMessageLimit += Int(SharedReadingPolicy.earlierMessageIncrement())
    } else {
      // Capture the old layout and expand the local window in the same main
      // actor transaction that publishes the prefix. Awaiting the read first
      // allowed SwiftUI to lay out a truncated new page before this anchor.
      guard await store.loadHistory(more: true, beforePrepend: { added in
        guard store.targetKey == target, earlierHistoryRequest == request else { return }
        earlierHistoryBottomSlack += earlierHistoryPosition.prepareForPrepend()
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-history-prepend-layout-fixture") {
          earlierLayoutFixtureSlack = 112
          Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(100))
            guard store.targetKey == target else { return }
            earlierLayoutFixtureSlack = 56
          }
        }
        #endif
        visibleMessageLimit += added
      }), !Task.isCancelled,
        store.targetKey == target, earlierHistoryRequest == request else { return }
    }
    revealedEarlierHistory = true
  }

  private var composer: some View {
    VStack(spacing: 4) {
      let questions = store.runtime?.approvals.filter { $0.interactionMode == "async" } ?? []
      if !questions.isEmpty {
        AsyncQuestionDeck(store: store, approvals: questions, targetKey: store.targetKey) {
          composerFocused = false
          voice.cancel()
        }.id(store.targetKey).padding(.horizontal, 16)
      }
      ComposerView(suggestionReader: store.desktopSuggestionReader, store: store, drafts: store.drafts, voice: voice,
        attachmentModalPresented: $attachmentModalPresented,
        showComposerActions: $showComposerActions, composerFocused: $composerFocused)
    }
  }

  private var sidebar: some View {
    let order = sidebarOrder
    let pinnedItems = sidebarPinnedItems(order)
    let recentThreads = sidebarRecentThreads(order)
    let filteredProjects = sidebarFilteredProjects(order)
    return VStack(spacing: 0) {
      HStack(spacing: 0) {
        Button {
          sidebarSearchFocused = false
          store.sheet = .clients
        } label: {
          VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 8) {
              Text(store.client?.name ?? "Agent Hub")
                .font(.title3).fontWeight(.semibold).lineLimit(1)
              Image(systemName: "chevron.down").font(.caption)
                .foregroundStyle(Palette.secondary)
            }
            Text(store.sourceLabel(for: store.client)).font(.caption2)
              .foregroundStyle(Palette.secondary).lineLimit(1)
          }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }.accessibilityLabel(L10n.tr("切换客户端，\(store.client?.name ?? L10n.tr("未连接"))，\(store.sourceLabel(for: store.client))"))
          .accessibilityIdentifier("sidebar-client-switch")
          .accessibilityValue(L10n.tr("手机共\(notifications.unreadCount)个未读对话"))
        IconButton(symbol: "magnifyingglass", label: L10n.tr("搜索项目和对话"), id: "sidebar-search-toggle") {
          sidebarSearchVisible.toggle()
          sidebarSearchFocused = sidebarSearchVisible
          if !sidebarSearchVisible { store.search = "" }
        }
      }.padding(.leading, 9)
      if sidebarSearchVisible {
        HStack(spacing: 8) {
          Image(systemName: "magnifyingglass").foregroundStyle(Palette.secondary)
          TextField(L10n.tr("搜索项目和对话"), text: $store.search).font(.body)
            .focused($sidebarSearchFocused).accessibilityIdentifier("sidebar-search")
            .onSubmit { sidebarSearchFocused = false }
          if !store.search.isEmpty {
            Button { store.search = "" } label: {
              Image(systemName: "xmark.circle.fill")
            }.accessibilityLabel(L10n.tr("清除搜索"))
          }
        }.padding(.horizontal, 12).frame(minHeight: 44)
          .background(Palette.background, in: RoundedRectangle(cornerRadius: 12))
          .padding(.vertical, 4)
      }
      Button {
        sidebarSearchFocused = false
        store.sheet = .projects
      } label: {
        Label(L10n.tr("新聊天"), systemImage: "square.and.pencil")
          .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          .contentShape(Rectangle())
      }.padding(.horizontal, 9).padding(.vertical, 4)
        .disabled(!store.canCreateThread || store.client?.online != true)
        .accessibilityIdentifier("sidebar-new")
      Button {
        sidebarSearchFocused = false
        store.sheet = .taskHub
      } label: {
        Label(L10n.tr("项目与任务"), systemImage: "checklist")
          .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          .contentShape(Rectangle())
      }.padding(.horizontal, 9).accessibilityIdentifier("sidebar-task-hub")
      if let client = store.client, !client.isDemo {
        Button {
          sidebarSearchFocused = false
          store.sheet = .fileTransfers
        } label: {
          Label(L10n.tr("文件互传"), systemImage: "arrow.up.arrow.down")
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }.padding(.horizontal, 9).accessibilityIdentifier("sidebar-file-transfers")
      }
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 0) {
          if !pinnedItems.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
            Button {
              var collapsed = collapsedPinnedSections
              toggleSidebarSet(&collapsed, key: sidebarClientKey)
              if let data = try? JSONEncoder().encode(collapsed.sorted()),
                let value = String(data: data, encoding: .utf8) { collapsedPinnedClients = value }
            } label: {
              HStack(spacing: 6) {
                Text(L10n.tr("置顶"))
                Image(systemName: pinnedSectionExpanded ? "chevron.down" : "chevron.right")
                  .font(.caption2)
              }.font(.subheadline).foregroundStyle(Palette.secondary)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }.padding(.horizontal, 9)
              .accessibilityIdentifier("sidebar-pinned-toggle")
              .accessibilityLabel(pinnedSectionExpanded ? L10n.tr("收起置顶聊天") : L10n.tr("展开置顶聊天"))
              .accessibilityValue(pinnedSectionExpanded ? L10n.tr("已展开") : L10n.tr("已折叠"))
              .disabled(!store.search.isEmpty)
            if pinnedSectionExpanded {
              ForEach(pinnedItems, id: \.key) { item in
                if item.kind == "thread", let thread = store.threads.first(where: { $0.id == item.id }) {
                  threadRow(thread, idPrefix: "pinned-thread")
                } else if item.kind == "project", let project = store.projects.first(where: { $0.id == item.id }) {
                  sidebarProject(project, order: order)
                }
              }
            }
            }.id("sidebar-pinned-section")
          }
          if !recentThreads.isEmpty {
            Button {
              var collapsed = collapsedRecentSections
              toggleSidebarSet(&collapsed, key: sidebarClientKey)
              if let data = try? JSONEncoder().encode(collapsed.sorted()),
                let value = String(data: data, encoding: .utf8) { collapsedRecentClients = value }
            } label: {
              HStack(spacing: 6) {
                Text(L10n.tr("最近"))
                Image(systemName: recentSectionExpanded ? "chevron.down" : "chevron.right")
                  .font(.caption2)
              }.font(.subheadline).foregroundStyle(Palette.secondary)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }.padding(.horizontal, 9)
              .accessibilityIdentifier("sidebar-recent-toggle")
              .accessibilityLabel(recentSectionExpanded ? L10n.tr("收起最近聊天") : L10n.tr("展开最近聊天"))
              .accessibilityValue(recentSectionExpanded ? L10n.tr("已展开") : L10n.tr("已折叠"))
              .disabled(!store.search.isEmpty)
            if recentSectionExpanded {
              let expanded = expandedRecentClients.contains(sidebarClientKey)
              ForEach(limitedThreads(recentThreads, expanded: expanded)) { thread in
                threadRow(thread, idPrefix: "recent-thread")
              }
              if store.search.isEmpty && recentThreads.count > 5 {
                Button(expanded ? L10n.tr("收起显示") : L10n.tr("展开显示")) {
                  toggleSidebarSet(&expandedRecentClients, key: sidebarClientKey)
                }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                  .padding(.horizontal, 9).foregroundStyle(Palette.secondary)
                  .accessibilityIdentifier("sidebar-more-recent")
                  .accessibilityLabel(expanded ? L10n.tr("收起更多最近聊天") : L10n.tr("展开更多最近聊天"))
              }
            }
          }
          if !filteredProjects.isEmpty || store.canCreateProject {
            HStack(spacing: 0) {
              Button {
                toggleSidebarSet(&collapsedProjectSections, key: sidebarClientKey)
              } label: {
                HStack(spacing: 6) {
                  Text(L10n.tr("项目"))
                  Image(systemName: projectsSectionExpanded ? "chevron.down" : "chevron.right")
                    .font(.caption2)
                }.foregroundStyle(Palette.secondary)
                  .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                  .contentShape(Rectangle())
              }.accessibilityIdentifier("sidebar-projects-toggle")
                .accessibilityValue(projectsSectionExpanded ? L10n.tr("已展开") : L10n.tr("已折叠"))
                .disabled(!store.search.isEmpty)
              if store.canCreateProject {
                IconButton(symbol: "plus", label: L10n.tr("添加项目"), id: "add-project") {
                  store.sheet = .addProject
                }.disabled(store.client?.online != true)
              }
            }.padding(.leading, 9).padding(.top, 12)
            if projectsSectionExpanded {
              ForEach(filteredProjects) { project in sidebarProject(project, order: order) }
            }
          }
          if filteredProjects.isEmpty && recentThreads.isEmpty && pinnedItems.isEmpty {
            Text(store.search.isEmpty ? L10n.tr("还没有项目或对话") : L10n.tr("没有找到相关内容"))
              .font(.subheadline).foregroundStyle(Palette.secondary).padding(.vertical, 24)
          }
          if let workspace = store.client.flatMap({ store.saved.workspaces[$0.id] }),
            workspace.nextProjectCursor != nil || workspace.nextThreadCursor != nil
          {
            Button(L10n.tr("加载更多")) { Task { await store.refresh(loadMore: true, includeHistory: false, userInitiated: true) } }
              .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
              .padding(.horizontal, 9).disabled(store.loading)
              .accessibilityIdentifier("sidebar-load-more")
          }
        }
        .background {
          SidebarPullRefresh(targetKey: sidebarClientKey) { await refreshSidebar() }
            .frame(width: 0, height: 0)
        }
      }
      .overlay(alignment: .top) {
        if sidebarRefreshRequest != nil {
          ProgressView().controlSize(.small).padding(.top, 10)
            .accessibilityLabel(L10n.tr("正在加载"))
            .accessibilityIdentifier("sidebar-refresh-progress")
            .allowsHitTesting(false)
        }
      }
      #if DEBUG
      .modifier(SidebarRefreshEvidence(refreshing: sidebarRefreshRequest != nil))
      #endif
      .scrollBounceBehavior(.always)
      .scrollDismissesKeyboard(.immediately)
      .scrollDismissesKeyboard(.interactively).accessibilityIdentifier("sidebar-list")
      HStack(spacing: 0) {
        Button {
          store.drawerOpen = false
          store.sheet = .settings
        } label: {
          HStack(spacing: 7) {
            Image(systemName: "gearshape").font(.system(size: 16))
            Text(L10n.tr("设置")).font(.system(size: 13))
          }.frame(width: 60).frame(minHeight: 44).contentShape(Rectangle())
        }.accessibilityIdentifier("open-settings")
        Spacer(minLength: 12)
        if let client = store.client {
          UsageFooterButton(client: client, target: store.mobileReadingTarget(client), active: store.drawerOpen && store.sheet == nil) { store.sheet = .usage }
        }
        IconButton(symbol: "sidebar.left", label: L10n.tr("收起侧栏"), id: "close-sidebar", action: closeSidebar)
      }.padding(.top, 6).overlay(alignment: .top) { Divider().allowsHitTesting(false) }
    }.padding(.horizontal, 10).padding(.top, 6).padding(.bottom, 8)
      .font(.callout).foregroundStyle(Color.primary).buttonStyle(.plain).background(Palette.sidebar)
      .accessibilityAction(.escape, closeSidebar)
  }

  private var sidebarClientKey: String { store.client?.id ?? "none" }
  @MainActor private func refreshSidebar() async {
    guard sidebarRefreshRequest == nil else { return }
    let request = UUID()
    sidebarRefreshRequest = request
    defer { if sidebarRefreshRequest == request { sidebarRefreshRequest = nil } }
    await store.refresh(includeHistory: false, userInitiated: true)
  }
  private var sidebarRefreshKey: String {
    "\(store.targetKey):\(store.drawerOpen):\(scenePhase == .active):\(store.client?.online == true)"
  }

  private var readingVisibilityKey: String {
    let position = store.conversation?.readState?.latest
    let runtime = store.runtime
    let notices = (runtime?.approvals.map { $0.id + ":" + $0.turnId }.joined(separator: ",") ?? "")
      + (runtime?.proposedPlan?.id ?? "") + (runtime?.receipts.map { $0.id + ":" + $0.state }.joined(separator: ",") ?? "")
    return [store.readingContextKey, position?.contentVersion ?? "", String(position?.revision ?? 0),
      String(store.visibleReadPosition != nil),
      String(latestMessageVisible), String(store.historyLoading), String(store.drawerOpen),
      String(store.sheet == nil), String(scenePhase == .active), String(notifications.unreadCount),
      notifications.unreadRevision, runtime?.state ?? "", runtime?.turnId ?? "", notices].joined(separator: "|")
  }
  private var latestMessageVisible: Bool {
    latestMessageVisibility?.targetKey == store.targetKey && latestMessageVisibility?.isVisible == true
  }
  private var projectsSectionExpanded: Bool {
    SharedMobileRules.sidebarExpanded(preference: !collapsedProjectSections.contains(sidebarClientKey), searching: !store.search.isEmpty)
  }
  private var collapsedRecentSections: Set<String> {
    Set((try? JSONDecoder().decode([String].self, from: Data(collapsedRecentClients.utf8))) ?? [])
  }
  private var recentSectionExpanded: Bool {
    SharedMobileRules.sidebarExpanded(preference: !collapsedRecentSections.contains(sidebarClientKey), searching: !store.search.isEmpty)
  }
  private var collapsedPinnedSections: Set<String> {
    Set((try? JSONDecoder().decode([String].self, from: Data(collapsedPinnedClients.utf8))) ?? [])
  }
  private var pinnedSectionExpanded: Bool {
    SharedMobileRules.sidebarExpanded(preference: !collapsedPinnedSections.contains(sidebarClientKey), searching: !store.search.isEmpty)
  }
  private var pinnedThreadIds: [String] {
    store.client.flatMap { store.saved.workspaces[$0.id]?.pinnedThreadIds } ?? []
  }
  private var sidebarOrder: SidebarDisplayOrder {
    SharedMobileRules.sidebarOrder(hostId: store.client?.id ?? "", threads: store.threads,
      projects: store.projects, pinnedThreadIds: pinnedThreadIds,
      sidebar: store.client.flatMap { store.saved.workspaces[$0.id]?.sidebar })
  }
  // Mapping only: mobile-core has already selected and ordered valid IDs.
  private func orderedThreads(_ ids: [String]) -> [Conversation] {
    let hostId = store.client?.id
    let threads = Dictionary(store.threads.filter { $0.hostId == hostId }.map { ($0.id, $0) },
      uniquingKeysWith: { first, _ in first })
    return ids.compactMap { threads[$0] }
  }
  private func sidebarPinnedItems(_ order: SidebarDisplayOrder) -> [WorkspaceSidebar.Item] {
    let threads = Set(orderedThreads(order.pinned.filter { $0.kind == "thread" }.map(\.id)).filter { matches($0.title) }.map(\.id))
    return order.pinned.filter { item in
      if item.kind == "thread" { return threads.contains(item.id) }
      return store.projects.contains { project in
        project.id == item.id && (matches(project.name) || projectThreads(project, order: order).contains { matches($0.title) })
      }
    }
  }
  private func sidebarRecentThreads(_ order: SidebarDisplayOrder) -> [Conversation] {
    orderedThreads(order.recentThreadIds).filter { matches($0.title) }
  }
  private func projectThreads(_ project: Project, order: SidebarDisplayOrder) -> [Conversation] {
    orderedThreads(order.projectThreadIds[project.id] ?? [])
  }
  private func limitedThreads(_ threads: [Conversation], expanded: Bool) -> [Conversation] {
    let byId = Dictionary(threads.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    return SharedMobileRules.sidebarVisibleIds(threads.map(\.id), expanded: expanded,
      searching: !store.search.isEmpty).compactMap { byId[$0] }
  }
  private func toggleSidebarSet(_ values: inout Set<String>, key: String) {
    if values.contains(key) { values.remove(key) } else { values.insert(key) }
  }
  private func sidebarProject(_ project: Project, order: SidebarDisplayOrder) -> some View {
    let expanded = SharedMobileRules.sidebarExpanded(
      preference: store.saved.expanded[project.hostId]?.contains(project.id) == true,
      searching: !store.search.isEmpty)
    let children = expanded ? projectThreads(project, order: order).filter {
      matches($0.title) || matches(project.name)
    } : []
    let listKey = project.hostId + ":" + project.id
    let showAll = expandedProjectLists.contains(listKey)
    let rows = Group {
      HStack(spacing: 0) {
        Button { store.toggleProject(project.id) } label: {
          HStack(spacing: 10) {
            Image(systemName: "folder").frame(width: 20)
            Text(project.name).lineLimit(1).truncationMode(.tail)
              .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: expanded ? "chevron.down" : "chevron.right")
              .font(.caption2).foregroundStyle(Palette.secondary)
          }.frame(minHeight: 44).contentShape(Rectangle())
        }.accessibilityIdentifier("project-\(project.id)")
          .accessibilityValue(expanded ? L10n.tr("已展开") : L10n.tr("已折叠"))
          .disabled(!store.search.isEmpty)
        if store.canCreateThread {
          IconButton(symbol: "square.and.pencil", label: L10n.tr("在 \(project.name) 新建对话"), id: "new-in-\(project.id)") {
            sidebarSearchFocused = false
            store.newConversation(in: project)
          }.disabled(store.client?.online != true)
        }
      }.padding(.leading, 9)
      if expanded {
        ForEach(limitedThreads(children, expanded: showAll)) { thread in
          threadRow(thread, project: project).padding(.leading, 24)
        }
        if store.search.isEmpty && children.count > 5 {
          Button(showAll ? L10n.tr("收起显示") : L10n.tr("展开显示")) {
            toggleSidebarSet(&expandedProjectLists, key: listKey)
          }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .padding(.leading, 33).foregroundStyle(Palette.secondary)
            .accessibilityIdentifier("sidebar-more-project-\(project.id)")
            .accessibilityLabel(showAll ? L10n.tr("折叠 \(project.name) 的聊天") : L10n.tr("展开 \(project.name) 的更多聊天"))
        }
      }
    }
    return Group {
      if showAll || !store.search.isEmpty {
        LazyVStack(alignment: .leading, spacing: 0) { rows }
      } else {
        // The default list contains at most five rows. Keep their accessibility
        // elements materialized inside the outer lazy sidebar on iOS 27.
        VStack(alignment: .leading, spacing: 0) { rows }
      }
    }.padding(.bottom, 6)
  }

  private func sidebarFilteredProjects(_ order: SidebarDisplayOrder) -> [Project] {
    let hostId = store.client?.id
    let byId = Dictionary(store.projects.filter { $0.hostId == hostId }.map { ($0.id, $0) },
      uniquingKeysWith: { first, _ in first })
    return order.projectIds.compactMap { byId[$0] }.filter { project in
      matches(project.name) || projectThreads(project, order: order).contains { matches($0.title) }
    }
  }
  private func matches(_ value: String) -> Bool {
    store.search.isEmpty || value.localizedCaseInsensitiveContains(store.search)
  }
  private func threadRow(_ thread: Conversation, idPrefix: String = "thread", project: Project? = nil) -> some View {
    let questionCount = store.pendingAsyncQuestionCount(for: thread)
    let status = [thread.state == "running" ? L10n.tr("执行中") : thread.state == "waiting-for-approval" ? L10n.tr("等待审批") : nil,
      questionCount > 0 ? L10n.tr("\(questionCount) 个问题待回答") : nil,
      questionCount == 0 && store.showsUnreadDot(thread) ? L10n.tr("未读") : nil].compactMap { $0 }.joined(separator: L10n.tr("，"))
    return Button {
      sidebarSearchFocused = false
      store.openThread(thread, in: project)
    } label: {
      HStack(spacing: 8) {
        Text(thread.title).lineLimit(1).truncationMode(.tail)
          .frame(minWidth: 0, maxWidth: .infinity, alignment: .leading).clipped()
        if thread.state == "running" {
          // The drawer stays mounted while closed. A window alone does not
          // mean its repeating animation is visible or should remain active.
          RunningTaskIndicator(isVisible: store.drawerOpen)
        } else if thread.state == "waiting-for-approval" {
          Image(systemName: "hand.raised.fill").foregroundStyle(Color.orange)
        }
        if questionCount > 0 {
          Image(systemName: "questionmark.bubble.fill").foregroundStyle(Color.orange)
            .accessibilityIdentifier("thread-async-question-\(thread.id)")
            .accessibilityLabel(L10n.tr("待回答问题"))
        } else if store.showsUnreadDot(thread) {
          Circle().fill(Color.blue).frame(width: 7, height: 7)
            .accessibilityHidden(true)
        }
      }.padding(.horizontal, 9).frame(minHeight: 44)
        .background(
          thread.id == store.selectedThreadId ? Color.primary.opacity(0.06) : .clear,
          in: RoundedRectangle(cornerRadius: 10)
        )
        .contentShape(Rectangle())
    }.accessibilityIdentifier("\(idPrefix)-\(thread.id)").accessibilityLabel(thread.title)
      .accessibilityValue(status)
      .accessibilityAddTraits(
      thread.id == store.selectedThreadId ? .isSelected : [])
  }

  @ViewBuilder private func sheets(_ route: AppSheet) -> some View {
    switch route {
    case .chatSearch: ChatHistorySearchView(store: store, targetKey: store.targetKey)
    case .archivedChats: ArchivedChatsView(store: store)
    case .taskHub: TaskHubView(store: taskHub, openExecution: { navigation in try await store.openTaskExecution(navigation) }).presentationDetents([.large])
    case .clients: ClientPickerView(store: store)
    case .addClient: AddClientView(store: store)
    case .projects: ProjectPickerView(store: store)
    case .settings: SettingsView(store: store)
    case .usage: if let client = store.client { UsageDetailsView(client: client, target: store.mobileReadingTarget(client)) }
    case .addProject: AddProjectView(store: store)
    case .generation: GenerationSettings(store: store)
    case .aiSharing: AISharingPermissionView(store: store)
    case .fileTransfers:
      if let client = store.fileTransferClient { FileTransfersView(client: client) }
    }
  }
}

private struct UserMessageTextHeights: PreferenceKey {
  static let defaultValue: [Int: CGFloat] = [:]
  static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
    value.merge(nextValue(), uniquingKeysWith: { _, new in new })
  }
}

private struct UserMessageBubble: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var isExpanded = false
  @State private var hasOverflow = false
  private let previewLines = Int(MobileDesignGeometry.userBubblePreviewLines)
  let id: String
  let text: String
  let files: [String]

  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      if !text.isEmpty {
        Text(verbatim: text)
          .lineLimit(isExpanded ? nil : previewLines)
          .fixedSize(horizontal: false, vertical: true)
          .textSelection(.enabled)
          .accessibilityIdentifier("user-message-text-\(id)")
          .background(alignment: .topLeading) {
            // Measure only the preview and one extra line, even for very large inputs.
            ZStack(alignment: .topLeading) {
              ForEach(previewLines...(previewLines + 1), id: \.self) { lines in
                Text(verbatim: text).lineLimit(lines)
                  .fixedSize(horizontal: false, vertical: true)
                  .background {
                    GeometryReader { geometry in
                      Color.clear.preference(key: UserMessageTextHeights.self,
                        value: [lines: geometry.size.height])
                    }
                  }
              }
            }.hidden().accessibilityHidden(true).allowsHitTesting(false)
          }
          .onPreferenceChange(UserMessageTextHeights.self) { heights in
            guard let preview = heights[previewLines], let extra = heights[previewLines + 1] else { return }
            hasOverflow = extra > preview + 0.5
          }
      }
      ForEach(Array(files.enumerated()), id: \.offset) { _, name in
        Label(name, systemImage: "paperclip").font(.caption)
      }
      if hasOverflow {
        Button {
          withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { isExpanded.toggle() }
        } label: {
          HStack(spacing: 5) {
            Spacer(minLength: 0)
            Text(isExpanded ? L10n.tr("收起") : L10n.tr("展开"))
            Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
              .font(.system(size: 10, weight: .semibold))
          }.font(.caption).fontWeight(.medium).frame(minHeight: 44)
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
          .accessibilityIdentifier("user-message-toggle-\(id)")
          .accessibilityLabel(isExpanded ? L10n.tr("收起") : L10n.tr("展开"))
          .accessibilityValue(isExpanded ? L10n.tr("已展开") : L10n.tr("已折叠"))
      }
    }.padding(.vertical, 10).padding(.horizontal, 14)
      .foregroundStyle(Palette.bubbleText)
      .background(Palette.bubble, in: RoundedRectangle(cornerRadius: 20))
      .onChange(of: id) { _, _ in isExpanded = false }
  }
}

private struct PendingCreationMessageView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let creation: PendingDesktopCreation

  var body: some View {
    VStack(alignment: .trailing, spacing: 6) {
      HStack(spacing: 8) {
        Spacer(minLength: 24)
        if creation.phase != "rejected" {
          ProgressView().controlSize(.small).tint(Palette.secondary)
            .accessibilityLabel(L10n.tr("消息正在发送"))
            .accessibilityIdentifier("desktop-creation-message-loading")
        }
        UserMessageBubble(id: creation.clientOperationId, text: creation.text, files: creation.attachments.map(\.name))
          .accessibilityIdentifier("desktop-creation-message-content")
      }
      Text(creation.phase == "rejected" ? L10n.tr("未发送") : L10n.tr("发送中…"))
        .font(.caption2).foregroundStyle(Palette.secondary)
        .accessibilityIdentifier("desktop-creation-message-status")
    }.font(.body).frame(maxWidth: .infinity, alignment: .trailing)
      .accessibilityElement(children: .contain)
      .accessibilityIdentifier("desktop-creation-message")
  }
}

struct MessageView: View, Equatable {
  let message: ChatMessage
  var client: Client?
  var threadId: String? = nil
  nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.message == rhs.message && lhs.client == rhs.client && lhs.threadId == rhs.threadId
  }
  var body: some View {
    VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 6) {
      if message.role == "user" {
        HStack {
          Spacer(minLength: 24)
          UserMessageBubble(id: message.id, text: message.text, files: message.files)
        }
      } else {
        if message.status == "inProgress" {
          Text(verbatim: message.text).textSelection(.enabled).lineSpacing(4)
        } else {
          MarkdownBody(text: message.text).equatable()
        }
        if message.status == "inProgress" {
          HStack(spacing: 4) {
            Circle().fill(Color.primary).frame(width: 5, height: 5)
            Text(L10n.tr("正在输出…")).font(.caption2)
          }
          .accessibilityIdentifier("streaming-message")
        } else {
          if message.status == "interrupted" {
            Text(L10n.tr("已停止")).font(.caption2).foregroundStyle(Palette.secondary)
          }
        }
      }
      if let client {
        ForEach(message.attachments ?? [], id: \.resourceIdentity) { file in
          RemoteFileView(client: client, file: file)
        }
      }
    }.font(.body).frame(maxWidth: .infinity, alignment: .leading)
      .modifier(MessageLinkOpening(messageId: message.id, threadId: threadId, client: client, attachments: message.attachments ?? []))
  }
}

private struct ReplyActionsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let id: String
  let text: String
  let turnId: String?
  let source: ThreadForkSource?

  var body: some View {
    HStack(spacing: 0) {
      IconButton(symbol: "doc.on.doc", label: L10n.tr("复制回复"), id: "copy-reply") {
        UIPasteboard.general.string = text
      }
      Menu {
        ForEach([ThreadForkEnvironment.sameDirectory, .worktree], id: \.rawValue) { environment in
          let reason = store.forkReason(source: source, turnId: turnId, environment: environment)
          Button {
            store.forkThread(source: source, turnId: turnId, environment: environment)
          } label: {
            Text(environment.title)
            if let reason { Text(reason) }
          }.disabled(reason != nil)
            .accessibilityIdentifier("fork-\(environment.rawValue)")
            .accessibilityHint(reason ?? L10n.tr("继承此轮次之前的对话，不发送新消息"))
        }
        if let reason = store.forkReason(source: source, turnId: turnId, environment: .worktree) {
          Text(reason)
        }
      } label: {
        Group {
          if store.forkBusy(source) { ProgressView().controlSize(.small) }
          else { Image(systemName: "arrow.triangle.branch") }
        }.frame(width: 44, height: 44)
      }.accessibilityLabel(L10n.tr("分支对话")).accessibilityIdentifier("fork-reply")
        .simultaneousGesture(TapGesture().onEnded {
          if turnId?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
            Task { await store.loadForkOptions(source, refresh: true) }
          }
        })
      ShareLink(item: text) {
        Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
      }.accessibilityLabel(L10n.tr("分享回复")).accessibilityIdentifier("share-reply")
    }.foregroundStyle(Palette.secondary).padding(.leading, -10)
      .accessibilityElement(children: .contain)
      .accessibilityIdentifier("run-reply-actions-\(id)")
      .task(id: source?.key) {
        if turnId?.isEmpty == false { await store.loadForkOptions(source) }
      }
  }
}

private struct ThreadForkReceiptView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let pending: PendingThreadFork

  var body: some View {
    HStack(alignment: .top, spacing: 8) {
      VStack(alignment: .leading, spacing: 4) {
        HStack(spacing: 6) {
          if (pending.phase == .submitted && pending.detached != true) || store.isQueryingFork(pending.id) {
            ProgressView().controlSize(.mini)
          }
          Text(pending.title)
        }
        Text(pending.request.environment.title).foregroundStyle(Palette.secondary)
        if pending.detached == true {
          Text(L10n.tr("仅结束本机等待；电脑端操作未取消，原回执保留。")).foregroundStyle(Palette.secondary)
        }
        if let error = pending.error { Text(error).foregroundStyle(Palette.secondary).textSelection(.enabled) }
      }.frame(maxWidth: .infinity, alignment: .leading)
      VStack(alignment: .trailing, spacing: 8) {
        Button(pending.phase == .confirmed ? L10n.tr("打开分支") : L10n.tr("查询原回执")) { store.queryFork(pending) }
          .disabled(store.isQueryingFork(pending.id))
          .accessibilityIdentifier("query-fork-\(pending.id)")
        if pending.phase.isPending && pending.detached != true {
          Button(L10n.tr("结束等待")) { store.detachFork(pending) }
            .accessibilityIdentifier("detach-fork-\(pending.id)")
            .accessibilityHint(L10n.tr("结束本机等待，保留原回执；电脑端操作未取消"))
        }
      }
    }.font(.caption).padding(10)
      .background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 10))
      .accessibilityElement(children: .contain)
      .accessibilityIdentifier("fork-receipt-\(pending.id)")
      .accessibilityValue(pending.request.environment.rawValue)
  }
}

struct ContextCompactionView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let message: ChatMessage
  let isLive: Bool

  private var running: Bool { message.status == "inProgress" && isLive }
  private var label: String { L10n.tr(LocalizedText(stringLiteral: SharedMobileRules.compactionLabel(message.status, live: isLive))) }

  var body: some View {
    HStack(spacing: 8) {
      Image(systemName: "arrow.down.right.and.arrow.up.left").accessibilityHidden(true)
      Text(label)
      if running { ProgressView().controlSize(.mini) }
      Spacer(minLength: 0)
    }.font(.callout).foregroundStyle(Palette.secondary)
      .padding(.vertical, 8)
      .accessibilityElement(children: .ignore).accessibilityLabel(label)
      .accessibilityIdentifier("context-compaction-\(message.id)")
  }
}

private struct ToolGroupIcon: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  var body: some View {
    Canvas { context, size in
      let scale = min(size.width, size.height) / 18
      var links = Path()
      links.move(to: CGPoint(x: 12 * scale, y: 6 * scale))
      links.addLine(to: CGPoint(x: 6 * scale, y: 12 * scale))
      context.stroke(links, with: .color(Palette.secondary), lineWidth: 1.4 * scale)
      var nodes = Path()
      for origin in [CGPoint(x: 1, y: 1), CGPoint(x: 11, y: 1),
        CGPoint(x: 1, y: 11), CGPoint(x: 11, y: 11)] {
        nodes.addEllipse(in: CGRect(x: origin.x * scale, y: origin.y * scale,
          width: 6 * scale, height: 6 * scale))
      }
      context.stroke(nodes, with: .color(Palette.secondary), lineWidth: 1.4 * scale)
    }.frame(width: 18, height: 18).accessibilityHidden(true)
  }
}

private final class NativeToolPresentationText: NSObject, ToolPresentationText {
  static let shared = NativeToolPresentationText()
  var english: Bool { L10n.language == "en" }
  func text(key: String) -> String { L10n.tr(LocalizedText(stringLiteral: key)) }
}

struct ToolCallGroupView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let id: String
  let messages: [ChatMessage]
  var client: Client?
  @State private var groupExpanded = false
  @State private var expandedTools: Set<String> = []

  private var summary: String { SharedMobileRules.toolSummary(messages, native: NativeToolPresentationText.shared) }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      if messages.count > 1 {
        Button { groupExpanded.toggle() } label: {
          HStack(spacing: 8) {
            ToolGroupIcon()
            Text(summary).fontWeight(.regular).lineLimit(1)
            Image(systemName: groupExpanded ? "chevron.down" : "chevron.right")
              .font(.system(size: 11))
            Spacer(minLength: 0)
          }.font(.callout).foregroundStyle(Palette.secondary).frame(minHeight: 44)
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
          .accessibilityIdentifier("tool-group-\(id)")
          .accessibilityLabel(summary)
          .accessibilityValue(groupExpanded ? L10n.tr("已展开") : L10n.tr("已折叠"))
      }
      if messages.count == 1 || groupExpanded {
        if messages.count > 6 {
          ScrollView(.vertical) { toolRows }
            .frame(maxHeight: 264)
            .overlay(alignment: .bottom) {
              LinearGradient(colors: [Palette.background.opacity(0), Palette.background],
                startPoint: .top, endPoint: .bottom)
                .frame(height: 22).allowsHitTesting(false).accessibilityHidden(true)
            }
        } else {
          toolRows
        }
      }
    }.frame(maxWidth: .infinity, alignment: .leading)
  }

  private var toolRows: some View {
    VStack(alignment: .leading, spacing: 0) {
      ForEach(messages) { message in
        ToolCallView(message: message, client: client, single: messages.count == 1,
          expanded: Binding(
            get: { expandedTools.contains(message.id) },
            set: { value in
              if value { expandedTools.insert(message.id) } else { expandedTools.remove(message.id) }
            }))
      }
    }.padding(.top, messages.count > 1 ? 4 : 0)
  }
}

private struct ToolActivityStyle {
  let category: String
  let verb: String
  let symbol: String
  let detailTitle: String

  init(_ kind: String?) {
    let style = SharedMobileRules.toolStyle(kind)
    category = L10n.tr(LocalizedText(stringLiteral: style.category)); verb = L10n.tr(LocalizedText(stringLiteral: style.verb)); detailTitle = L10n.tr(LocalizedText(stringLiteral: style.detailTitle))
    switch kind {
    case "commandExecution": symbol = "terminal"
    case "fileChange": symbol = "pencil"
    case "webSearch": symbol = "globe"
    default: symbol = "circle.grid.2x2"
    }
  }

}

struct ToolCallView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let message: ChatMessage
  var client: Client?
  var single = false
  @Binding var expanded: Bool
  @State private var hovered = false

  private var style: ToolActivityStyle { ToolActivityStyle(message.kind) }
  private var emphasized: Bool { !single && (expanded || hovered) }
  private var title: String { SharedMobileRules.toolTitle(message, single: single, native: NativeToolPresentationText.shared) }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Button { expanded.toggle() } label: {
        HStack(spacing: 8) {
          Image(systemName: style.symbol).frame(width: 18)
          Text(title).lineLimit(1).fontWeight(single || emphasized ? .semibold : .regular)
            .foregroundStyle(emphasized ? Color.primary : Palette.secondary)
          if single || expanded || hovered {
            Image(systemName: expanded ? "chevron.down" : "chevron.right")
              .font(.system(size: 11))
          }
          Spacer(minLength: 0)
        }.font(.callout)
          .foregroundStyle(Palette.secondary)
          .opacity(message.status == "inProgress" && !expanded ? 0.55 : 1)
          .frame(minHeight: 44).contentShape(Rectangle())
      }.buttonStyle(.plain)
        .onHover { hovered = $0 }
        .accessibilityIdentifier("tool-item-\(message.id)")
        .accessibilityLabel(title)
        .accessibilityValue(L10n.tr("\(statusLabel)，\(expanded ? L10n.tr("已展开") : L10n.tr("已折叠"))"))
      if expanded {
        VStack(alignment: .leading, spacing: 8) {
          Text(style.detailTitle).font(.subheadline).foregroundStyle(Palette.secondary)
          HStack(alignment: .top, spacing: 8) {
            Text(verbatim: (message.kind == "commandExecution" ? "$ " : "") + message.text)
              .font(.system(.subheadline, design: .monospaced)).lineLimit(2)
              .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            Button {
              UIPasteboard.general.string = message.kind == "commandExecution"
                ? message.text : message.detail.orEmpty
            } label: {
              Image(systemName: "doc.on.doc").font(.system(size: 13))
                .frame(width: 44, height: 44, alignment: .top).contentShape(Rectangle())
            }.buttonStyle(.plain).foregroundStyle(Palette.secondary)
              .accessibilityLabel(message.kind == "commandExecution" ? L10n.tr("复制命令") : L10n.tr("复制工具详情"))
              .accessibilityIdentifier("tool-copy-\(message.id)")
          }
          if message.detail.orEmpty.isEmpty {
            Text(message.status == "inProgress" ? L10n.tr("等待工具输出…") : L10n.tr("暂无详细输出"))
              .font(.caption).foregroundStyle(Palette.secondary)
          } else {
            ScrollView(.vertical) {
              ScrollView(.horizontal) {
                Text(verbatim: message.detail.orEmpty)
                  .font(.system(.subheadline, design: .monospaced)).textSelection(.enabled)
                  .foregroundStyle(Palette.secondary.opacity(0.75))
              }
            }.frame(maxHeight: 180)
          }
          HStack {
            Spacer(minLength: 0)
            if message.status == "inProgress" {
              ProgressView().controlSize(.mini)
              Text(statusLabel)
            } else {
              Label(statusLabel, systemImage: statusSymbol)
            }
          }.font(.subheadline).foregroundStyle(Palette.secondary)
        }.padding(12)
          .background(Palette.toolDetail, in: RoundedRectangle(cornerRadius: 12))
          .overlay {
            RoundedRectangle(cornerRadius: 12).strokeBorder(Color.primary.opacity(0.1), lineWidth: 1)
          }
          .accessibilityIdentifier("tool-detail-\(message.id)")
          .padding(.bottom, 8)
        if let client {
          ForEach(message.attachments ?? [], id: \.resourceIdentity) { file in
            RemoteFileView(client: client, file: file)
          }
        }
      }
    }
  }

  private var statusSymbol: String {
    switch message.status {
    case "completed": return "checkmark"
    case "failed": return "xmark"
    case "interrupted": return "stop"
    default: return "questionmark"
    }
  }
  private var statusLabel: String { L10n.tr(LocalizedText(stringLiteral: SharedMobileRules.toolStatus(message.status))) }
}

extension Optional where Wrapped == String {
  var orEmpty: String { self ?? "" }
}

struct MarkdownBody: View, Equatable {
  let text: String
  var body: some View {
    #if DEBUG
    let _ = { if HistoryScrollProbe.enabled { HistoryScrollProbe.shared.markdownBodies += 1 } }()
    #endif
    VStack(alignment: .leading, spacing: 8) {
      ForEach(Array(text.components(separatedBy: "```").enumerated()), id: \.offset) {
        index, part in
        if index % 2 == 1 {
          let lines = part.components(separatedBy: "\n")
          let language = lines.first ?? ""
          VStack(alignment: .leading, spacing: 6) {
            HStack {
              Text(language.isEmpty ? L10n.tr("代码") : language).font(.caption2)
                .foregroundStyle(Palette.secondary)
              Spacer()
              Button(L10n.tr("复制")) {
                UIPasteboard.general.string = lines.dropFirst().joined(
                  separator: "\n")
              }.font(.caption).accessibilityIdentifier("copy-code")
            }
            ScrollView(.horizontal) {
              Text(lines.dropFirst().joined(separator: "\n")).font(
                .system(.callout, design: .monospaced)
              ).textSelection(.enabled)
            }
          }.padding(12).background(
            Palette.sidebar, in: RoundedRectangle(cornerRadius: 12))
        } else {
          ForEach(Array(markdownBlocks(part).enumerated()), id: \.offset) { _, block in
            MarkdownBlockView(block: block).frame(maxWidth: .infinity, alignment: .leading)
          }
        }
      }
    }
  }
}

private enum MarkdownBlock {
  case paragraph(String)
  case heading(String, Int)
  case list(String, String, Int)
  case quote(String)
  case table([[String]])
  case divider
}

private func markdownCells(_ line: String) -> [String] {
  var cells = line.split(separator: "|", omittingEmptySubsequences: false).map {
    $0.trimmingCharacters(in: .whitespaces)
  }
  if cells.first == "" { cells.removeFirst() }
  if cells.last == "" { cells.removeLast() }
  return cells
}

private func markdownBlocks(_ source: String) -> [MarkdownBlock] {
  let lines = source.components(separatedBy: "\n")
  var result: [MarkdownBlock] = []
  var paragraph: [String] = []
  var index = 0
  func flush() {
    if !paragraph.isEmpty {
      result.append(.paragraph(paragraph.joined(separator: "\n")))
      paragraph = []
    }
  }
  while index < lines.count {
    let raw = lines[index]
    let line = raw.trimmingCharacters(in: .whitespaces)
    if line.isEmpty {
      flush()
      index += 1
      continue
    }
    if index + 1 < lines.count && line.contains("|") {
      let separators = markdownCells(lines[index + 1])
      if !separators.isEmpty
        && separators.allSatisfy({ $0.range(of: "^:?-{3,}:?$", options: .regularExpression) != nil }
        )
      {
        flush()
        var rows = [markdownCells(line)]
        index += 2
        while index < lines.count && lines[index].contains("|") {
          let cells = markdownCells(lines[index])
          if cells.count != rows[0].count { break }
          rows.append(cells)
          index += 1
        }
        result.append(.table(rows))
        continue
      }
    }
    let level = line.prefix { $0 == "#" }.count
    if (1...6).contains(level) && line.dropFirst(level).first == " " {
      flush()
      result.append(.heading(String(line.dropFirst(level + 1)), level))
    } else if ["---", "***", "___"].contains(line) {
      flush()
      result.append(.divider)
    } else if line.hasPrefix(">") {
      flush()
      result.append(.quote(String(line.dropFirst()).trimmingCharacters(in: .whitespaces)))
    } else if let marker = line.range(of: "^(?:[-+*]|[0-9]+[.)])\\s+", options: .regularExpression)
    {
      flush()
      let prefix = String(line[marker]).trimmingCharacters(in: .whitespaces)
      let indent = min(5, (raw.count - raw.drop { $0 == " " }.count) / 2)
      result.append(
        .list(
          String(line[marker.upperBound...]), ["-", "+", "*"].contains(prefix) ? "•" : prefix,
          indent))
    } else {
      paragraph.append(raw)
    }
    index += 1
  }
  flush()
  return result
}

private struct MarkdownBlockView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let block: MarkdownBlock
  var body: some View {
    switch block {
    case .paragraph(let text): MarkdownParagraph(text: text)
    case .heading(let text, let level):
      MarkdownParagraph(text: text).font(level == 1 ? .title2 : level == 2 ? .title3 : .headline)
        .fontWeight(.semibold).padding(.top, 4).accessibilityIdentifier("markdown-heading")
    case .list(let text, let marker, let indent):
      HStack(alignment: .top, spacing: 8) {
        Text(marker).frame(minWidth: 14, alignment: .trailing)
        MarkdownParagraph(text: text)
      }.padding(.leading, CGFloat(indent * 16)).accessibilityIdentifier("markdown-list")
    case .quote(let text):
      HStack(alignment: .top, spacing: 10) {
        RoundedRectangle(cornerRadius: 2).fill(Color.secondary.opacity(0.4)).frame(width: 3)
        MarkdownParagraph(text: text).foregroundStyle(Palette.secondary)
      }.fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("markdown-quote")
    case .divider: Divider().padding(.vertical, 4)
    case .table(let rows):
      ScrollView(.horizontal) {
        VStack(spacing: 0) {
          ForEach(Array(rows.enumerated()), id: \.offset) { rowIndex, cells in
            HStack(alignment: .top, spacing: 0) {
              ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                MarkdownParagraph(text: cell).font(.subheadline)
                  .fontWeight(rowIndex == 0 ? .semibold : .regular)
                  .frame(width: 136, alignment: .leading).padding(10)
              }
            }.background(rowIndex == 0 ? Palette.sidebar : Palette.background)
            Divider()
          }
        }.overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.2)))
      }.accessibilityIdentifier("markdown-table")
    }
  }
}

struct MarkdownParagraph: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @Environment(\.openingMessageLink) private var openingLink
  @Environment(\.openingMessageLinkAnchor) private var openingAnchor
  @Environment(\.messageLinkOpen) private var messageLinkOpen
  @Environment(\.openURL) private var openURL
  let text: String
  @State private var formatted: AttributedString?
  @State private var linkAnchor = UUID()
  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      Group {
        if let formatted { Text(highlighted(formatted)) } else { Text(verbatim: text) }
      }.textSelection(.enabled).lineSpacing(4).tint(Palette.link)
      if containsOpeningLink {
        HStack(spacing: 8) {
          ProgressView().controlSize(.small)
          Text(L10n.tr("正在下载并打开文件…")).font(.caption).foregroundStyle(Palette.secondary)
        }.accessibilityIdentifier("message-link-loading")
      }
    }
      .environment(\.openURL, OpenURLAction { url in
        if let messageLinkOpen { messageLinkOpen(url, linkAnchor) }
        else { openURL(url) }
        return .handled
      })
      .task(id: text) {
        // Partial streams stay plain; completed Markdown is parsed away from the UI thread.
        let input = text
        let parsed = await Task.detached(priority: .userInitiated) {
          try? AttributedString(
            markdown: input,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
        }.value
        if !Task.isCancelled { formatted = parsed }
      }
  }
  private var containsOpeningLink: Bool {
    guard openingAnchor == linkAnchor, let openingLink, let formatted else { return false }
    return formatted.runs.contains { $0.link == openingLink }
  }
  private func highlighted(_ input: AttributedString) -> AttributedString {
    guard openingAnchor == linkAnchor, let openingLink else { return input }
    var result = input
    for run in input.runs where run.link == openingLink {
      result[run.range].backgroundColor = Palette.link.opacity(0.16)
      result[run.range].underlineStyle = .single
    }
    return result
  }
}

struct RemoteFileView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let client: Client
  let file: RemoteAttachment
  @State private var preview: FilePreviewItem?
  @State private var share: PreparedFileShare?
  @State private var image: UIImage?
  @State private var loading = false
  @State private var error: String?
  @State private var downloadTask: Task<Void, Never>?
  @State private var downloadGeneration = UUID()
  var body: some View {
    VStack(alignment: .leading, spacing: 5) {
      if let image {
        Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220)
          .clipShape(RoundedRectangle(cornerRadius: 12)).onTapGesture {
            prepare(.preview)
          }
          .accessibilityIdentifier("received-image-\(file.name)")
      }
      HStack(spacing: 8) {
        Button { prepare(.preview) } label: {
          HStack(spacing: 8) {
            fileSummary
            Spacer()
            Text(L10n.tr("预览")).font(.caption)
          }.frame(minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(loading).accessibilityIdentifier("preview-\(file.name)")
        Button { prepare(.share) } label: {
          Group {
            if loading { ProgressView() }
            else { Image(systemName: "square.and.arrow.up") }
          }.frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(loading)
        .accessibilityLabel(L10n.tr("保存或分享文件"))
        .accessibilityIdentifier("share-\(file.name)")
      }
      if loading {
        Text(L10n.tr("正在准备文件…")).font(.caption).foregroundStyle(Palette.secondary)
          .accessibilityIdentifier("file-preparation-loading")
      }
      if let error { Text(error).font(.caption2).foregroundStyle(.red) }
    }.padding(10).background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 12))
      .filePreview($preview)
      .sheet(item: $share) { FileShareSheet(url: $0.url) }
      .task(id: file.id) {
        if file.mediaType.hasPrefix("image/"), !loading {
          loading = true
          await download(.image)
        }
      }
      .onDisappear { downloadTask?.cancel(); downloadTask = nil; loading = false; downloadGeneration = UUID() }
      .onChange(of: client.id) { _, _ in reset() }
      .onChange(of: client.readingTarget) { _, _ in reset() }
      .onChange(of: client.agentConnection?.target) { _, _ in reset() }
  }
  private var fileSummary: some View {
    HStack(spacing: 8) {
      Image(systemName: file.mediaType.hasPrefix("image/") ? "photo" : "doc")
      VStack(alignment: .leading) {
        Text(file.name).font(.caption).lineLimit(1)
        Text(ByteCountFormatter.string(fromByteCount: Int64(file.sizeBytes), countStyle: .file))
          .font(.caption2).foregroundStyle(Palette.secondary)
      }
    }
  }
  private enum FileAction { case image, preview, share }
  private func reset() {
    downloadTask?.cancel(); downloadTask = nil; loading = false
    downloadGeneration = UUID()
    preview = nil; share = nil; image = nil; error = nil
  }
  private func prepare(_ action: FileAction) {
    guard !loading else { return }
    loading = true
    error = nil
    UISelectionFeedbackGenerator().selectionChanged()
    downloadTask = Task { await download(action) }
  }
  private func download(_ action: FileAction) async {
    let generation = downloadGeneration
    error = nil
    defer { if !Task.isCancelled && generation == downloadGeneration { loading = false; downloadTask = nil } }
    do {
      let downloaded = try await AgentClients.resolve(client).download(client, file: file)
      guard !Task.isCancelled, generation == downloadGeneration else { return }
      if file.mediaType.hasPrefix("image/") {
        image = UIImage(contentsOfFile: downloaded.path)
      }
      switch action {
      case .image: break
      case .preview: preview = FilePreviewItem(url: downloaded, file: file, client: client)
      case .share: share = PreparedFileShare(url: downloaded)
      }
    } catch {
      guard !Task.isCancelled, generation == downloadGeneration else { return }
      self.error = error.localizedDescription
    }
  }
}

struct ReplyDeliveryRecordsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let target: String
  @Environment(\.dismiss) private var dismiss
  var body: some View {
    NavigationStack {
      List(store.detachedReplies(for: target).reversed()) { record in
        VStack(alignment: .leading, spacing: 8) {
          Text(record.original.text).textSelection(.enabled)
          ForEach(record.original.sourceAttachments ?? []) { attachment in
            Label(attachment.name, systemImage: attachment.isImage ? "photo" : "doc").font(.caption)
          }
          Text(status(record.receipt)).font(.caption).foregroundStyle(Palette.secondary)
            .accessibilityIdentifier("reply-record-state-" + record.id)
          if let error = record.error { Text(error).font(.caption).textSelection(.enabled) }
          Button(L10n.tr("查询原回执")) { store.queryDetachedReply(record, target: target) }
            .accessibilityIdentifier("query-detached-reply-" + record.id)
        }.accessibilityElement(children: .contain).accessibilityIdentifier("reply-record-" + record.id)
      }
      .navigationTitle(L10n.tr("发送记录"))
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L10n.tr("完成")) { dismiss() } } }
    }
  }
  private func status(_ receipt: DeliveryReceipt?) -> String {
    switch receipt?.state {
    case "failed": return receipt?.failureTitle ?? L10n.tr("消息提交失败")
    case "cancelled", "interrupted": return L10n.tr("已停止")
    case "completed": return L10n.tr("已完成")
    case "submitted": return L10n.tr("已提交")
    case "queued": return L10n.tr("已排队")
    default: return L10n.tr("已结束等待 · 结果待确认")
    }
  }
}

struct RuntimePanel: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let runtime: ThreadRuntime
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      if store.isRunning && !store.messages.contains(where: {
        $0.kind == "contextCompaction" && $0.status == "inProgress"
      }) {
        HStack {
          if runtime.state == "running" { ProgressView().controlSize(.small) }
          Text(
            store.client?.online != true
              ? L10n.tr("执行状态等待同步")
              : runtime.state == "waiting-for-approval" ? L10n.tr("等待你的处理") : L10n.tr("\(store.clientTypeName) 正在执行")
          ).font(
            .caption)
          Spacer()
        }.accessibilityIdentifier("runtime-progress")
      }
      if !runtime.plan.isEmpty {
        DisclosureGroup(
          L10n.tr("执行计划 · \(runtime.plan.filter { $0.status == "completed" }.count)/\(runtime.plan.count)")
        ) {
          ForEach(Array(runtime.plan.enumerated()), id: \.offset) { _, step in
            Label(
              step.step,
              systemImage: step.status == "completed"
                ? "checkmark.circle.fill"
                : ["in_progress", "inProgress"].contains(step.status) ? "circle.dotted" : "circle"
            )
            .font(.caption).frame(maxWidth: .infinity, alignment: .leading).padding(
              .vertical, 2)
          }
        }.font(.caption).padding(10).background(
          Palette.sidebar, in: RoundedRectangle(cornerRadius: 12)
        )
        .accessibilityIdentifier("runtime-plan")
        .accessibilityLabel(
          L10n.tr("执行计划 · \(runtime.plan.filter { $0.status == "completed" }.count)/\(runtime.plan.count)"))
      }
      ForEach(runtime.approvals.filter { $0.interactionMode != "async" }) { approval in ApprovalCard(store: store, approval: approval, targetKey: store.targetKey).id(store.targetKey + ":" + approval.id)
      }
      if let plan = runtime.proposedPlan, !store.isRunning {
        PlanReviewCard(store: store, plan: plan).id(plan.id)
      }
      ForEach(store.receiptNotices) { receipt in
        let target = store.targetKey
        HStack(alignment: .top, spacing: 8) {
          VStack(alignment: .leading, spacing: 4) {
            if let queueError = receipt.queueSyncError, !queueError.isEmpty {
              Text(L10n.tr("客户端队列同步失败")).font(.caption)
              Text(queueError).font(.caption).foregroundStyle(Palette.secondary)
            } else if receipt.state == "uncertain" && receipt.noticeContext == "historical" {
              DisclosureGroup(L10n.tr("历史消息结果待核对")) {
                Text(L10n.tr("电脑已继续后续任务。这条旧消息是否执行仍缺少确认，不代表当前任务在等待；不会自动重发。"))
                  .font(.caption).foregroundStyle(Palette.secondary)
                if let text = receipt.text, !text.isEmpty {
                  Text(text).font(.caption).textSelection(.enabled)
                }
              }.font(.caption)
            } else {
              Text(receipt.state == "uncertain" ? L10n.tr("消息执行结果待确认") : receipt.failureTitle)
                .font(.caption)
            }
            if let error = receipt.error, !error.isEmpty {
              Text(error).font(.caption).foregroundStyle(Palette.secondary).textSelection(.enabled)
            }
          }.frame(maxWidth: .infinity, alignment: .leading)
          if receipt.state == "failed" {
            Button { store.dismissReceiptFailure(receipt, expectedTargetKey: target) } label: {
              Image(systemName: "xmark").font(.caption).frame(width: 44, height: 44)
            }.buttonStyle(.plain).foregroundStyle(Palette.secondary)
              .accessibilityLabel(L10n.tr("关闭失败提示"))
              .accessibilityIdentifier("dismiss-receipt-\(receipt.id)")
          }
        }.padding(10).background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 10))
          .accessibilityElement(children: .contain)
          .accessibilityIdentifier("receipt-notice-\(receipt.id)")
      }
      if let error = runtime.error {
        Text(error).font(.caption).foregroundStyle(.red).textSelection(.enabled)
      }
    }
  }
}

struct ProposedPlanMessageView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let message: ChatMessage
  var body: some View {
    DisclosureGroup {
      MarkdownBody(text: message.text).padding(.top, 8)
      Button(L10n.tr("复制计划"), systemImage: "doc.on.doc") { UIPasteboard.general.string = message.text }
        .font(.caption).padding(.top, 4)
    } label: {
      Label(message.status == "inProgress" ? L10n.tr("正在制定计划…") : L10n.tr("计划"), systemImage: "lightbulb")
        .font(.subheadline)
    }.padding(12).background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 16))
      .accessibilityIdentifier("proposed-plan-message")
  }
}

struct PlanReviewCard: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let plan: ProposedPlan
  @State private var feedback = ""
  @State private var adjusting = false
  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      Text(L10n.tr("实施此计划？")).font(.subheadline.weight(.semibold))
      if !store.messages.contains(where: { $0.kind == "plan" && $0.text == plan.text }) {
        DisclosureGroup(L10n.tr("查看计划")) { MarkdownBody(text: plan.text).padding(.top, 8) }
      }
      Button { store.implementPlan(plan) } label: {
        HStack {
          Image(systemName: "play")
          Text(L10n.tr("是，实施此计划"))
          Spacer()
          Image(systemName: "arrow.right")
        }.font(.subheadline).padding(12)
          .background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 12))
      }.buttonStyle(.plain).disabled(!store.canRespondToPlan(plan))
        .accessibilityIdentifier("implement-plan")
      if adjusting {
        TextField(L10n.tr("告诉助手应该如何调整计划"), text: $feedback, axis: .vertical)
          .lineLimit(2...5).font(.subheadline).textFieldStyle(.roundedBorder)
          .accessibilityIdentifier("plan-feedback")
        Button(L10n.tr("发送调整"), systemImage: "arrow.up") { store.revisePlan(plan, feedback: feedback) }
          .disabled(!store.canRespondToPlan(plan) || feedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
          .accessibilityIdentifier("submit-plan-feedback")
      } else {
        Button(L10n.tr("调整计划"), systemImage: "pencil") { adjusting = true }
          .font(.subheadline).disabled(!store.canRespondToPlan(plan))
          .accessibilityIdentifier("adjust-plan")
      }
    }.padding(14).background(Palette.background, in: RoundedRectangle(cornerRadius: 18))
      .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.primary.opacity(0.1)))
      .accessibilityElement(children: .contain)
      .accessibilityIdentifier("plan-review")
  }
}

private struct ApprovalAnswerDraft {
  var answers: [String: [String]] = [:]
  var customAnswers: [String: String] = [:]
}

private struct ApprovalSurface: ViewModifier {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  var verticalPadding: CGFloat = 12
  func body(content: Content) -> some View {
    content.padding(.horizontal, 12).padding(.vertical, verticalPadding)
      .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
      .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.orange.opacity(0.2)))
  }
}

private struct AsyncQuestionDeck: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let approvals: [Approval]
  let targetKey: String
  let onPresent: () -> Void
  @State private var selectedId: String?
  @State private var drafts: [String: ApprovalAnswerDraft] = [:]
  @State private var isPresented = false
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private var selectedIndex: Int {
    approvals.firstIndex { $0.id == selectedId } ?? 0
  }
  private var current: Approval? { approvals.indices.contains(selectedIndex) ? approvals[selectedIndex] : nil }

  var body: some View {
    Button { present() } label: {
      HStack(spacing: 8) {
        Label(L10n.tr("\(approvals.count) 个问题待回答"), systemImage: "questionmark.bubble")
          .foregroundStyle(Color.orange)
        Spacer()
        Image(systemName: "chevron.right").foregroundStyle(Palette.secondary)
      }.font(.subheadline).frame(minHeight: 44).contentShape(Rectangle())
    }.buttonStyle(.plain).modifier(ApprovalSurface(verticalPadding: 0))
      .background(Palette.background, in: RoundedRectangle(cornerRadius: 12))
      .accessibilityIdentifier("open-async-questions")
      .onAppear {
        selectedId = approvals.first?.id
        if store.sheet == nil && !store.drawerOpen { present() }
      }
      .onChange(of: approvals) { old, new in
        let previous = old.map(\.id), updated = new.map(\.id)
        drafts = drafts.filter { key, _ in
          guard let before = old.first(where: { $0.id == key }), let after = new.first(where: { $0.id == key }) else { return false }
          return before == after
        }
        if let selectedId, updated.contains(selectedId) { return }
        let previousIndex = previous.firstIndex(of: selectedId ?? "") ?? 0
        selectedId = updated.isEmpty ? nil : updated[min(previousIndex, updated.count - 1)]
        if updated.isEmpty { isPresented = false }
      }
      .onChange(of: store.sheet) { _, sheet in
        if sheet != nil { isPresented = false }
      }
      .sheet(isPresented: $isPresented) {
        VStack(spacing: 4) {
          HStack(spacing: 0) {
            Label(L10n.tr("问题"), systemImage: "questionmark.bubble")
              .font(.subheadline.weight(.semibold)).foregroundStyle(Color.orange)
            Spacer(minLength: 0)
            IconButton(symbol: "chevron.left", label: L10n.tr("上一题"), id: "async-question-prev") { move(-1) }
              .disabled(selectedIndex == 0 || current.map { store.isAnswering($0) } == true)
            Text("\(selectedIndex + 1) / \(approvals.count)").font(.caption.monospacedDigit())
              .foregroundStyle(Palette.secondary).accessibilityIdentifier("async-question-page")
            IconButton(symbol: "chevron.right", label: L10n.tr("下一题"), id: "async-question-next") { move(1) }
              .disabled(selectedIndex + 1 >= approvals.count || current.map { store.isAnswering($0) } == true)
            IconButton(symbol: "xmark", label: L10n.tr("收起问题"), id: "close-async-questions") { isPresented = false }
          }.padding(.horizontal, 16).padding(.top, 12)
          if let current {
              ApprovalCard(store: store, approval: current, targetKey: targetKey, scrollsContent: true,
                externalDraft: Binding(get: { drafts[current.id] ?? ApprovalAnswerDraft() },
                  set: { drafts[current.id] = $0 }))
                .id(current.id).layoutPriority(1).padding(.horizontal, 16).padding(.bottom, 16)
                .simultaneousGesture(DragGesture(minimumDistance: 40).onEnded { value in
                  guard abs(value.translation.width) > 60,
                    abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                  move(value.translation.width < 0 ? 1 : -1)
                })
          }
          Spacer(minLength: 0)
        }.background(Palette.background).tint(.primary)
          .presentationDetents([.large])
          .presentationDragIndicator(.visible).presentationCornerRadius(24)
      }
  }

  private func present() {
    onPresent()
    isPresented = true
  }

  private func move(_ offset: Int) {
    guard let current, !store.isAnswering(current), approvals.indices.contains(selectedIndex + offset) else { return }
    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
      selectedId = approvals[selectedIndex + offset].id
    }
  }
}

struct ApprovalCard: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let approval: Approval
  let targetKey: String
  let scrollsContent: Bool
  private let actionContext: SessionStore.ApprovalActionContext
  fileprivate var externalDraft: Binding<ApprovalAnswerDraft>? = nil
  @State private var localDraft = ApprovalAnswerDraft()
  @State private var contentHeight: CGFloat = 1000
  @State private var recordingQuestionId: String?
  fileprivate init(store: SessionStore, approval: Approval, targetKey: String,
    scrollsContent: Bool = false, externalDraft: Binding<ApprovalAnswerDraft>? = nil) {
    self.store = store
    self.approval = approval
    self.targetKey = targetKey
    self.scrollsContent = scrollsContent
    self.actionContext = store.approvalActionContext
    self.externalDraft = externalDraft
  }
  private var draft: Binding<ApprovalAnswerDraft> { externalDraft ?? $localDraft }
  private var isAsync: Bool { approval.interactionMode == "async" }
  private var submittedAnswers: [String: [String]] {
    Dictionary(uniqueKeysWithValues: approval.questions.map { question in
      let custom = (draft.wrappedValue.customAnswers[question.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
      return (question.id, isAsync && !custom.isEmpty ? [custom] : (draft.wrappedValue.answers[question.id] ?? []) + (custom.isEmpty ? [] : [custom]))
    })
  }
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      if scrollsContent {
        ScrollView {
          questionContent(includeAnswerFields: false)
            .frame(maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }.frame(maxHeight: contentHeight)
          .scrollDismissesKeyboard(.interactively)
          .accessibilityIdentifier("question-content")
        ForEach(approval.questions) { question in answerField(question) }
      } else {
        questionContent(includeAnswerFields: true)
      }
      answerActions.fixedSize(horizontal: false, vertical: true)
    }.disabled(!store.canEditApproval(approval, context: actionContext)).modifier(ApprovalSurface())
      .onChange(of: approval) { _, _ in draft.wrappedValue = ApprovalAnswerDraft() }
  }

  private func questionContent(includeAnswerFields: Bool) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      if !isAsync {
        Label(approval.kind == "question" ? L10n.tr("需要回复") : L10n.tr("需要审批"), systemImage: "hand.raised")
          .font(.caption).foregroundStyle(Color.orange)
      }
      if !isAsync { Text(approval.title).font(.subheadline).textSelection(.enabled) }
      if isAsync {
        Text(store.runtime?.turnId == approval.turnId ? L10n.tr("任务仍在继续，可随时回答或跳过") : L10n.tr("任务已结束，回答将继续此对话"))
          .font(.caption).foregroundStyle(Palette.secondary).accessibilityIdentifier("async-question-status")
      }
      if store.isAnswering(approval) {
        HStack { ProgressView(); Text(L10n.tr("正在提交…")).font(.caption) }.accessibilityIdentifier("approval-pending")
      }
      if !approval.detail.isEmpty {
        Text(approval.detail).font(.caption).foregroundStyle(Palette.secondary)
      }
      ForEach(approval.questions) { question in
        Text(question.question).font(.subheadline).accessibilityIdentifier("question-title-\(question.id)")
        if let options = question.options {
          ForEach(options, id: \.label) { option in
            Button {
              if question.multiple == true {
                if draft.wrappedValue.answers[question.id, default: []].contains(option.label) { draft.wrappedValue.answers[question.id]?.removeAll { $0 == option.label } }
                else { draft.wrappedValue.answers[question.id, default: []].append(option.label) }
              } else { draft.wrappedValue.answers[question.id] = [option.label] }
              if isAsync { draft.wrappedValue.customAnswers[question.id] = "" }
            } label: {
              HStack {
                Image(
                  systemName: draft.wrappedValue.answers[question.id, default: []].contains(option.label)
                    ? "checkmark.circle.fill" : "circle")
                VStack(alignment: .leading) {
                  Text(option.label)
                  if !option.description.isEmpty { Text(option.description).font(.caption2).foregroundStyle(Palette.secondary) }
                }
                Spacer(minLength: 0)
              }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).font(.subheadline)
              .disabled(recordingQuestionId != nil)
              .accessibilityLabel(option.label).accessibilityHint(option.description)
              .accessibilityValue(draft.wrappedValue.answers[question.id, default: []].contains(option.label) ? L10n.tr("已选择") : L10n.tr("未选择"))
              .accessibilityIdentifier("question-option-\(question.id)-\(option.label)")
          }
        }
        if includeAnswerFields { answerField(question) }
      }
    }
  }

  @ViewBuilder private func answerField(_ question: ApprovalQuestion) -> some View {
    if question.custom != false {
      QuestionAnswerField(store: store, approval: approval, question: question, targetKey: targetKey, actionContext: actionContext,
        text: Binding(get: { draft.wrappedValue.customAnswers[question.id] ?? "" },
          set: { draft.wrappedValue.customAnswers[question.id] = $0 }),
        recordingQuestionId: $recordingQuestionId)
        .id(approval.id + ":" + question.id).fixedSize(horizontal: false, vertical: true)
    }
  }

  private var answerActions: some View {
    Group {
      if approval.kind == "question" {
        HStack(spacing: 12) {
        if isAsync {
          Button(L10n.tr("跳过")) { store.answer(approval, decision: "skip", expectedTargetKey: targetKey, expectedContext: actionContext) }
            .font(.subheadline).frame(minWidth: 44, minHeight: 44).accessibilityIdentifier("skip-question")
        }
        Button {
          store.answer(approval, decision: "", answers: submittedAnswers, expectedTargetKey: targetKey, expectedContext: actionContext)
        } label: { Text(L10n.tr("提交回复")).frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle()) }
        .buttonStyle(ApprovalSubmitButtonStyle())
        .disabled(!approval.questions.allSatisfy { !(submittedAnswers[$0.id] ?? []).isEmpty })
        .accessibilityIdentifier("answer-question")
        }
      } else {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), alignment: .leading)], alignment: .leading) {
          ForEach(approval.decisions, id: \.self) { decision in
            Button(
              (approval.decisionLabels?[decision]).map { label in
                switch label {
                case "始终允许": return L10n.tr("始终允许")
                case "允许本轮": return L10n.tr("允许本轮")
                case "本会话允许": return L10n.tr("本会话允许")
                case "允许一次": return L10n.tr("允许一次")
                case "拒绝": return L10n.tr("拒绝")
                case "取消": return L10n.tr("取消")
                default: return label
                }
              } ?? (decision == "accept" || decision == "approve"
                ? L10n.tr("允许一次")
                : decision == "acceptForSession"
                  ? L10n.tr("本会话允许") : decision == "decline" || decision == "deny" ? L10n.tr("拒绝") : L10n.tr("停止任务"))
            ) {
              store.answer(approval, decision: decision, expectedTargetKey: targetKey, expectedContext: actionContext)
            }.font(.caption).frame(minHeight: 44).accessibilityIdentifier(
              "approval-\(decision)")
          }
        }
      }
    }.disabled(recordingQuestionId != nil || !store.canAnswer(approval, context: actionContext))
  }
}

private struct QuestionAnswerField: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  let approval: Approval
  let question: ApprovalQuestion
  let targetKey: String
  let actionContext: SessionStore.ApprovalActionContext
  @Binding var text: String
  @Binding var recordingQuestionId: String?
  @StateObject private var voice = VoiceInputController()
  @EnvironmentObject private var composerVoice: VoiceInputController
  @FocusState private var focused: Bool
  @Environment(\.scenePhase) private var scenePhase

  private var voiceTarget: String { targetKey + ":" + approval.id + ":" + question.id }

  var body: some View {
    VStack(spacing: 4) {
      HStack(alignment: .bottom, spacing: 0) {
        Group {
          if question.isSecret == true {
            SecureField(L10n.tr("输入回复"), text: $text)
          } else {
            TextField(L10n.tr("输入回复或补充说明"), text: $text, axis: .vertical).lineLimit(1...4)
          }
        }.font(.subheadline).textFieldStyle(.plain).focused($focused)
          .disabled(voice.isActive).padding(.leading, 10).padding(.vertical, 10)
          .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          .accessibilityIdentifier("question-custom-\(question.id)")
        if question.isSecret != true && !voice.isActive {
          IconButton(symbol: "mic", label: L10n.tr("语音输入回复"),
            id: "question-voice-\(question.id)") {
            focused = false
            guard let provider = store.voiceProvider(draftId: voiceTarget) else { return }
            voice.start(target: voiceTarget, text: text, provider: provider) { target, value in
              guard target == voiceTarget, store.targetKey == targetKey,
                text == voice.originalText,
                store.canEditApproval(approval, context: actionContext) else { return false }
              text = value
              return true
            }
          }.disabled(composerVoice.isActive
            || (recordingQuestionId != nil && recordingQuestionId != question.id))
        }
      }
      if voice.isActive {
        VoiceInputControls(voice: voice, text: text, questionId: question.id).padding(4)
      }
    }.background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 10))
      .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.primary.opacity(0.12)))
      .onChange(of: voice.isActive) { _, active in
        if active { recordingQuestionId = question.id }
        else if recordingQuestionId == question.id { recordingQuestionId = nil }
        UIApplication.shared.isIdleTimerDisabled = (active || composerVoice.isActive) && scenePhase == .active
      }
      .onChange(of: composerVoice.isActive) { _, active in if active { voice.cancel() } }
      .onChange(of: scenePhase) { _, phase in
        if phase == .background || (phase == .inactive && voice.phase != .requesting) { voice.suspend() }
      }
      .onChange(of: store.targetKey) { _, _ in voice.cancel() }
      .onChange(of: approval) { _, _ in voice.cancel() }
      .onChange(of: store.approvalActionContext) { _, _ in voice.cancel() }
      .onDisappear {
        voice.cancel()
        if recordingQuestionId == question.id { recordingQuestionId = nil }
        UIApplication.shared.isIdleTimerDisabled = composerVoice.isActive && scenePhase == .active
      }
  }
}

private struct ApprovalSubmitButtonStyle: ButtonStyle {
  var fill = Color.primary
  @Environment(\.isEnabled) private var isEnabled
  func makeBody(configuration: Configuration) -> some View {
    configuration.label.font(.subheadline.weight(.semibold))
      .foregroundStyle(isEnabled ? Palette.background : Palette.secondary)
      .background(isEnabled ? fill : Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 10))
      .opacity(configuration.isPressed ? 0.75 : 1)
  }
}

#Preview { RootView() }


private struct DesktopSuggestionDetails: View {
  let action: DesktopSuggestionDraftAction
  let canApply: Bool
  let apply: (DesktopSuggestionAdoptionMode) -> Void
  let ignore: () -> Void
  @Environment(\.dismiss) private var dismiss
  @State private var confirmReplacement = false

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 14) {
          Text(L10n.tr("来自桌面的建议")).font(.caption).foregroundStyle(Palette.secondary)
          Text(action.suggestion.text).font(.body).textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("suggestion-full-text")
        }.padding(20)
      }
      .navigationTitle(L10n.tr("下一步建议")).navigationBarTitleDisplayMode(.inline)
      .toolbar { ToolbarItem(placement: .confirmationAction) {
        Button(L10n.tr("完成")) { dismiss() }.accessibilityIdentifier("suggestion-details-close")
      } }
      .safeAreaInset(edge: .bottom) {
        VStack(spacing: 4) {
          Button {
            apply(action.draft.text.isEmpty ? .fill : .append)
          } label: {
            Text(action.draft.text.isEmpty ? L10n.tr("采用到输入框") : L10n.tr("追加到草稿末尾"))
              .frame(maxWidth: .infinity, minHeight: 44)
          }.buttonStyle(ApprovalSubmitButtonStyle(fill: Color(uiColor: .label)))
            .disabled(!canApply).accessibilityIdentifier("suggestion-apply")
          if !action.draft.text.isEmpty {
            Button { confirmReplacement = true } label: {
              Text(L10n.tr("替换现有草稿…")).frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
            }.disabled(!canApply).accessibilityIdentifier("suggestion-replace")
          }
          Button { ignore() } label: {
            Text(L10n.tr("忽略这条建议")).font(.subheadline).foregroundStyle(Palette.secondary)
              .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
          }.accessibilityIdentifier("suggestion-ignore")
        }.padding(.horizontal, 20).padding(.bottom, 8).background(Palette.background)
      }
      .alert(L10n.tr("替换现有草稿？"), isPresented: $confirmReplacement) {
        Button(L10n.tr("确认替换"), role: .destructive) { apply(.replace) }
          .accessibilityIdentifier("suggestion-confirm-replace")
        Button(L10n.tr("保留原草稿"), role: .cancel) {}.accessibilityIdentifier("suggestion-cancel-replace")
      } message: { Text(L10n.tr("原草稿将被替换，填入后仍可编辑。")) }
    }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
  }
}

@available(iOS 18.0, *)
private struct SuggestionSelectableTextField: View {
  let prompt: String
  @Binding var text: String
  var focused: FocusState<Bool>.Binding
  let insertionRequest: UUID
  @State private var selection: TextSelection?
  var body: some View {
    TextField(prompt, text: $text, selection: $selection, axis: .vertical).focused(focused)
      .onChange(of: insertionRequest) { _, _ in selection = TextSelection(insertionPoint: text.endIndex) }
  }
}

private struct ComposerView: View {
  private struct HandledSuggestion: Hashable {
    let target: String
    let version: String
  }
  private struct SuggestionControlIdentity: Hashable {
    let navigation: String
    let connection: String
    let handled: HandledSuggestion
    let revision: UInt64
  }
  private struct SuggestionUndoIdentity: Hashable {
    let target: String
    let revision: UInt64
  }
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @State private var creationWaitToDetach: (target: String, operationId: String)?
  @ObservedObject var suggestionReader: DesktopSuggestionReader
  @State private var suggestionPreview: DesktopSuggestionDraftAction?
  @State private var handledSuggestions: Set<HandledSuggestion> = []
  @State private var insertionRequest = UUID()
  @State private var focusAfterSuggestionSheet = false
  @ObservedObject var store: SessionStore
  @ObservedObject var drafts: ComposerDraftStore
  @ObservedObject var voice: VoiceInputController
  @StateObject private var attachmentPhotos = RecentAttachmentPhotos()
  @State private var attachmentRequest: QuickAttachmentRequest?
  @State private var draftImagePreview: DraftAttachmentPreviewRequest?
  @Binding var attachmentModalPresented: Bool
  @Binding var showComposerActions: Bool
  @FocusState.Binding var composerFocused: Bool
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    // Presentation values live for this render only. User actions still recheck live state.
    let context = suggestionReadContext
    let available = voice.isActive ? nil : availableSuggestion(in: context)
    let action = context.enabled ? context.scope.flatMap { scope in available.map {
      DesktopSuggestionDraftAction(scope: scope, suggestion: $0, draft: store.suggestionDraftSnapshot)
    } } : nil
    let presentation = SuggestionPresentation(context: context, available: available,
      showsGhost: store.draft.text.isEmpty && available != nil, action: action)
    return composer(presentation)
      .modifier(QuickAttachmentPresentation(store: store, library: attachmentPhotos,
        request: attachmentRequest, isPresenting: $attachmentModalPresented))
      .fullScreenCover(item: $draftImagePreview, onDismiss: { attachmentModalPresented = false }) { request in
        DraftAttachmentImagePreview(store: store, drafts: drafts, request: request)
      }
      .onChange(of: store.targetKey) { _, _ in draftImagePreview = nil }
      .task { attachmentPhotos.setActive(scenePhase == .active) }
      .onChange(of: scenePhase) { _, phase in attachmentPhotos.setActive(phase == .active) }
      .task(id: context) {
        // A cancelled nil-scope task must not erase a value that history has
        // already confirmed while SwiftUI was laying out the new conversation.
        guard !Task.isCancelled else { return }
        guard UIApplication.shared.applicationState == .active else { suggestionReader.clear(reason: "inactive"); return }
        guard let scope = suggestionReadContext.scope, let client = store.client else {
          if store.nextTurnSuggestionPresentationScope == nil {
            #if DEBUG
            suggestionReader.clear(reason: "source-" + store.suggestionUnavailableReason)
            #else
            suggestionReader.clear(reason: "source-unavailable")
            #endif
          }
          return
        }
        guard scope == store.nextTurnSuggestionPresentationScope else { return }
        guard suggestionReadContext.enabled else { suggestionReader.suspend(keeping: scope); return }
        await suggestionReader.read(scope) { try await store.readNextTurnSuggestion(client, scope: scope) }
      }
      .onChange(of: presentation.readScope) { _, _ in suggestionPreview = nil; focusAfterSuggestionSheet = false }
      .onChange(of: store.suggestionDraftSnapshot) { previous, current in
        if let action = suggestionPreview, action.draft != current { suggestionPreview = nil }
        // Explicit clears and local deletion advance the edit revision. The
        // automatic clear after sending must not revive the prior turn's hint.
        if previous.targetKey == current.targetKey, previous.revision != current.revision,
          current.text.isEmpty {
          handledSuggestions = Set(handledSuggestions.filter { $0.target != current.targetKey })
        }
      }
      .onChange(of: suggestionReader.suggestion) { _, current in
        if let action = suggestionPreview, current != action.suggestion { suggestionPreview = nil }
      }
      .onDisappear {
        attachmentPhotos.setActive(false)
        if let scope = suggestionReadContext.scope, suggestionReader.scope == scope { suggestionReader.suspend(keeping: scope) }
        suggestionPreview = nil
      }
      .sheet(item: $suggestionPreview, onDismiss: {
        if focusAfterSuggestionSheet, validSuggestionUndo != nil {
          composerFocused = true; insertionRequest = UUID()
        }
        focusAfterSuggestionSheet = false
      }) { action in
        DesktopSuggestionDetails(action: action, canApply: canApplySuggestion(action), apply: { mode in
          guard suggestionPreview?.id == action.id else { return }
          applySuggestion(action, mode: mode)
        }, ignore: {
          guard suggestionPreview?.id == action.id, canApplySuggestion(action) else { return }
          handledSuggestions.insert(suggestionKey(action.scope, action.suggestion, target: action.draft.targetKey)); suggestionPreview = nil
        })
      }
  }

  private struct SuggestionPresentation {
    let context: SuggestionReadContext
    let available: DesktopSuggestion?
    let showsGhost: Bool
    let action: DesktopSuggestionDraftAction?
    var readScope: DesktopSuggestionScope? { context.enabled ? context.scope : nil }
  }
  private struct SuggestionReadContext: Equatable {
    let scope: DesktopSuggestionScope?
    let enabled: Bool
  }
  private var suggestionReadContext: SuggestionReadContext {
    let liveScope = store.nextTurnSuggestionScope
    return SuggestionReadContext(scope: scenePhase == .active ? (liveScope ?? store.nextTurnSuggestionPresentationScope) : nil,
      enabled: liveScope != nil && !voice.isActive && !store.drawerOpen
        && store.sheet == nil && !attachmentModalPresented)
  }
  private var suggestionReadScope: DesktopSuggestionScope? {
    suggestionReadContext.enabled ? suggestionReadContext.scope : nil
  }
  private func suggestionKey(_ scope: DesktopSuggestionScope, _ value: DesktopSuggestion, target: String? = nil) -> HandledSuggestion {
    let key = target ?? store.targetKey
    return HandledSuggestion(target: key, version: value.handledVersionKey(targetKey: key, sourceTurnId: scope.sourceTurnId))
  }
  private func suggestionControlIdentity(_ action: DesktopSuggestionDraftAction?) -> SuggestionControlIdentity? {
    action.map { SuggestionControlIdentity(navigation: $0.scope.navigation, connection: $0.scope.connection,
      handled: suggestionKey($0.scope, $0.suggestion, target: $0.draft.targetKey), revision: $0.draft.revision) }
  }
  private func presentSuggestion(_ action: DesktopSuggestionDraftAction?) {
    guard let action, canApplySuggestion(action) else { return }
    composerFocused = false; suggestionPreview = action
  }
  private var availableSuggestion: DesktopSuggestion? {
    // No payload means no presentation scope to resolve. Nonempty values keep all original checks.
    guard suggestionReader.suggestion != nil else { return nil }
    return availableSuggestion(in: suggestionReadContext)
  }
  private func availableSuggestion(in context: SuggestionReadContext) -> DesktopSuggestion? {
    guard let scope = context.scope, let suggestion = suggestionReader.visibleSuggestion(for: scope),
      !handledSuggestions.contains(suggestionKey(scope, suggestion)) else { return nil }
    return suggestion
  }
  private var validSuggestionUndo: DesktopSuggestionDraftUndo? {
    guard let undo = drafts.suggestionUndo,
      undo.restoredText(currentDraft: store.suggestionDraftSnapshot) != nil else { return nil }
    return undo
  }
  private func canApplySuggestion(_ action: DesktopSuggestionDraftAction) -> Bool {
    suggestionReadScope == action.scope && store.suggestionDraftSnapshot == action.draft
      && availableSuggestion == action.suggestion && availableSuggestion?.isValid(at: Date()) == true
  }
  private func applySuggestion(_ action: DesktopSuggestionDraftAction, mode: DesktopSuggestionAdoptionMode) {
    guard suggestionPreview == nil || suggestionPreview?.id == action.id else { return }
    guard canApplySuggestion(action), store.adoptDesktopSuggestion(action, mode: mode, currentSuggestion: availableSuggestion) else {
      suggestionPreview = nil; return
    }
    handledSuggestions.insert(suggestionKey(action.scope, action.suggestion, target: action.draft.targetKey))
    focusAfterSuggestionSheet = suggestionPreview != nil
    suggestionPreview = nil
    if !focusAfterSuggestionSheet { composerFocused = true; insertionRequest = UUID() }
  }
  private func suggestionDraftControls(_ presentation: SuggestionPresentation) -> some View {
    HStack {
      if let undo = validSuggestionUndo {
        Text(L10n.tr("已填入输入框")).font(.caption).foregroundStyle(Palette.secondary)
        Spacer()
        Button {
          guard store.undoDesktopSuggestion(undo) else { return }
          handledSuggestions.remove(HandledSuggestion(target: undo.applied.targetKey, version: undo.handledVersionKey))
          composerFocused = true; insertionRequest = UUID()
        } label: {
          Text(L10n.tr("撤销")).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
        }.accessibilityIdentifier("suggestion-undo")
          .id(SuggestionUndoIdentity(target: undo.applied.targetKey, revision: undo.applied.revision))
      } else if presentation.available != nil && !store.draft.text.isEmpty {
        Button { presentSuggestion(presentation.action) } label: {
          Label(L10n.tr("下一步建议"), systemImage: "sparkles").font(.caption)
            .frame(minHeight: 44).contentShape(Rectangle())
        }.accessibilityIdentifier("suggestion-entry").disabled(presentation.readScope == nil)
          .id(suggestionControlIdentity(presentation.action))
        Spacer()
      }
    }.padding(.horizontal, 6).buttonStyle(.plain)
  }

  private var composerDetails: some View {
    VStack(spacing: 5) {
      if let client = store.client, store.isConnecting(client) {
        HStack(spacing: 5) {
          ProgressView().controlSize(.mini)
          Text(L10n.tr("\(store.connectionLabel(for: client)) 草稿保存在此对话。"))
        }.font(.caption2).foregroundStyle(Palette.secondary).accessibilityIdentifier("connection-status")
      } else if store.client?.isDemo == false && !store.isWritable && !store.canConnectForReply {
        Text(store.composerUnavailableMessage)
          .font(.caption2).foregroundStyle(Palette.secondary)
          .accessibilityIdentifier("readonly-note")
      } else if store.client?.online == false {
        Text(L10n.tr("离线 · 草稿保留在此对话")).font(.caption2).foregroundStyle(Palette.secondary)
      }
      if let transfer = store.transferStatus {
        HStack {
          ProgressView()
          Text(transfer).font(.caption)
        }.accessibilityIdentifier("transfer-status")
      }
      if store.isImporting { ProgressView(L10n.tr("正在导入附件…")).font(.caption) }
      if let pending = store.pending {
        let target = store.targetKey
        VStack(alignment: .leading, spacing: 4) {
          Text(store.canRetryPending ? L10n.tr("原消息已确认未提交") : store.pendingWaitDetached ? L10n.tr("等待已结束；原消息保留，未取消远端") : L10n.tr("发送结果待核对，仅查询原操作 ID")).font(.caption)
          HStack {
            Button(L10n.tr("查询原回执")) { store.reconcilePending() }.accessibilityIdentifier("query-original-reply")
            if store.canRetryPending {
              Button(L10n.tr("重试原消息")) { store.retryPending() }.accessibilityIdentifier("retry-proven-unsubmitted-reply")
              Button(L10n.tr("恢复未提交消息")) { store.editFailedPending() }
            }
            if !store.pendingWaitDetached { Button(L10n.tr("结束等待")) { store.detachPendingWait(pending, target: target) }.accessibilityIdentifier("detach-reply-wait") }
          }
        }.font(.caption).accessibilityElement(children: .contain).accessibilityIdentifier("outbox-pending")
      }
      if let creation = store.pendingDesktopCreation {
        VStack(alignment: .leading, spacing: 4) {
          Text("\(store.clientTypeName) · \(creation.progressTitle)").font(.caption)
          if let error = creation.error { Text(error).font(.caption2).lineLimit(3) }
          HStack {
            Button(L10n.tr("查询状态")) { store.queryDesktopCreation() }
            if store.canResumeDesktopCreation {
              Button(creation.phase == "prepared" ? L10n.tr("继续上传并创建") : L10n.tr("提交原内容")) { store.resumeDesktopCreation() }
                .accessibilityIdentifier("continue-original-creation")
            }
            if creation.phase == "rejected" { Button(L10n.tr("返回草稿")) { store.restoreRejectedCreation() } }
            Button(L10n.tr("结束等待")) { creationWaitToDetach = (store.targetKey, creation.clientOperationId) }
              .disabled(store.detachingDesktopCreation).accessibilityIdentifier("detach-creation-wait")
          }
        }.font(.caption).accessibilityElement(children: .contain).accessibilityIdentifier("desktop-creation-pending")
          .confirmationDialog(L10n.tr("结束等待？"), isPresented: Binding(get: { creationWaitToDetach != nil }, set: { if !$0 { creationWaitToDetach = nil } }), titleVisibility: .visible) {
            Button(L10n.tr("保留记录并返回编辑")) {
              if let request = creationWaitToDetach { store.detachDesktopCreation(target: request.target, operationId: request.operationId) }
              creationWaitToDetach = nil
            }
            Button(L10n.tr("继续等待"), role: .cancel) { creationWaitToDetach = nil }
          } message: {
            Text(L10n.tr("结束等待不会撤销可能已执行的请求。原记录仍可查询；再次发送将是一条新请求。"))
          }
      }
      if store.showsReplyMode {
        Picker(L10n.tr("发送方式"), selection: Binding(get: { store.activeReplyMode }, set: { store.replyMode = $0 })) {
          Text(L10n.tr("排队执行")).tag("queue")
            .disabled(!store.runningReplyModes.contains("queue"))
          if store.runningReplyModes.contains("steer") { Text(L10n.tr("立即追加")).tag("steer") }
        }.pickerStyle(.segmented).disabled(store.pending != nil).accessibilityIdentifier("reply-mode")
        if store.activeReplyMode == "queue" && !store.runningReplyModes.contains("queue") {
          Text(L10n.tr("客户端队列不可用，消息未提交。可保留草稿，或自行选择立即追加。"))
            .font(.caption).foregroundStyle(Palette.secondary)
            .accessibilityIdentifier("client-queue-unavailable")
        }
      }
      if !store.draft.attachments.isEmpty {
        ScrollView(.horizontal) {
          HStack(spacing: 8) {
            ForEach(store.draft.attachments) { file in
              HStack(spacing: 6) {
                Button {
                  guard file.isImage else { return }
                  composerFocused = false
                  attachmentModalPresented = true
                  draftImagePreview = DraftAttachmentPreviewRequest(destination: store.attachmentDestination(), initialId: file.id)
                } label: {
                  HStack(spacing: 6) {
                if file.isImage,
                  let image = UIImage(
                    contentsOfFile: store.attachmentURL(file).path)
                {
                  Image(uiImage: image).resizable().scaledToFill().frame(
                    width: 32, height: 32
                  ).clipped().clipShape(RoundedRectangle(cornerRadius: 6))
                } else {
                  Image(systemName: "doc").frame(width: 24)
                }
                VStack(alignment: .leading) {
                  Text(file.name).font(.caption).lineLimit(1)
                  Text(L10n.tr("已添加 · 待发送")).font(.caption2).foregroundStyle(Palette.secondary)
                  Text(
                    ByteCountFormatter.string(
                      fromByteCount: Int64(file.size), countStyle: .file)
                  ).font(.caption2).foregroundStyle(Palette.secondary)
                }
                  }
                }.buttonStyle(.plain).disabled(!file.isImage)
                  .accessibilityLabel(L10n.tr("预览图片") + " " + file.name)
                  .accessibilityIdentifier("preview-draft-attachment-" + file.id)
                IconButton(
                  symbol: "xmark", label: L10n.tr("移除 \(file.name)"),
                  id: "remove-attachment"
                ) { store.removeAttachment(file) }
              }.padding(.leading, 8).background(
                Palette.sidebar, in: RoundedRectangle(cornerRadius: 12))
            }
          }
        }.scrollIndicators(.hidden)
      }
      if !store.queuedReplies.isEmpty { queuedCommands }
    }
  }

  private var hasComposerDetails: Bool {
    (store.client?.isDemo == false && !store.isWritable && !store.canConnectForReply) || store.client?.online == false
      || store.transferStatus != nil || store.isImporting || store.pending != nil
      || store.pendingDesktopCreation != nil
      || store.showsReplyMode || !store.draft.attachments.isEmpty
      || !store.queuedReplies.isEmpty
  }

  private func composer(_ presentation: SuggestionPresentation) -> some View {
    VStack(spacing: 6) {
      if !voice.isActive && !store.drawerOpen && store.sheet == nil && !attachmentModalPresented {
        RecentAttachmentHint(store: store, library: attachmentPhotos)
          .padding(.horizontal, -12)
      }
      if let conflict = store.codexStorageConflictMessage {
        Text(conflict).font(.caption).foregroundStyle(Palette.secondary).textSelection(.enabled)
      }
      if !store.codexLegacyStorageChoices.isEmpty {
        Menu(L10n.tr("核对原草稿/回执")) {
          ForEach(Array(store.codexLegacyStorageChoices.enumerated()), id: \.element) { index, key in
            Button(L10n.tr("查看第 \(index + 1) 份原记录")) { store.selectCodexLegacyStorage(key) }
              .accessibilityIdentifier("storage-identity-choice-\(index)")
          }
        }.font(.caption).accessibilityIdentifier("storage-identity-choices")
      }
      if hasComposerDetails {
        composerDetails.padding(10).floatingSurface(cornerRadius: 16)
      }
      if !voice.isActive && (store.supportsGenerationSettings || store.supportsPlanMode || store.selectedCollaborationMode == "plan") {
        GeometryReader { geometry in
          composerSettings(toolbarDensity(width: geometry.size.width))
            .frame(width: geometry.size.width, height: 44)
        }.frame(height: 44)
      }
      if !voice.isActive && (validSuggestionUndo != nil || (presentation.available != nil && !store.draft.text.isEmpty)) {
        suggestionDraftControls(presentation)
      }
      Group {
        if voice.isActive { dictationInput } else { textInput(presentation) }
      }.padding(4).floatingSurface(cornerRadius: 27)
    }.padding(.horizontal, 12).padding(.vertical, 4)
  }

  private func textInput(_ presentation: SuggestionPresentation) -> some View {
    VStack(alignment: .leading, spacing: 0) {
        #if DEBUG
        if store.typingFixture {
          HStack {
            Button(L10n.tr("重置输入性能")) { store.resetTypingMetrics() }.accessibilityIdentifier("typing-reset")
            Button(L10n.tr("读取输入性能")) { store.showTypingMetrics() }.accessibilityIdentifier("typing-sample")
            Button(L10n.tr("复制中文")) { UIPasteboard.general.string = String(repeating: "请优化输入框，保留中文、换行和标点。\n", count: 8) }.accessibilityIdentifier("typing-copy-chinese")
          }.font(.caption2).buttonStyle(.bordered).frame(height: 44).padding(.horizontal, 8)
        }
        if store.typingFixture && !store.typingEvidence.isEmpty {
          Text(store.typingEvidence).font(.caption2).accessibilityIdentifier("typing-evidence")
        }
        if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), ProcessInfo.processInfo.arguments.contains("-ui-testing") {
          Text("DEV").font(.caption2).accessibilityIdentifier("agent-dev-target")
            .accessibilityValue(([store.client?.id ?? "", store.selectedThreadId ?? "", store.targetKey]
              + (ProcessInfo.processInfo.arguments.contains("-native-queue-dev") ? [store.devQueueReadiness] : [])).joined(separator: "|"))
          if ProcessInfo.processInfo.arguments.contains("-native-queue-dev") {
            Button("DEV Queue") { store.enqueueDEVQueueBatch() }
              .disabled(store.devQueueBatchRunning || !store.isRunning)
              .accessibilityIdentifier("native-queue-dev-batch")
          }
        }
        if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), let codes = store.devAttachmentEvidence {
          Text(codes).font(.caption2).accessibilityIdentifier("easywork-dev-input-evidence")
        }
        if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), let evidence = store.devOperationEvidence {
          Text(evidence).font(.caption2).accessibilityIdentifier("agent-dev-operation-evidence")
        }
        #endif
      HStack(alignment: .bottom, spacing: 0) {
        if !presentation.showsGhost { attachmentMenu }
        ZStack(alignment: .topLeading) {
          if presentation.showsGhost, let suggestion = presentation.available {
            Text(suggestion.text).font(.body).foregroundStyle(Palette.secondary).lineLimit(2)
              .padding(.horizontal, 6).padding(.vertical, 8).allowsHitTesting(false)
              .accessibilityIdentifier("suggestion-text")
              .onAppear {
                #if DEBUG
                if let scope = suggestionReadContext.scope { DesktopSuggestionTiming.mark("hintMounted", navigation: scope.navigation) }
                #endif
              }
          }
          composerTextField(showsGhost: presentation.showsGhost)
            .font(.body).lineLimit(presentation.showsGhost ? 2...6 : 1...6)
            .padding(.horizontal, 6).padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: presentation.showsGhost ? 62 : 44)
            .accessibilityIdentifier("composer-input").accessibilityLabel(L10n.tr("发送给当前客户端的消息"))
            .accessibilityHint(L10n.tr("长按语音输入"))
            .accessibilityAction(named: L10n.tr("语音输入")) { startVoiceInput() }
            .onKeyPress(.tab, phases: .down) { press in
              guard press.modifiers.isEmpty, composerFocused, presentation.showsGhost,
                let action = presentation.action else { return .ignored }
              applySuggestion(action, mode: .fill); return .handled
            }
        }.contentShape(Rectangle())
          .highPriorityGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            startVoiceInput()
          })
          .padding(.horizontal, presentation.showsGhost ? 8 : 0)
        if !presentation.showsGhost { voiceInputButton; composerActionButton }
      }
      if presentation.showsGhost {
        HStack(spacing: 0) {
          attachmentMenu
          Button {
            guard let action = presentation.action else { return }
            applySuggestion(action, mode: .fill)
          } label: {
            Text(L10n.tr("采用")).font(.subheadline).frame(minWidth: 56, minHeight: 44).contentShape(Rectangle())
          }.accessibilityIdentifier("suggestion-adopt").disabled(presentation.readScope == nil)
          Button { presentSuggestion(presentation.action) } label: {
            Text(L10n.tr("全文")).font(.caption).foregroundStyle(Palette.secondary)
              .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
          }.accessibilityIdentifier("suggestion-details").disabled(presentation.readScope == nil)
          Spacer(minLength: 0)
          voiceInputButton
          composerActionButton
        }.buttonStyle(.plain).id(suggestionControlIdentity(presentation.action))
      }
    }
  }

  @ViewBuilder private func composerTextField(showsGhost: Bool) -> some View {
    let prompt = showsGhost ? "" : L10n.tr("输入消息，长按语音输入")
    let text = Binding(get: { store.draft.text }, set: { store.setText($0) })
    if #available(iOS 18.0, *) {
      SuggestionSelectableTextField(prompt: prompt, text: text, focused: $composerFocused, insertionRequest: insertionRequest)
    } else {
      TextField(prompt, text: text, axis: .vertical).focused($composerFocused)
    }
  }

  private enum ToolbarDensity { case full, compact, minimal, icons }

  // Build one toolbar. Multiple proposed layouts can leave duplicate action
  // accessibility elements with stale frames after keyboard/sheet transitions.
  private func toolbarDensity(width: CGFloat) -> ToolbarDensity {
    let plan = store.supportsPlanMode || store.selectedCollaborationMode == "plan"
    if width >= (plan ? 480 : 420) { return .full }
    if width >= (plan ? 330 : 280) { return .compact }
    if width >= 220 { return .minimal }
    return .icons
  }

  private var attachmentMenu: some View {
    Button {
      composerFocused = false
      attachmentRequest = QuickAttachmentRequest(destination: store.attachmentDestination())
    } label: { Image(systemName: "plus").frame(width: 44, height: 44) }
      .accessibilityLabel(L10n.tr("添加图片或文件"))
      .accessibilityIdentifier("attachment-menu")
  }

  private var voiceInputButton: some View {
    IconButton(symbol: "mic", label: L10n.tr("语音输入"), id: "voice-input", action: startVoiceInput)
  }

  private func startVoiceInput() {
    guard !voice.isActive, let provider = store.voiceProvider(draftId: store.targetKey) else { return }
    composerFocused = false
    voice.start(target: store.targetKey, text: store.draft.text, provider: provider) { target, text in
      guard store.targetKey == target, store.draft.text == voice.originalText else { return false }
      store.setText(text, target: target)
      return true
    }
  }

  private func composerSettings(_ density: ToolbarDensity) -> some View {
    HStack(spacing: 6) {
      if store.supportsGenerationSettings { toolbarPermissionButton(density) }
      if store.supportsPlanMode || store.selectedCollaborationMode == "plan" { toolbarPlanToggle(density) }
      Spacer(minLength: 0)
      if store.supportsGenerationSettings { toolbarGenerationButton(density) }
    }
  }

  private func toolbarPlanToggle(_ density: ToolbarDensity) -> some View {
    let enabled = store.selectedCollaborationMode == "plan"
    return Button { store.setPlanMode(!enabled) } label: {
      HStack(spacing: 4) {
        Image(systemName: enabled ? "lightbulb.fill" : "lightbulb")
        if density != .icons { Text(enabled ? L10n.tr("计划 · 开") : L10n.tr("计划 · 关")).lineLimit(1) }
      }.font(.caption).padding(.horizontal, 9).frame(minHeight: 30)
        .foregroundStyle(enabled ? Color.blue : Palette.secondary)
        .floatingSurface(cornerRadius: 20).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
    }.buttonStyle(.plain).fixedSize(horizontal: true, vertical: false).disabled(!store.canSelectPlanMode)
      .accessibilityIdentifier("plan-mode-toggle").accessibilityLabel(L10n.tr("计划模式"))
      .accessibilityValue(enabled ? L10n.tr("已开启") : L10n.tr("已关闭"))
  }

  private func toolbarPermissionButton(_ density: ToolbarDensity) -> some View {
    Button { composerFocused = false; store.sheet = .generation } label: {
      HStack(spacing: 4) {
        Image(systemName: store.selectedPermissionOption?.icon ?? "shield")
        if density == .full || density == .compact {
          Text(density == .full ? store.permissionLabel : store.permissionLabel.replacingOccurrences(of: L10n.tr("权限"), with: ""))
            .lineLimit(1)
          Image(systemName: "chevron.down").font(.system(size: 9))
        }
      }.font(.caption).padding(.horizontal, 10).frame(minHeight: 34)
        .foregroundStyle(store.selectedPermissionMode == "full-access" ? Color.orange : Palette.secondary)
        .floatingSurface(cornerRadius: 20).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
    }.buttonStyle(.plain).fixedSize(horizontal: true, vertical: false).disabled(!store.canConfigureGeneration)
      .accessibilityIdentifier("permission-settings").accessibilityLabel(L10n.tr("权限模式，") + store.permissionLabel)
  }

  private var compactModelLabel: String {
    let name = store.displayedModelLabel.replacingOccurrences(of: "GPT-", with: "", options: [.anchored, .caseInsensitive])
      .replacingOccurrences(of: "-Sol", with: " Sol").replacingOccurrences(of: "-Astra", with: " Astra")
      .replacingOccurrences(of: "-Luna", with: " Luna").replacingOccurrences(of: "-Terra", with: " Terra")
    return name.count > 10 ? String(name.prefix(9)) + "…" : name
  }

  private func toolbarGenerationButton(_ density: ToolbarDensity) -> some View {
    Button { composerFocused = false; store.sheet = .generation } label: {
      HStack(spacing: density == .minimal ? 2 : 4) {
        Image(systemName: store.selectedSpeed == "ultrafast" ? "bolt.badge.clock.fill" : "bolt.fill")
          .foregroundStyle(store.selectedSpeed == "fast" || store.selectedSpeed == "ultrafast" ? Color.blue : Color.primary)
        if density != .icons {
          Text(density == .full ? store.displayedModelLabel : density == .minimal ? String(compactModelLabel.prefix(6)) : compactModelLabel)
            .lineLimit(1)
        }
        if density != .icons { Text(store.displayedEffortLabel).foregroundStyle(Palette.secondary) }
        if density != .icons { Image(systemName: "chevron.down").font(.system(size: 9)) }
      }.font(density == .minimal ? .caption2 : .caption)
        .padding(.horizontal, density == .full || density == .compact ? 10 : 6).frame(minHeight: 34)
        .floatingSurface(cornerRadius: 20).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
    }.buttonStyle(.plain).fixedSize(horizontal: true, vertical: false).disabled(!store.canConfigureGeneration)
      .accessibilityIdentifier("generation-settings")
      .accessibilityLabel(L10n.tr("模型，\(store.displayedModelLabel)，推理强度，\(store.displayedEffortLabel)，速度，\(store.speedLabel(store.selectedSpeed))"))
  }

  private var queuedCommands: some View {
    let target = store.targetKey, turn = store.runtime?.turnId
    return ScrollView {
      VStack(spacing: 4) {
        ForEach(Array(store.queuedReplies.enumerated()), id: \.element.id) { index, receipt in
          let text = (receipt.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
          HStack(spacing: 8) {
            Text("\(index + 1)").font(.caption.monospacedDigit()).foregroundStyle(Palette.secondary)
              .frame(width: 18).accessibilityIdentifier("queued-number-\(receipt.id)")
            VStack(alignment: .leading, spacing: 2) {
              if receipt.queueSource == "host" {
                Text(L10n.tr("连接器待发送 · 尚未进入电脑队列"))
                  .font(.caption2).foregroundStyle(Palette.secondary)
                  .accessibilityIdentifier("queued-source-\(receipt.id)")
              }
              Text(text.isEmpty ? L10n.tr("附件指令") : text)
                .font(.caption).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("queued-text-\(receipt.id)")
            }
            if store.supportsQueueExecution {
              Button { store.executeQueue(receipt, expectedTargetKey: target, expectedTurnId: turn) } label: {
                Group {
                  if store.queueActionPending(receipt) { ProgressView().controlSize(.mini) }
                  else { Text(store.isRunning ? L10n.tr("立即追加") : L10n.tr("立即执行")).font(.caption) }
                }.padding(.horizontal, 6).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
              }.buttonStyle(.plain)
                .disabled(!store.canExecuteQueue(receipt, expectedTargetKey: target, expectedTurnId: turn))
                .accessibilityLabel(L10n.tr("立即执行第 \(index + 1) 条排队指令"))
                .accessibilityIdentifier("execute-queued-\(receipt.id)")
            }
            Button { store.cancelQueue(receipt, expectedTargetKey: target) } label: {
              Image(systemName: "xmark").font(.caption).frame(width: 44, height: 44)
            }.buttonStyle(.plain).foregroundStyle(Palette.secondary)
              .disabled(!store.canCancelQueue(receipt, expectedTargetKey: target))
              .accessibilityLabel(L10n.tr("取消第 \(index + 1) 条排队指令"))
              .accessibilityIdentifier("cancel-queued-\(receipt.id)")
          }.padding(.leading, 10).background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 10))
        }
      }
    }.frame(height: min(CGFloat(store.queuedReplies.count) * 68, 180))
  }

  private var composerActionButton: some View {
      ZStack {
        Button {
          store.performComposerAction()
          composerFocused = false
        } label: {
          if store.composerAction == .busy {
            ProgressView().tint(Palette.background).frame(width: 44, height: 44)
          } else {
            Image(systemName: store.composerAction.symbol).font(.system(size: store.composerAction == .stop ? 14 : 18, weight: .medium))
              .frame(width: 44, height: 44)
          }
        }.buttonStyle(.plain)
          .modifier(ComposerActionAppearance(enabled: store.canPerformComposerAction,
            busy: store.composerAction == .busy))
          .disabled(!store.canPerformComposerAction)
          .accessibilityLabel(store.composerAction.label)
          .accessibilityValue(store.composerAction == .busy ? L10n.tr("正在提交") : store.canPerformComposerAction ? L10n.tr("可用") : L10n.tr("不可用"))
          .accessibilityIdentifier(store.composerAction.identifier)
          .highPriorityGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            if store.canStop || store.canRunAgain { showComposerActions = true }
          })
        if !store.canPerformComposerAction && store.canRunAgain {
          Circle().fill(Color.clear).frame(width: 44, height: 44)
            .contentShape(Circle())
            .onLongPressGesture(minimumDuration: 0.5) { showComposerActions = true }
            .accessibilityHidden(true)
        }
      }.contentShape(Circle())
        .confirmationDialog(L10n.tr("任务操作"), isPresented: $showComposerActions, titleVisibility: .hidden) {
          if store.canStop { Button(L10n.tr("停止任务")) { store.stop() } }
          if store.canRunAgain {
            Button(L10n.tr("继续任务")) { store.runAgain(restart: false) }
            Button(L10n.tr("重新运行上一任务")) { store.runAgain(restart: true) }
          }
          Button(L10n.tr("取消"), role: .cancel) {}
        }
  }

  private var dictationInput: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(store.draft.text.isEmpty ? L10n.tr("随心输入") : store.draft.text)
        .foregroundStyle(store.draft.text.isEmpty ? Palette.secondary : .primary)
        .font(.body).lineLimit(1...6).frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14).padding(.top, 10)
        .accessibilityIdentifier("dictation-transcript")
      VoiceInputControls(voice: voice, text: store.draft.text)
    }.padding(4)
  }

}


private struct ChatHistorySearchView: View {
  @ObservedObject var store: SessionStore
  let targetKey: String
  @Environment(\.dismiss) private var dismiss
  @State private var query = ""
  @State private var history: [ChatMessage] = []
  @State private var historyVersion = UUID()
  @State private var loading = true
  @State private var failure: String?
  @State private var reload = UUID()
  @State private var results: [ChatMessage] = []
  @State private var resultQuery = ""
  @State private var resultVersion: UUID?
  private var hasQuery: Bool { !SharedMobileRules.searchQuery(query).isEmpty }
  private var resultsCurrent: Bool {
    SharedMobileRules.searchResultsCurrent(resultQuery, query: query, resultVersion: resultVersion, version: historyVersion)
  }
  var body: some View {
    NavigationStack {
      VStack(spacing: 0) {
        HStack(spacing: 8) {
          Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
          TextField(L10n.tr("搜索消息、工具输出或附件名称"), text: $query)
            .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.search)
            .accessibilityIdentifier("chat-search-query")
          if !query.isEmpty {
            Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
              .accessibilityLabel(L10n.tr("清除搜索"))
          }
        }.padding(12).background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal).padding(.vertical, 8)
      List {
        if loading { ProgressView(L10n.tr("正在读取完整历史…")) }
        if let failure {
          Text(failure).foregroundStyle(.secondary)
          Button(L10n.tr("重试")) { reload = UUID() }.accessibilityIdentifier("chat-search-retry")
        }
        if !resultsCurrent && hasQuery { ProgressView() }
        if !loading && failure == nil && resultsCurrent && hasQuery && results.isEmpty { Text(L10n.tr("没有匹配结果")) }
        ForEach(resultsCurrent ? results : []) { message in
          Button {
            guard resultsCurrent else { return }
            store.locateSearchMessage(message, query: resultQuery, targetKey: targetKey, history: history, complete: failure == nil && !loading)
            dismiss()
          } label: {
            VStack(alignment: .leading, spacing: 4) {
              Text(message.role == "tool" ? L10n.tr("工具输出") : message.role == "user" ? L10n.tr("你") : L10n.tr("回复")).font(.caption).foregroundStyle(.secondary)
              Text(highlightedSearchText(searchSnippet(message.searchableText, query: query), query: query)).lineLimit(4)
            }.frame(maxWidth: .infinity, alignment: .leading)
          }.accessibilityIdentifier("chat-search-result-" + message.id)
        }
      }
      }

      .navigationTitle(L10n.tr("在聊天中查找"))
      .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(L10n.tr("完成")) { dismiss() }.accessibilityIdentifier("chat-search-close") } }
      .task(id: reload) {
        loading = true; failure = nil
        history = store.messages; historyVersion = UUID()
        if store.client?.online != true {
          loading = false
          failure = L10n.tr("无法读取完整历史。可搜索已缓存内容；连接恢复后重试。")
          return
        }
        do {
          let loaded = try await store.completeChatSearchHistory(targetKey: targetKey)
          try Task.checkCancellation(); history = loaded; historyVersion = UUID(); loading = false
        } catch {
          guard !Task.isCancelled, store.targetKey == targetKey else { return }
          loading = false
          failure = L10n.tr("无法读取完整历史。可搜索已缓存内容；连接恢复后重试。")
          history = store.messages; historyVersion = UUID()
        }
      }
      .task(id: query + ":" + historyVersion.uuidString) {
        let text = SharedMobileRules.searchQuery(query), source = history, version = historyVersion
        guard !text.isEmpty else { results = []; resultQuery = text; resultVersion = version; return }
        let work = Task.detached(priority: .userInitiated) {
          var matches: [ChatMessage] = []
          for message in source {
            if Task.isCancelled { return [ChatMessage]() }
            if SharedMobileRules.searchMatches(message.searchableText, query: text) { matches.append(message) }
          }
          return matches
        }
        let matches = await withTaskCancellationHandler { await work.value } onCancel: { work.cancel() }
        guard !Task.isCancelled else { return }
        results = matches; resultQuery = text; resultVersion = version
      }
    }
  }
}
private func searchSnippet(_ text: String, query: String) -> String {
  SharedMobileRules.searchSnippet(text, query: query)
}
private func highlightedSearchText(_ text: String, query: String) -> AttributedString {
  var result = AttributedString(text)
  for match in SharedMobileRules.searchRanges(text, query: query) {
    if let start = AttributedString.Index(match.lowerBound, within: result), let end = AttributedString.Index(match.upperBound, within: result) {
      result[start..<end].backgroundColor = .yellow.opacity(0.45)
      result[start..<end].foregroundColor = .primary
    }
  }
  return result
}
private struct LocatedChatMessageView: View {
  let message: ChatMessage
  let query: String
  let client: Client?
  let threadId: String?
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Label(L10n.tr("搜索匹配"), systemImage: "magnifyingglass").font(.caption).foregroundStyle(.secondary)
      Text(highlightedSearchText(searchSnippet(message.searchableText, query: query), query: query)).textSelection(.enabled)
      if SharedMobileRules.longSearchMessage(message.searchableText) {
        DisclosureGroup(L10n.tr("完整消息")) {
          if message.role == "assistant" && message.kind == "agentMessage" {
            MessageView(message: message, client: client, threadId: threadId)
          } else {
            Text(highlightedSearchText(message.searchableText, query: query)).textSelection(.enabled)
          }
        }
      }
    }.frame(maxWidth: .infinity, alignment: .leading).padding(10)
      .background(Color.yellow.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
      .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.orange.opacity(0.7)))
      .accessibilityIdentifier("chat-search-located-" + message.id)
  }
}
private struct ArchivedChatsView: View {
  @ObservedObject var store: SessionStore
  @Environment(\.dismiss) private var dismiss
  var body: some View {
    NavigationStack {
      List {
        if store.archivedChats.isEmpty { Text(L10n.tr("没有已归档对话")) }
        ForEach(store.archivedChats) { thread in
          Button { store.openThread(thread); dismiss() } label: {
            VStack(alignment: .leading, spacing: 4) {
              Label(thread.title, systemImage: "archivebox")
              Text(store.chatLifecycleStatus(thread)).font(.caption).foregroundStyle(.secondary)
            }
          }.accessibilityIdentifier("archived-chat-" + thread.id)
        }
      }.navigationTitle(L10n.tr("已归档对话"))
        .refreshable { await store.refresh() }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("完成")) { dismiss() } } }
    }
  }
}
