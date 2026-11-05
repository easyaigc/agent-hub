import SwiftUI
import UIKit

/// Observes the existing native menu button without replacing its actions or delegate.
struct MenuReadingCoverProbe: UIViewRepresentable {
  let resolved: (UUID, UIButton) -> Void
  let changed: () -> Void
  let released: (UUID) -> Void

  func makeUIView(context: Context) -> MenuReadingCoverProbeView {
    let view = MenuReadingCoverProbeView()
    view.resolved = resolved; view.changed = changed; view.released = released
    return view
  }
  func updateUIView(_ view: MenuReadingCoverProbeView, context: Context) {
    view.scheduleResolution()
  }
  static func dismantleUIView(_ view: MenuReadingCoverProbeView, coordinator: ()) {
    view.detach()
  }
}

/// Its lifetime follows the actual held menu, including removal of the presenting row.
final class MenuReadingCoverProbeView: UIView {
  let owner = UUID()
  private(set) weak var menuButton: UIButton?
  var resolved: ((UUID, UIButton) -> Void)?
  var changed: (() -> Void)?
  var released: ((UUID) -> Void)?
  private var link: CADisplayLink?
  private var detached = false
  private var lastHeld = false
  private var resolutionPending = false
  private(set) var isRegistered = false

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear; isUserInteractionEnabled = false
    isAccessibilityElement = false; accessibilityElementsHidden = true
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { detach() }
    else { detached = false; scheduleResolution() }
  }
  override func layoutSubviews() { super.layoutSubviews(); scheduleResolution() }

  func scheduleResolution() {
    guard !detached, window != nil, menuButton == nil, !resolutionPending else { return }
    resolutionPending = true
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.resolutionPending = false
      self.resolveButton()
    }
  }
  private func resolveButton() {
    guard !detached, menuButton == nil, let window, bounds.width > 1, bounds.height > 1 else { return }
    let point = convert(CGPoint(x: bounds.midX, y: bounds.midY), to: window)
    guard window.bounds.contains(point) else { return }
    var candidate = window.hitTest(point, with: nil)
    while let view = candidate {
      if let button = view as? UIButton, button.menu != nil, button.window === window {
        menuButton = button; lastHeld = button.isHeld
        resolved?(owner, button); isRegistered = true
        if lastHeld { changed?() }
        let observer = CADisplayLink(target: self, selector: #selector(sampleMenu))
        observer.preferredFrameRateRange = CAFrameRateRange(minimum: 15, maximum: 30, preferred: 30)
        observer.add(to: .main, forMode: .common); link = observer
        return
      }
      candidate = view.superview
    }
  }
  @objc private func sampleMenu() {
    let held = menuButton?.isHeld == true
    if held != lastHeld { lastHeld = held; changed?() }
    if detached && !held || menuButton == nil {
      stop()
      if !detached { scheduleResolution() }
    }
  }
  func detach() {
    detached = true
    // The menu may outlive the SwiftUI row. Only actual dismissal releases it.
    if menuButton?.isHeld != true { stop() }
  }
  private func stop() {
    link?.invalidate(); link = nil
    if isRegistered { released?(owner); isRegistered = false }
    menuButton = nil; lastHeld = false
  }
}
