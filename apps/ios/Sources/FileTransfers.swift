import SwiftUI
import QuickLook
import UniformTypeIdentifiers

struct ComputerFileManifest: Codable, Equatable, Sendable, Identifiable {
  let hostId: String
  let id: String
  let name: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
}
struct ComputerFileReceipt: Codable, Sendable, Identifiable {
  let hostId: String
  let id: String
  let name: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
  let phase: String
  let destinationPath: String
  let createdAt: String

  func validate(_ file: ComputerFileManifest) throws {
    guard hostId == file.hostId, id == file.id, name == file.name, mediaType == file.mediaType,
      sizeBytes == file.sizeBytes, sha256 == file.sha256, ["prepared", "completed"].contains(phase),
      !destinationPath.isEmpty else { throw HostServiceError.invalidResponse }
  }
}
struct ComputerFileCatalog: Decodable, Sendable {
  let hostId: String
  let destinationDirectory: String
  let maxFileBytes: Int?
  let files: [ComputerFileReceipt]
  var incomingFiles: [IncomingComputerFile]? = nil
}
private struct IncomingFileJournal: Codable {
  var files: [IncomingComputerFile] = []
  var localCopies: [IncomingComputerFile] = []
}
private struct PendingComputerFile: Codable, Sendable, Identifiable {
  let file: ComputerFileManifest
  var phase: String = "ready"
  var error: String?
  var id: String { file.id }
}

@MainActor private final class FileTransferModel: ObservableObject {
  let client: Client
  @Published var catalog: ComputerFileCatalog?
  @Published var pending: [PendingComputerFile] = []
  @Published var busy = false
  @Published var activity: String?
  @Published var error: String?
  @Published private(set) var incoming: [IncomingComputerFile] = []
  @Published private(set) var localCopies: [IncomingComputerFile] = []
  @Published private(set) var receivingId: String?
  @Published private(set) var receiveProgress = FileDownloadProgress()
  @Published var previewURL: URL?
  private var receiveTask: Task<Void, Never>?
  private var incomingUsable = true
  private let directory: URL
  private var journalUsable = true
  private var adapter: any AgentClient { get throws { try AgentClients.resolve(client) } }

  init(client: Client) {
    self.client = client
    let target = client.agentConnection?.target ?? client.codexDirect?.target ?? client.readingTarget
    let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
    let identity = (try? encoder.encode(target)) ?? Data(client.id.utf8)
    let testing = ProcessInfo.processInfo.arguments.contains("-ui-testing")
    directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent(testing ? "FileTransfersAcceptance" : "FileTransfers", isDirectory: true)
      .appendingPathComponent(HostService.hash(Data(client.id.utf8) + identity), isDirectory: true)
    do {
      if FileManager.default.fileExists(atPath: journalURL.path) {
        pending = try AgentCache.open(Data(contentsOf: journalURL), as: [PendingComputerFile].self)
        guard pending.allSatisfy({ $0.file.hostId == client.id && UUID(uuidString: $0.id) != nil }) else { throw HostServiceError.wrongHost }
        // A crash can occur at any request boundary. Restore by querying, never sending.
        for index in pending.indices where pending[index].phase != "ready" { pending[index].phase = "unknown" }
      }
    } catch { journalUsable = false; pending = []; self.error = L10n.tr("无法读取保存的传输记录；请保留手机数据并检查存储空间。") }
    do {
      let url = directory.appendingPathComponent("incoming.dat")
      if FileManager.default.fileExists(atPath: url.path) {
        let saved = try AgentCache.open(Data(contentsOf: url), as: IncomingFileJournal.self)
        for file in saved.files + saved.localCopies { try file.validate(hostId: client.id) }
        guard Set(saved.files.map(\.id)).count == saved.files.count else { throw HostServiceError.invalidResponse }
        incoming = saved.files; localCopies = saved.localCopies
      }
    } catch { incomingUsable = false; self.error = L10n.tr("无法读取保存的传输记录；请保留手机数据并检查存储空间。") }
  }
  private func saveIncoming() throws {
    let bytes = try AgentCache.seal(IncomingFileJournal(files: incoming, localCopies: localCopies))
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try bytes.write(to: directory.appendingPathComponent("incoming.dat"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }
  func hasLocalCopy(_ file: IncomingComputerFile) -> Bool { localCopies.contains { $0.sameFile(as: file) } }
  func receive(_ file: IncomingComputerFile) {
    guard receiveTask == nil, !busy, incomingUsable else { return }
    receivingId = file.id; receiveProgress = FileDownloadProgress(); error = nil
    receiveTask = Task {
      defer { receivingId = nil; receiveTask = nil }
      do {
        let service = try adapter
        let cached = hasLocalCopy(file) ? try await service.cachedReceivedFile(client, file: file) : nil
        let local: URL
        if let cached { local = cached }
        else { local = try await service.receiveComputerFile(client, file: file) { value in
          Task { @MainActor [weak self] in
            guard self?.receivingId == file.id else { return }
            self?.receiveProgress = value
          }
        } }
        try Task.checkCancellation()
        let previous = localCopies
        localCopies = localCopies.filter { $0.id != file.id } + [file]
        do { try saveIncoming() } catch { localCopies = previous; throw error }
        previewURL = local
      } catch is CancellationError {
        self.error = L10n.tr("下载已停止，可重新下载同一文件。")
      } catch { self.error = error.localizedDescription }
    }
  }
  func stopReceiving() { receiveTask?.cancel() }
  private var journalURL: URL { directory.appendingPathComponent("pending.dat") }
  private func localURL(_ id: String) -> URL { directory.appendingPathComponent(id + ".data") }
  private func persist() async throws {
    let data = try AgentCache.seal(pending), directory = directory, journal = journalURL
    try await Task.detached {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      try data.write(to: journal, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }.value
  }
  private func apply(_ receipt: ComputerFileReceipt) async throws {
    if let index = pending.firstIndex(where: { $0.id == receipt.id }) {
      try receipt.validate(pending[index].file)
      if receipt.phase == "completed" {
        let previous = pending
        pending.remove(at: index)
        do { try await persist() } catch { pending = previous; throw error }
        try? FileManager.default.removeItem(at: localURL(receipt.id))
      } else { pending[index].phase = "ready"; pending[index].error = nil; try await persist() }
    }
  }
  func refresh() async {
    guard !busy, receivingId == nil, journalUsable else { return }
    busy = true
    defer { busy = false }
    do {
      let value = try await adapter.fileTransfers(client)
      guard value.maxFileBytes.map({ $0 > 0 }) ?? true,
        value.files.allSatisfy({ $0.hostId == client.id && UUID(uuidString: $0.id) != nil && ["prepared", "completed"].contains($0.phase) })
      else { throw HostServiceError.invalidResponse }
      for file in value.files { try await apply(file) }
      catalog = value
      if incomingUsable, let files = value.incomingFiles {
        for file in files { try file.validate(hostId: client.id) }
        guard Set(files.map(\.id)).count == files.count else { throw HostServiceError.invalidResponse }
        let previous = incoming; incoming = files
        do { try saveIncoming() } catch { incoming = previous; throw error }
      }
      error = nil
    } catch { self.error = error.localizedDescription }
  }
  func reconcileUploads() async {
    for job in UploadCenter.shared.jobs.values where UploadCenter.sameTarget(job.client, client) && job.manifest.purpose == "file-transfer" {
      guard let receipt = job.receipt else { continue }
      do {
        if receipt.phase == "completed" {
          let file = try receipt.value(ComputerFileReceipt.self)
          try await apply(file)
          if let old = catalog {
            catalog = ComputerFileCatalog(hostId: old.hostId, destinationDirectory: old.destinationDirectory,
              maxFileBytes: old.maxFileBytes, files: [file] + old.files.filter { $0.id != file.id })
          }
        }
        else if receipt.phase == "cancelled", pending.contains(where: { $0.id == job.id }) {
          let previous = pending; pending.removeAll { $0.id == job.id }
          do { try await persist() } catch { pending = previous; throw error }
          try? FileManager.default.removeItem(at: localURL(job.id))
        }
      } catch { self.error = error.localizedDescription }
    }
  }
  func importFiles(_ urls: [URL]) async {
    guard !busy, let catalog else { return }
    busy = true
    defer { busy = false; activity = nil }
    var imported: [String] = []
    for url in urls {
      do {
        // The legacy EasyWork upload path still buffers data. Its streaming upgrade
        // is separate from independent receiving; do not offer unbounded imports.
        let hostId = client.id, directory = directory
        let limit = client.agentConnection?.target.agentId == "easywork" ? min(catalog.maxFileBytes ?? Int.max, 20 * 1024 * 1024) : catalog.maxFileBytes
        activity = L10n.tr("正在读取 \(url.lastPathComponent)…")
        let file = try await Task.detached { () throws -> ComputerFileManifest in
          let scoped = url.startAccessingSecurityScopedResource()
          defer { if scoped { url.stopAccessingSecurityScopedResource() } }
          let metadata = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .contentTypeKey])
          guard metadata.isRegularFile == true, let size = metadata.fileSize, size > 0 else { throw HostServiceError.message(L10n.tr("请选择非空的普通文件。")) }
          if let limit, size > limit { throw HostServiceError.message(L10n.tr("本次传输的文件大小上限为 \(ByteCountFormatter.string(fromByteCount: Int64(limit), countStyle: .file))。")) }
          let id = UUID().uuidString.lowercased()
          try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
          let snapshot = directory.appendingPathComponent(id + ".data")
          try FileManager.default.copyItem(at: url, to: snapshot)
          try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: snapshot.path)
          let (count, hash) = try UploadCenter.fileHash(snapshot)
          guard count == size else { try? FileManager.default.removeItem(at: snapshot); throw HostServiceError.message(L10n.tr("文件在读取时发生变化，请重新选择。")) }
          let file = ComputerFileManifest(hostId: hostId, id: id, name: url.lastPathComponent, mediaType: metadata.contentType?.preferredMIMEType ?? "application/octet-stream", sizeBytes: count, sha256: hash)
          return file
        }.value
        pending.append(PendingComputerFile(file: file))
        try await persist()
        imported.append(file.id)
      } catch { self.error = error.localizedDescription }
    }
    let batchId = UUID().uuidString
    let totalBytes = pending.filter { imported.contains($0.id) }.reduce(0) { $0 + $1.file.sizeBytes }
    for (index, id) in imported.enumerated() {
      await UploadBatch.$current.withValue(UploadBatch(id: batchId, index: index, count: imported.count, totalBytes: totalBytes)) { await deliver(id) }
    }
  }
  private func deliver(_ id: String) async {
    guard let index = pending.firstIndex(where: { $0.id == id }) else { return }
    let file = pending[index].file
    do {
      let url = localURL(id)
      let (size, hash) = try await Task.detached { try UploadCenter.fileHash(url) }.value
      guard size == file.sizeBytes, hash == file.sha256 else { throw HostServiceError.message(L10n.tr("手机保存的文件已变化，请保留原记录并重新选择文件。")) }
      pending[index].phase = "unknown"
      pending[index].error = nil
      try await persist() // Durable immutable intent before the first remote write.
      activity = L10n.tr("正在上传 \(file.name)…")
      var receipt = try await adapter.prepareFileTransfer(client, file: file)
      if receipt.phase != "completed" { receipt = try await adapter.uploadTransferFile(client, file: file, source: url) }
      try await apply(receipt)
      if let old = catalog {
        catalog = ComputerFileCatalog(hostId: old.hostId, destinationDirectory: old.destinationDirectory, maxFileBytes: old.maxFileBytes, files: [receipt] + old.files.filter { $0.id != id })
      }
      error = nil
    } catch {
      if let index = pending.firstIndex(where: { $0.id == id }) {
        pending[index].error = error is CancellationError || (error as? URLError)?.code == .cancelled
          ? L10n.tr("连接等待已结束；文件已保留，请查询接收结果。") : error.localizedDescription
        do { try await persist() } catch { self.error = L10n.tr("无法保存传输状态，请检查手机存储空间。") }
      }
    }
  }
  func retry(_ id: String) async {
    guard !busy, journalUsable else { return }
    busy = true
    defer { busy = false; activity = nil }
    if pending.first(where: { $0.id == id })?.phase == "unknown" {
      activity = L10n.tr("正在查询电脑接收结果…")
      do {
        let receipt = try await adapter.fileTransfer(client, id: id)
        try await apply(receipt)
        if receipt.phase == "completed", let old = catalog {
          catalog = ComputerFileCatalog(hostId: old.hostId, destinationDirectory: old.destinationDirectory, maxFileBytes: old.maxFileBytes, files: [receipt] + old.files.filter { $0.id != id })
        }
      } catch HostServiceError.rejected(404, _) {
        if let index = pending.firstIndex(where: { $0.id == id }) {
          pending[index].phase = "ready"
          pending[index].error = nil
          do { try await persist() } catch { self.error = error.localizedDescription }
        }
      } catch { self.error = error.localizedDescription }
    } else { await deliver(id) }
  }
}

struct FileTransfersView: View {
  @ObservedObject private var uploads = UploadCenter.shared
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @StateObject private var model: FileTransferModel
  @Environment(\.dismiss) private var dismiss
  @State private var selecting = false
  init(client: Client) { _model = StateObject(wrappedValue: FileTransferModel(client: client)) }
  var body: some View {
    NavigationStack {
      List {
        Section {
          Label(model.client.name, systemImage: "laptopcomputer").font(.headline)
          if let catalog = model.catalog {
            Text(L10n.tr("保存到：\(catalog.destinationDirectory)")).font(.footnote).textSelection(.enabled)
              .accessibilityIdentifier("file-transfer-destination")
          }
          Button { selecting = true } label: { Label(L10n.tr("选择文件上传"), systemImage: "arrow.up.doc") }
            .disabled(model.catalog == nil || model.busy || model.receivingId != nil).accessibilityIdentifier("choose-transfer-files")
        } footer: { Text(L10n.tr("手机 → 电脑 · 同名文件会保留独立副本。")) }
        Section { UploadJobsView(client: model.client, independent: true) }
        if let activity = model.activity { Section { ProgressView(activity).accessibilityIdentifier("file-transfer-progress") } }
        else if model.busy { Section { ProgressView(L10n.tr("正在查询传输记录…")) } }
        if let error = model.error { Section { Text(error).foregroundStyle(.red) } }
        if model.catalog?.incomingFiles != nil || !model.incoming.isEmpty {
          Section(L10n.tr("电脑发来的文件")) {
            if model.incoming.isEmpty { Text(L10n.tr("在电脑的在线客户端中选择此手机，即可发送文件。")).foregroundStyle(.secondary) }
            ForEach(model.incoming) { file in
              VStack(alignment: .leading, spacing: 8) {
                Label(file.name, systemImage: "arrow.down.doc")
                Text(ByteCountFormatter.string(fromByteCount: Int64(file.sizeBytes), countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                if model.receivingId == file.id {
                  FileDownloadProgressView(progress: model.receiveProgress)
                  Button(L10n.tr("停止下载")) { model.stopReceiving() }.accessibilityIdentifier("stop-incoming-\(file.id)")
                } else if model.hasLocalCopy(file) || !["failed", "cancelled"].contains(file.phase) {
                  Button(model.hasLocalCopy(file) ? L10n.tr("打开文件") : L10n.tr("下载并打开")) { model.receive(file) }
                    .disabled(model.busy || model.receivingId != nil).accessibilityIdentifier("receive-transfer-\(file.id)")
                } else { Text(L10n.tr("电脑已停止此文件传输。")).font(.caption).foregroundStyle(.secondary) }
              }.accessibilityIdentifier("incoming-transfer-\(file.id)")
            }
          }
        }
        if !model.pending.isEmpty {
          Section(L10n.tr("待完成")) {
            ForEach(model.pending) { pending in
              VStack(alignment: .leading, spacing: 8) {
                Text(pending.file.name)
                Text(pending.phase == "unknown" ? L10n.tr("接收结果待确认 · 文件已保留在手机") : L10n.tr("待上传"))
                  .font(.caption).foregroundStyle(.secondary)
                if let error = pending.error { Text(error).font(.caption).foregroundStyle(.red) }
                Button(pending.phase == "unknown" ? L10n.tr("查询结果") : L10n.tr("上传到电脑")) { Task { await model.retry(pending.id) } }
                .disabled(model.busy || model.receivingId != nil || model.catalog == nil).accessibilityIdentifier("retry-transfer-\(pending.id)")
              }
            }
          }
        }
        Section(L10n.tr("已上传")) {
          let completed = model.catalog?.files.filter { $0.phase == "completed" } ?? []
          if completed.isEmpty { Text(L10n.tr("上传完成后，文件和保存位置会显示在这里。")).foregroundStyle(.secondary) }
          ForEach(completed) { file in
            VStack(alignment: .leading, spacing: 6) {
              Label(file.name, systemImage: "checkmark.circle.fill").accessibilityIdentifier("completed-transfer-\(file.name)")
              Text(ByteCountFormatter.string(fromByteCount: Int64(file.sizeBytes), countStyle: .file)).font(.caption).foregroundStyle(.secondary)
              Text(file.destinationPath).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
              Button(L10n.tr("复制电脑路径")) { UIPasteboard.general.string = file.destinationPath }
                .font(.caption).accessibilityIdentifier("copy-transfer-path-\(file.name)")
            }
          }
        }
      }.navigationTitle(L10n.tr("文件互传")).navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("完成")) { dismiss() } }
          ToolbarItem(placement: .primaryAction) { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.disabled(model.busy || model.receivingId != nil).accessibilityLabel(L10n.tr("刷新传输记录")) }
        }
    }.task { await model.refresh(); await model.reconcileUploads() }
      .onChange(of: uploads.jobs.values.map { $0.id + ":" + $0.phase }.sorted()) { _, _ in Task { await model.reconcileUploads() } }
      .quickLookPreview($model.previewURL)
      .onDisappear { model.stopReceiving() }
      .fileImporter(isPresented: $selecting, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
        selecting = false
        switch result {
        case .success(let urls): Task { await model.importFiles(urls) }
        case .failure(let error): model.error = error.localizedDescription
        }
      }
  }
}
