import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct RecentAttachmentHint: View {
  @ObservedObject var store: SessionStore
  @ObservedObject var library: RecentAttachmentPhotos
  @State private var preview: RecentAttachmentPhoto?
  var body: some View {
    if let photo = library.suggestion {
      HStack(spacing: 8) {
        Button { preview = photo } label: { AttachmentPhotoThumbnail(photo: photo).frame(width: 48, height: 52) }
          .accessibilityLabel(L10n.tr("预览最近图片"))
        VStack(alignment: .leading, spacing: 3) {
          Text(L10n.tr("要添加这张图片吗？")).font(.subheadline)
          Text(L10n.tr("最近新增的图片")).font(.caption).foregroundStyle(Palette.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
        Button(L10n.tr("添加")) { add(photo) }.buttonStyle(.borderedProminent)
          .disabled(store.isImporting || store.localRecoveryRequired).accessibilityIdentifier("recent-attachment-add")
        IconButton(symbol: "xmark", label: L10n.tr("忽略这张图片"), id: "recent-attachment-dismiss") { library.dismiss(photo) }
      }.padding(8).floatingSurface(cornerRadius: 16).padding(.horizontal, 12)
        .fullScreenCover(item: $preview) { photo in
          AttachmentImageViewer(items: [AttachmentPreviewItem(photo: photo)], selection: .constant(photo.id),
            close: { preview = nil }) {
            HStack {
              Text(L10n.tr("仅添加到草稿，发送时再上传。")).font(.caption)
              Spacer()
              Button(L10n.tr("添加到当前草稿")) { preview = nil; add(photo) }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(store.isImporting)
            }
          }
        }
    }
  }
  private func add(_ photo: RecentAttachmentPhoto) {
    let destination = store.attachmentDestination()
    let count = store.drafts.values[destination.key]?.attachments.count ?? 0
    Task {
      await store.addLocalAttachments(to: destination, loaders: [{ try await library.input(for: photo) }])
      if (store.drafts.values[destination.key]?.attachments.count ?? 0) > count { library.dismiss(photo) }
    }
  }
}

struct QuickAttachmentRequest: Equatable {
  let id = UUID().uuidString
  let destination: AttachmentDraftDestination
}

struct QuickAttachmentPresentation: ViewModifier {
  @ObservedObject var store: SessionStore
  @ObservedObject var library: RecentAttachmentPhotos
  let request: QuickAttachmentRequest?
  @Binding var isPresenting: Bool
  @State private var panel = false
  @State private var showPhotos = false
  @State private var showFiles = false
  @State private var showCamera = false
  @State private var showShare = false
  @State private var shareURL: URL?
  @State private var selectedPhotos: [PhotosPickerItem] = []
  @State private var destination: AttachmentDraftDestination?
  @State private var importId = UUID().uuidString
  @State private var nextAction: Action?
  @State private var pasting = false
  @State private var browsingPhotos = false
  @State private var addingPhotos = false
  @State private var previewPhotos: AttachmentPhotoPreviewRequest?
  @StateObject private var photoSelection = AttachmentPhotoSelection()
  @StateObject private var photoCatalog = AttachmentPhotoCatalog()
  private enum Action { case photos, files, camera, received, shareTest(URL) }
  func body(content: Content) -> some View {
    content
      .onChange(of: request) { _, request in
        guard let request else { return }
        destination = request.destination
        importId = request.id
        photoSelection.photos = []
        photoSelection.error = nil
        addingPhotos = false
        previewPhotos = nil
        browsingPhotos = false
        isPresenting = true
        panel = true
      }
      .sheet(isPresented: $panel, onDismiss: performPendingAction) { attachmentPanel }
      .sheet(isPresented: $showShare) { if let shareURL { FileShareSheet(url: shareURL) } }
      .onReceive(NotificationCenter.default.publisher(for: Notification.Name("AgentHubShareAcceptanceFile"))) { note in
        #if DEBUG
        guard QuickAttachmentAcceptance.enabled, let url = note.object as? URL else { return }
        nextAction = .shareTest(url)
        panel = false
        #endif
      }
      .photosPicker(isPresented: $showPhotos, selection: $selectedPhotos, maxSelectionCount: 5, matching: .images)
      .onChange(of: selectedPhotos) { _, photos in
        guard !photos.isEmpty, let target = destination else { return }
        let operation = importId
        selectedPhotos = []
        Task {
          await store.addLocalAttachments(to: target, importId: operation, loaders: photos.map { item in
            {
              guard let image = try await item.loadTransferable(type: PickedLocalImage.self) else {
                throw AttachmentError.unsupportedImage
              }
              return .data(image.data, name: image.name, image: true)
            }
          })
        }
      }
      .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
        guard let target = destination else { return }
        let operation = importId
        switch result {
        case .success(let urls):
          Task { await store.addLocalAttachments(to: target, importId: operation, loaders: urls.map { url in { .file(url) } }) }
        case .failure(let error): store.presentError(error)
        }
      }
      .fullScreenCover(isPresented: $showCamera) {
        AttachmentCamera { image in
          showCamera = false
          guard let image, let target = destination else { return }
          let operation = importId
          Task { await store.addLocalAttachments(to: target, importId: operation, loaders: [{ .image(image) }]) }
        }.ignoresSafeArea()
      }
      .onChange(of: showPhotos) { _, value in if !value { isPresenting = false } }
      .onChange(of: showFiles) { _, value in if !value { isPresenting = false } }
      .onChange(of: showCamera) { _, value in if !value { isPresenting = false } }
      .onChange(of: showShare) { _, value in if !value { isPresenting = false } }
  }

  private var attachmentPanel: some View {
    NavigationStack {
      List {
        #if DEBUG
        QuickAttachmentAcceptanceControls(store: store, library: library)
        #endif
        Section {
          if library.readable {
            if !library.recent.isEmpty {
              ScrollView(.horizontal) {
                LazyHStack(spacing: 10) {
                  ForEach(library.recent) { photo in
                    photoTile(photo, in: library.recent).frame(width: 96, height: 96)
                  }
                }
              }.frame(height: 96).scrollIndicators(.hidden)
                .accessibilityIdentifier("recent-attachments-strip")
            } else if library.baselineEstablished {
              Text(L10n.tr("暂无可访问的图片")).foregroundStyle(Palette.secondary)
            } else {
              ProgressView().frame(maxWidth: .infinity)
            }
          } else if library.authorization == .denied || library.authorization == .restricted {
            Text(L10n.tr("允许读取照片后可显示最近图片，也可以继续使用系统选图器。"))
              .font(.caption).foregroundStyle(Palette.secondary)
            if library.authorization == .denied {
              Button(L10n.tr("打开系统设置")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
              }
            }
          }
          if library.authorization == .limited {
            Button(L10n.tr("管理可访问的照片")) { library.manageLimitedAccess() }
          }
        } header: { Text(L10n.tr("最近图片")) } footer: { Text(L10n.tr("点图片预览，点圆圈快速选择；添加到草稿后，发送时再上传。")) }
        Section {
          if ((try? AttachmentInbox.pending()) ?? []).isEmpty == false {
            Button(L10n.tr("来自其他应用的附件"), systemImage: "tray.and.arrow.down") { nextAction = .received; panel = false }
              .accessibilityIdentifier("attachment-received-files")
          }
          Button(L10n.tr("拍照"), systemImage: "camera") { nextAction = .camera; panel = false }
            .accessibilityIdentifier("attachment-camera")
          Button(L10n.tr("选择图片"), systemImage: "photo") {
            if library.readable { browsingPhotos = true }
            else { nextAction = .photos; panel = false }
          }.accessibilityIdentifier("attachment-photo-library")
          Button(L10n.tr("选择文件"), systemImage: "doc") { nextAction = .files; panel = false }
          HStack {
          Text(L10n.tr("粘贴图片或文件"))
          Spacer()
          AttachmentPasteControl(disabled: pasting) { providers in
            guard !pasting, let target = destination else { return }
            pasting = true
            let operation = importId
            Task {
              defer { pasting = false }
              await store.addLocalAttachments(to: target, importId: operation,
                loaders: providers.map { provider in {
                  if provider.attachmentNameAllowsImage, provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
                    !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                    return try await provider.localAttachmentImage()
                  }
                  let payload = try await provider.attachmentFilePayload()
                  return .data(payload.data, name: payload.name, image: payload.image)
                } })
              // Keep the system paste control alive until its providers finish.
              panel = false
            }
          }.fixedSize().frame(minHeight: 44)
          }
          Text(L10n.tr("复制文件或图片后点粘贴；若来源应用未提供文件，请使用分享或用其他应用打开。"))
            .font(.caption).foregroundStyle(Palette.secondary)
          if pasting { ProgressView(L10n.tr("正在导入附件…")) }
        } footer: { Text(L10n.tr("仅在你添加后保存在手机，点击发送才上传。")) }
        Section {
          Button(L10n.tr("清空草稿"), systemImage: "trash", role: .destructive) {
            if let target = destination { store.clearDraft(target: target.key) }; panel = false
          }.accessibilityIdentifier("clear-composer-draft")
          #if DEBUG
          if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev") {
            Button(L10n.tr("导入 DEV 验收附件")) { store.importEasyWorkDEVInputs(); panel = false }
              .accessibilityIdentifier("easywork-dev-inputs")
            Button(L10n.tr("双通道重复创建")) { store.devReplayAgentOperation(.create); panel = false }
              .accessibilityIdentifier("agent-dev-duplicate-create")
            Button(L10n.tr("双通道重复发送")) { store.devReplayAgentOperation(.reply); panel = false }
              .accessibilityIdentifier("agent-dev-duplicate-reply")
          }
          #endif
        }
      }.navigationTitle(L10n.tr("添加附件")).navigationBarTitleDisplayMode(.inline)
        .toolbar {
          if !browsingPhotos {
            ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("关闭")) { panel = false } }
          }
        }
        .safeAreaInset(edge: .bottom) { photoSelectionFooter }
        .navigationDestination(isPresented: $browsingPhotos) { photoLibrary }
        .fullScreenCover(item: $previewPhotos) { request in
          AttachmentPhotoSelectionPreview(request: request, selection: photoSelection,
            alreadyAdded: addedPhotoIds, maximum: remainingPhotoCount, adding: addingPhotos,
            add: addSelectedPhotos)
        }
    }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
      .task { await library.requestAccess() }
  }

  private var addedPhotoIds: Set<String> {
    guard let destination else { return [] }
    return Set((store.drafts.values[destination.key]?.attachments ?? []).compactMap(\.photoAssetId))
  }
  private var remainingPhotoCount: Int {
    guard let destination else { return 0 }
    return max(0, 5 - (store.drafts.values[destination.key]?.attachments.count ?? 0))
  }
  private func photoTile(_ photo: RecentAttachmentPhoto, in photos: [RecentAttachmentPhoto]) -> some View {
    AttachmentPhotoTile(photo: photo, order: photoSelection.photos.firstIndex(where: { $0.id == photo.id }).map { $0 + 1 },
      alreadyAdded: addedPhotoIds.contains(photo.id), preview: {
        previewPhotos = AttachmentPhotoPreviewRequest(photos: photos, initialId: photo.id)
      }, select: { photoSelection.toggle(photo, maximum: remainingPhotoCount) })
      .disabled(addingPhotos)
  }
  private var photoLibrary: some View {
    ScrollView {
      LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 3), count: 3), spacing: 3) {
        ForEach(photoCatalog.photos) { photo in
          photoTile(photo, in: photoCatalog.photos).aspectRatio(1, contentMode: .fit)
        }
        if photoCatalog.hasMore {
          ProgressView().frame(height: 44).onAppear { photoCatalog.loadMore() }
        }
      }.padding(3)
      if photoCatalog.photos.isEmpty {
        Text(L10n.tr("暂无可访问的图片")).foregroundStyle(Palette.secondary).padding()
      }
      if library.authorization == .limited {
        Button(L10n.tr("管理可访问的照片")) { library.manageLimitedAccess() }.frame(minHeight: 44)
      }
    }.navigationTitle(L10n.tr("选择图片")).navigationBarTitleDisplayMode(.inline)
      .navigationBarBackButtonHidden(true)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button(L10n.tr("返回")) { browsingPhotos = false }.accessibilityIdentifier("attachment-photo-library-back")
        }
        ToolbarItem(placement: .topBarTrailing) {
          Button(L10n.tr("取消")) { panel = false }.accessibilityIdentifier("attachment-photo-library-cancel")
        }
      }
      .accessibilityIdentifier("attachment-photo-grid")
      .safeAreaInset(edge: .bottom) { photoSelectionFooter }
      .task { photoCatalog.load() }
      .onChange(of: library.recent.map(\.id)) { _, _ in photoCatalog.load() }
  }
  private var photoSelectionFooter: some View {
    AttachmentPhotoSelectionFooter(selection: photoSelection, adding: addingPhotos, add: addSelectedPhotos)
      .background(Palette.background)
  }
  private func addSelectedPhotos() {
    guard !addingPhotos, !photoSelection.photos.isEmpty, let target = destination else { return }
    let photos = photoSelection.photos
    let requestID = importId
    addingPhotos = true
    Task {
      await store.addLocalAttachments(to: target, importId: UUID().uuidString,
        loaders: photos.map { photo in { try await library.input(for: photo) } })
      // An older import may finish after a new picker/target is opened.
      // Its original draft remains authoritative; it must not dismiss or edit
      // the new picker selection.
      guard importId == requestID, destination == target else { return }
      let saved = Set((store.drafts.values[target.key]?.attachments ?? []).compactMap(\.photoAssetId))
      for photo in photos where saved.contains(photo.id) { library.dismiss(photo) }
      photoSelection.photos = photos.filter { !saved.contains($0.id) }
      addingPhotos = false
      if photoSelection.photos.isEmpty { previewPhotos = nil; panel = false }
      else { photoSelection.error = L10n.tr("部分图片未能添加，请重试。") }
    }
  }

  private func performPendingAction() {
    guard let action = nextAction else { isPresenting = false; return }
    nextAction = nil
    switch action {
    case .shareTest(let url):
      shareURL = url
      showShare = true
    case .received:
      isPresenting = false
      NotificationCenter.default.post(name: Notification.Name("AgentHubShowReceivedAttachments"), object: nil)
    case .photos: showPhotos = true
    case .files: showFiles = true
    case .camera:
      if UIImagePickerController.isSourceTypeAvailable(.camera) { showCamera = true }
      else { isPresenting = false; store.error = L10n.tr("当前设备无法使用相机。") }

    }
  }
}

private struct AttachmentCamera: UIViewControllerRepresentable {
  let completion: (UIImage?) -> Void
  func makeCoordinator() -> Coordinator { Coordinator(completion: completion) }
  func makeUIViewController(context: Context) -> UIImagePickerController {
    let controller = UIImagePickerController()
    controller.sourceType = .camera
    controller.mediaTypes = [UTType.image.identifier]
    controller.delegate = context.coordinator
    return controller
  }
  func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
  final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    let completion: (UIImage?) -> Void
    init(completion: @escaping (UIImage?) -> Void) { self.completion = completion }
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { completion(nil) }
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
      completion(info[.originalImage] as? UIImage)
    }
  }
}
