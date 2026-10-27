// Standalone, opt-in acceptance fixture. Never compiled into Agent Hub.
import SwiftUI
import UniformTypeIdentifiers

@main struct ExternalAttachmentSourceApp: App {
  var body: some Scene { WindowGroup { SourceView() } }
}

private struct SourceView: UIViewControllerRepresentable {
  func makeUIViewController(context: Context) -> UINavigationController {
    UINavigationController(rootViewController: SourceController())
  }
  func updateUIViewController(_ controller: UINavigationController, context: Context) {}
}

@MainActor private final class SourceController: UIViewController {
  private var document: UIDocumentInteractionController?
  private let status = UILabel()
  override func viewDidLoad() {
    super.viewDidLoad()
    title = "File Source DEV"
    view.backgroundColor = .systemBackground
    status.text = "ready"
    status.accessibilityIdentifier = "fixture-source-status"
    let stack = UIStackView(arrangedSubviews: [status,
      button("Share controlled ZIP", "fixture-share-file", #selector(share(_:))),
      button("Open controlled ZIP in another app", "fixture-open-file", #selector(open(_:))),
      button("Copy controlled ZIP", "fixture-copy-file", #selector(copyFile))])
    stack.axis = .vertical
    stack.alignment = .center
    stack.spacing = 24
    stack.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(stack)
    NSLayoutConstraint.activate([
      stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
      stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
      stack.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -32)
    ])
  }
  private func button(_ title: String, _ id: String, _ action: Selector) -> UIButton {
    let button = UIButton(type: .system)
    button.setTitle(title, for: .normal)
    button.accessibilityIdentifier = id
    button.addTarget(self, action: action, for: .touchUpInside)
    return button
  }
  @objc private func share(_ sender: UIButton) {
    do {
      let controller = UIActivityViewController(activityItems: [try file()], applicationActivities: nil)
      controller.popoverPresentationController?.sourceView = sender
      controller.popoverPresentationController?.sourceRect = sender.bounds
      present(controller, animated: true)
    } catch { status.text = error.localizedDescription }
  }
  @objc private func open(_ sender: UIButton) {
    do {
      let document = UIDocumentInteractionController(url: try file())
      document.uti = UTType.zip.identifier
      self.document = document
      status.text = document.presentOpenInMenu(from: sender.bounds, in: sender, animated: true)
        ? "open-menu-presented" : "no-open-in-handlers"
    } catch { status.text = error.localizedDescription }
  }
  @objc private func copyFile() {
    do {
      let url = try file()
      guard let provider = NSItemProvider(contentsOf: url) else { return }
      provider.suggestedName = url.lastPathComponent
      UIPasteboard.general.setItemProviders([provider], localOnly: true, expirationDate: nil)
      status.text = "copied"
    } catch { status.text = error.localizedDescription }
  }
  private func file() throws -> URL {
    let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-wechat.zip")
    try Data("PK\u{03}\u{04} controlled ZIP attachment".utf8).write(to: url, options: .atomic)
    return url
  }
}
