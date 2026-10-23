import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
  private var handler: ((UNNotificationContent) -> Void)?
  private var content: UNMutableNotificationContent?
  override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
    handler = contentHandler
    guard let content = request.content.mutableCopy() as? UNMutableNotificationContent else { contentHandler(request.content); return }
    self.content = content
    if content.userInfo["businessNotification"] != nil {
      if let (event, binding) = try? BusinessNotificationShared.decode(content.userInfo) {
        let applicable = binding.preferences.kinds.contains(event.kind)
        content.title = binding.preferences.showPreview ? (event.title ?? event.kind.title) : event.kind.title
        content.subtitle = binding.preferences.showPreview ? (event.summary ?? "") : ""
        content.body = event.kind.title
        content.categoryIdentifier = BusinessNotificationShared.category
        content.threadIdentifier = "business." + binding.bindingId
        let sound = binding.preferences.sound
        content.sound = !applicable || sound == "none" ? nil : sound == "default" ? .default : UNNotificationSound(named: UNNotificationSoundName(rawValue: sound))
        content.interruptionLevel = applicable ? .active : .passive
      } else {
        content.title = "Agent Hub"; content.subtitle = ""; content.body = L10n.tr("有新的业务提醒，请打开核对。"); content.sound = nil
      }
      content.badge = nil // Business notices never mutate the conversation unread ledger.
      contentHandler(content); handler = nil; return
    }
    let prefs = TaskNotificationShared.preferences
    if let event = try? TaskNotificationShared.decodeEnvelope(content.userInfo) {
      let bindingId = (content.userInfo["notification"] as? [String: Any])?["bindingId"] as? String ?? ""
      let name = TaskNotificationShared.bindings[bindingId]?.clientName ?? L10n.tr("电脑")
      let applicable = prefs.enabled && prefs.kinds.contains(event.kind) && !prefs.excludedHosts.contains(event.target.hostId)
      let presentation = TaskNotificationPresentation(event: event, computer: name, showPreview: prefs.showThreadTitle)
      presentation.apply(to: content)
      content.threadIdentifier = event.target.hostId
      let sound = prefs.categorySounds[event.kind.rawValue] ?? prefs.sound
      content.sound = !applicable || !prefs.soundEnabled || sound == "none" ? nil : sound == "default" ? .default : UNNotificationSound(named: UNNotificationSoundName(rawValue: sound))
      if !applicable { content.interruptionLevel = .passive }
      else if prefs.timeSensitive && [.approval, .question, .plan, .paused, .failed, .uncertain].contains(event.kind) { content.interruptionLevel = .timeSensitive } else { content.interruptionLevel = .active }
      if applicable { try? TaskNotificationShared.remember(event) }
      if prefs.enabled && !prefs.excludedHosts.contains(event.target.hostId) && (applicable || prefs.badgeEnabled) {
        _ = try? MobileUnreadBadge.receive(event)
      }
      content.badge = prefs.badgeEnabled ? NSNumber(value: MobileUnreadBadge.count) : NSNumber(value: 0)
    } else {
      content.title = "Agent Hub"; content.subtitle = ""; content.body = L10n.tr("有新的任务提醒，请打开查看。")
      if !prefs.enabled || !prefs.soundEnabled { content.sound = nil }
      content.badge = prefs.badgeEnabled ? NSNumber(value: MobileUnreadBadge.count) : NSNumber(value: 0)
    }
    contentHandler(content); handler = nil
  }
  override func serviceExtensionTimeWillExpire() { if let handler, let content { handler(content) }; handler = nil }
}
