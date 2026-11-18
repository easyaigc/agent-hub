import SwiftUI

/// An explicit send intent, captured before a drag can arm. Changes to the
/// destination, draft or reply mode invalidate that drag, even if later restored.
struct ComposerSwipeSendIntent: Equatable {
  let target: String
  let draft: Draft
  let snapshot: DesktopSuggestionDraftSnapshot
  let replyMode: String
}

/// Deliberately separate from the editor: scrolling, selection and cursor drags
/// in the text field must never compete with a submission gesture.
struct ComposerSwipeSend: View {
  let intent: ComposerSwipeSendIntent
  let enabled: Bool
  let send: (ComposerSwipeSendIntent) -> Void
  @Environment(\.scenePhase) private var scenePhase
  @GestureState private var dragging = false
  @State private var captured: ComposerSwipeSendIntent?
  @State private var invalidated = false
  @State private var armed = false

  private var title: String {
    if armed { return L10n.tr("松开发送 · 回滑取消") }
    if dragging { return L10n.tr("继续上滑 · 回滑取消") }
    return L10n.tr("上滑发送")
  }

  var body: some View {
    interactiveSurface
      .accessibilityElement(children: .ignore)
      .accessibilityIdentifier("composer-swipe-send")
      .accessibilityLabel(L10n.tr("上滑发送"))
      .accessibilityValue(accessibilityStatus)
      .accessibilityHint(L10n.tr("从此处向上滑动，松开发送；回滑取消。文字区域仍可正常编辑。"))
      .accessibilityAddTraits(.isButton)
      .accessibilityAction(.default) { activate() }
  }

  private var accessibilityStatus: String {
    if armed { return L10n.tr("松开发送 · 回滑取消") }
    return enabled ? L10n.tr("可用") : L10n.tr("不可用")
  }

  private var surface: some View {
    HStack(spacing: 8) {
      Image(systemName: armed ? "arrow.up.circle.fill" : "chevron.up")
      Text(title).font(.caption)
      Spacer(minLength: 0)
    }
    .padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 44)
    .foregroundStyle(enabled ? (armed ? Color.blue : Palette.secondary) : Palette.secondary.opacity(0.5))
    .background(armed ? Color.blue.opacity(0.1) : Color.clear, in: RoundedRectangle(cornerRadius: 20))
    .contentShape(Rectangle())
  }

  private var interactiveSurface: some View {
    surface.gesture(sendGesture)
      .onChange(of: intent) { _, _ in invalidate() }
      .onChange(of: enabled) { _, _ in invalidate() }
      .onChange(of: scenePhase) { _, _ in invalidate() }
      .onChange(of: dragging) { _, active in if !active { reset() } }
      .onDisappear { reset() }
      .sensoryFeedback(.impact(weight: .light), trigger: armed)
  }

  private var sendGesture: some Gesture {
    DragGesture(minimumDistance: 0, coordinateSpace: .global)
      .updating($dragging) { _, active, _ in active = true }
      .onChanged { value in
        if captured == nil {
          captured = intent
          invalidated = !enabled || scenePhase != .active
        }
        guard !invalidated, enabled, captured == intent, scenePhase == .active else {
          invalidated = true; armed = false; return
        }
        let up = -value.translation.height
        let horizontal = abs(value.translation.width)
        // Hysteresis prevents flicker near the threshold. A final position
        // back below 48pt, or outside the vertical corridor, cancels the send.
        armed = horizontal <= 44 && up >= (armed ? 48 : 72)
      }
      .onEnded { value in
        let original = captured
        let shouldSend = armed && !invalidated && enabled && scenePhase == .active
          && original == intent && -value.translation.height >= 48
          && abs(value.translation.width) <= 44
        reset()
        if shouldSend, let original { send(original) }
      }
  }

  private func activate() {
    guard enabled, scenePhase == .active else { return }
    send(intent)
  }

  private func invalidate() {
    if captured != nil { invalidated = true; armed = false }
  }

  private func reset() {
    captured = nil; invalidated = false; armed = false
  }
}
