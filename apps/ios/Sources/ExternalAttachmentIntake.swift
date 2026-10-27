import SwiftUI
import UniformTypeIdentifiers

/// External files stay in a local inbox until the user chooses a draft.
struct ExternalAttachmentIntake: ViewModifier {
  @ObservedObject var store: SessionStore
  let modalPresented: Bool
  @Environment(\.scenePhase) private var phase
  @State private var pending: [AttachmentInboxBatch] = []
  @State private var presentation: Presentation?
  @State private var importing = false
  @State private var intakeError: String?
  private struct Presentation: Identifiable {
    let id = UUID()
    let batches: [AttachmentInboxBatch]
  }

  func body(content: Content) -> some View {
    content
      .onOpenURL { url in
        guard url.isFileURL else { AgentConfigurationViews.open(url, store: store); return }
        Task {
          do {
            _ = try await AttachmentInbox.stage(loaders: [{
              try await Task.detached(priority: .userInitiated) {
                AttachmentFilePayload(data: try AttachmentFileIntake.read(url), name: url.lastPathComponent,
                  image: UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) == true)
              }.value
            }])
            refresh()
          } catch { store.presentError(error) }
        }
      }
      .task(id: phase == .active) { if phase == .active { refresh() } }
      .onReceive(NotificationCenter.default.publisher(for: Notification.Name("AgentHubShowReceivedAttachments"))) { _ in refresh() }
      .onChange(of: store.sheet) { _, sheet in if sheet == nil { presentIfPossible() } }
      .onChange(of: modalPresented) { _, presented in if !presented { refresh() } }
      .onChange(of: store.localRecoveryRequired) { _, required in if !required { refresh() } }
      .sheet(item: $presentation) { request in
        NavigationStack {
          List {
            Section {
              ForEach(request.batches) { batch in
                ForEach(batch.files) { file in
                  Label {
                    VStack(alignment: .leading) {
                      Text(file.name)
                      Text(ByteCountFormatter.string(fromByteCount: Int64(file.size), countStyle: .file))
                        .font(.caption).foregroundStyle(.secondary)
                    }
                  } icon: { Image(systemName: file.image ? "photo" : "doc") }
                }
              }
            } footer: { Text(L10n.tr("选择好聊天后添加到当前草稿，发送消息时才上传。")) }
            if let intakeError { Text(intakeError).foregroundStyle(.red) }
            if importing { ProgressView(L10n.tr("正在导入附件…")) }
            Button(L10n.tr("添加到当前草稿")) { add(request.batches) }
              .accessibilityIdentifier("external-attachment-add")
              .disabled(importing || store.client == nil || store.localRecoveryRequired || store.isImporting)
            Button(L10n.tr("丢弃共享文件"), role: .destructive) {
              guard !importing else { return }
              do {
                for batch in request.batches { try AttachmentInbox.remove(batch) }
                pending = try AttachmentInbox.pending()
                presentation = nil
              }
              catch { intakeError = error.localizedDescription }
            }.disabled(importing).accessibilityIdentifier("external-attachment-discard")
          }
          .navigationTitle(L10n.tr("来自其他应用的附件"))
          .navigationBarTitleDisplayMode(.inline)
          .toolbar { ToolbarItem(placement: .cancellationAction) {
            Button(L10n.tr("稍后添加")) { presentation = nil }.disabled(importing)
          } }
        }.interactiveDismissDisabled(importing)
      }
  }
  private func refresh() {
    guard !importing, !store.localRecoveryRequired else { return }
    do { pending = try AttachmentInbox.pending(); presentIfPossible() }
    catch { store.presentError(error) }
  }
  private func presentIfPossible() {
    if presentation == nil, !pending.isEmpty, phase == .active, store.sheet == nil, !modalPresented {
      // The sheet receives the exact batch snapshot; it must not capture an empty pre-refresh state.
      presentation = Presentation(batches: pending)
    }
  }
  private func add(_ batches: [AttachmentInboxBatch]) {
    guard !importing, store.client != nil, !store.localRecoveryRequired, !store.isImporting else { return }
    let destination = store.attachmentDestination()
    importing = true
    intakeError = nil
    Task {
      defer { importing = false }
      do {
        for batch in batches {
          let path = try AttachmentInbox.batchDirectory(batch.id).appendingPathComponent("destination.json")
          if FileManager.default.fileExists(atPath: path.path) {
            let original = try JSONDecoder().decode(AttachmentDraftDestination.self, from: AttachmentFileIntake.read(path))
            guard original == destination else { throw AttachmentIntakeError.invalid }
          } else {
            try JSONEncoder().encode(destination).write(to: path, options: [.atomic, .completeFileProtectionUnlessOpen])
          }
          let files = batch.files.filter { file in
            store.drafts.values[destination.key]?.attachments.contains { $0.id == file.id } != true
          }
          if !files.isEmpty {
            await store.addLocalAttachments(to: destination, attachmentIds: files.map(\.id),
              loaders: files.map { file in {
                let payload = try await Task.detached(priority: .userInitiated) {
                  try AttachmentInbox.payload(batch: batch, file: file)
                }.value
                return .data(payload.data, name: payload.name, image: payload.image)
              } })
          }
          guard try await store.confirmLocalAttachmentImport(batch.files.map(\.id), to: destination) else {
            throw AttachmentIntakeError.unavailable
          }
          try AttachmentInbox.remove(batch)
        }
        pending = try AttachmentInbox.pending()
        presentation = nil
      } catch { intakeError = error.localizedDescription }
    }
  }
}

struct AttachmentPasteControl: UIViewRepresentable {
  var disabled: Bool
  let onPaste: ([NSItemProvider]) -> Void
  func makeCoordinator() -> Target { Target() }
  func makeUIView(context: Context) -> UIPasteControl {
    var configuration = UIPasteControl.Configuration()
    configuration.displayMode = .iconAndLabel
    let control = UIPasteControl(configuration: configuration)
    control.target = context.coordinator
    control.accessibilityLabel = L10n.tr("粘贴图片或文件")
    control.accessibilityIdentifier = "attachment-paste"
    return control
  }
  func updateUIView(_ control: UIPasteControl, context: Context) {
    context.coordinator.disabled = disabled
    context.coordinator.onPaste = onPaste
    control.isUserInteractionEnabled = !disabled
    control.alpha = disabled ? 0.5 : 1
  }
  final class Target: UIResponder {
    var disabled = false
    var onPaste: (([NSItemProvider]) -> Void)?
    override init() {
      super.init()
      pasteConfiguration = UIPasteConfiguration(acceptableTypeIdentifiers: ["public.data", "public.file-url", "public.image"])
    }
    override func canPaste(_ itemProviders: [NSItemProvider]) -> Bool {
      !disabled && itemProviders.contains { $0.canImportAttachmentFile }
    }
    override func paste(itemProviders: [NSItemProvider]) {
      guard !disabled else { return }
      let files = itemProviders.filter { $0.canImportAttachmentFile }
      if !files.isEmpty { onPaste?(files) }
    }
  }
}
