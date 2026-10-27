import SwiftUI
import UniformTypeIdentifiers

final class AttachmentActionViewController: UIViewController {
  override func viewDidLoad() {
    super.viewDidLoad()
    let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
    let controller = UIHostingController(rootView: AttachmentActionView(providers: providers,
      finish: { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) },
      cancel: { [weak self] in self?.extensionContext?.cancelRequest(withError: CocoaError(.userCancelled)) }))
    addChild(controller)
    view.addSubview(controller.view)
    controller.view.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      controller.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      controller.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      controller.view.topAnchor.constraint(equalTo: view.topAnchor),
      controller.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
    ])
    controller.didMove(toParent: self)
  }
}

private struct AttachmentActionView: View {
  let providers: [NSItemProvider]
  let finish: () -> Void
  let cancel: () -> Void
  @State private var saving = false
  @State private var saved = false
  @State private var failure: String?
  var body: some View {
    NavigationStack {
      List {
        ForEach(Array(providers.enumerated()), id: \.offset) { _, provider in
          Label(provider.suggestedName ?? NSLocalizedString("附件", comment: ""), systemImage: "doc")
        }
        Text(saved ? NSLocalizedString("已保存。返回 Agent Hub，选择聊天后添加到草稿。", comment: "")
          : NSLocalizedString("文件仅保存在手机，返回 Agent Hub 后添加到聊天草稿，发送时才上传。", comment: ""))
          .foregroundStyle(.secondary)
        if let failure { Text(failure).foregroundStyle(.red) }
        if saving { ProgressView() }
        if saved {
          Button(NSLocalizedString("完成", comment: ""), action: finish)
        } else {
          Button(NSLocalizedString("添加到 Agent Hub", comment: "")) {
            saving = true; failure = nil
            Task {
              defer { saving = false }
              do {
                guard providers.allSatisfy({ $0.canImportAttachmentFile }) else { throw AttachmentIntakeError.unsupported }
                let readers = providers.map { AttachmentFileProvider(provider: $0) }
                _ = try await AttachmentInbox.stage(loaders: readers.map { reader in { try await reader.load() } })
                saved = true
              } catch { failure = error.localizedDescription }
            }
          }.disabled(saving).accessibilityIdentifier("external-action-save")
        }
      }
      .navigationTitle(NSLocalizedString("添加到 Agent Hub", comment: ""))
      .navigationBarTitleDisplayMode(.inline)
      .toolbar { ToolbarItem(placement: .cancellationAction) {
        Button(NSLocalizedString("取消", comment: ""), action: cancel).disabled(saving || saved)
      } }
    }.interactiveDismissDisabled(saving)
  }
}
