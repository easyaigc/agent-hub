import UIKit
import UserNotifications
import UserNotificationsUI

final class NotificationViewController: UIViewController, UNNotificationContentExtension {
  private let heading = UILabel()
  private let summary = UILabel()
  private let computer = UILabel()
  private let kind = UILabel()
  private let card = UIStackView()

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .clear
    heading.font = .preferredFont(forTextStyle: .headline)
    heading.numberOfLines = 2
    heading.accessibilityIdentifier = "notification-card-title"
    summary.font = .preferredFont(forTextStyle: .subheadline)
    summary.textColor = .secondaryLabel
    summary.numberOfLines = 4
    summary.accessibilityIdentifier = "notification-card-summary"
    computer.font = .preferredFont(forTextStyle: .caption1)
    computer.textColor = .secondaryLabel
    computer.textAlignment = .right
    computer.numberOfLines = 2
    computer.accessibilityIdentifier = "notification-card-computer"
    kind.font = .preferredFont(forTextStyle: .caption1)
    kind.textAlignment = .right
    kind.numberOfLines = 2
    kind.textColor = .label
    kind.accessibilityIdentifier = "notification-card-kind"
    for label in [heading, summary, computer, kind] { label.adjustsFontForContentSizeCategory = true }
    let text = UIStackView(arrangedSubviews: [heading, summary])
    text.axis = .vertical; text.spacing = 6
    let metadata = UIStackView(arrangedSubviews: [computer, kind])
    metadata.axis = .vertical; metadata.alignment = .trailing; metadata.spacing = 6
    metadata.setContentHuggingPriority(.required, for: .horizontal)
    metadata.setContentCompressionResistancePriority(.required, for: .horizontal)
    card.addArrangedSubview(text); card.addArrangedSubview(metadata)
    card.axis = .horizontal; card.alignment = .top; card.spacing = 14
    card.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(card)
    NSLayoutConstraint.activate([
      card.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
      card.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
      card.topAnchor.constraint(equalTo: view.topAnchor, constant: 14),
      metadata.widthAnchor.constraint(greaterThanOrEqualToConstant: 72),
      metadata.widthAnchor.constraint(lessThanOrEqualTo: card.widthAnchor, multiplier: 0.34)
    ])
  }

  func didReceive(_ notification: UNNotification) {
    loadViewIfNeeded()
    let content = notification.request.content
    if content.userInfo["businessNotification"] != nil {
      if let (event, binding) = try? BusinessNotificationShared.decode(content.userInfo) {
        heading.text = binding.preferences.showPreview ? (event.title ?? event.kind.title) : event.kind.title
        summary.text = binding.preferences.showPreview ? (event.summary ?? "") : ""
        computer.text = L10n.tr("项目任务"); kind.text = event.kind.title
      } else { heading.text = "Agent Hub"; summary.text = L10n.tr("请打开核对业务提醒"); computer.text = L10n.tr("项目任务"); kind.text = "" }
      return
    }
    let event: TaskNotificationEvent?
    if let value = content.userInfo["event"], let data = try? JSONSerialization.data(withJSONObject: value),
      let local = try? JSONDecoder().decode(TaskNotificationEvent.self, from: data), TaskNotificationValidation.event(local) { event = local }
    else { event = try? TaskNotificationShared.decodeEnvelope(content.userInfo) }
    if let event {
      let binding = (content.userInfo["notification"] as? [String: Any])?["bindingId"] as? String ?? ""
      let name = TaskNotificationShared.bindings[binding]?.clientName
        ?? content.userInfo["clientName"] as? String
        ?? (content.subtitle.contains(" · ") ? content.subtitle : content.body).components(separatedBy: " · ").first ?? L10n.tr("电脑")
      let presentation = TaskNotificationPresentation(event: event, computer: name, showPreview: TaskNotificationShared.preferences.showThreadTitle)
      heading.text = presentation.title; summary.text = presentation.summary
      computer.text = presentation.computer; kind.text = presentation.kind
    } else if TaskNotificationShared.preferences.showThreadTitle {
      heading.text = content.title.isEmpty ? "Agent Hub" : content.title
      summary.text = content.body
      computer.text = nil; kind.text = L10n.tr("任务提醒")
    } else {
      heading.text = "Agent Hub"; summary.text = L10n.tr("有新的任务提醒，请打开 App 查看")
      computer.text = nil; kind.text = L10n.tr("任务提醒")
    }
    summary.isHidden = summary.text?.isEmpty != false
    view.setNeedsLayout()
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    guard view.bounds.width > 32 else { return }
    let size = card.systemLayoutSizeFitting(CGSize(width: view.bounds.width - 32, height: 0), withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel)
    let height = max(86, size.height + 28)
    if abs(preferredContentSize.height - height) > 1 { preferredContentSize = CGSize(width: view.bounds.width, height: height) }
  }
}
