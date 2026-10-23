import SwiftUI
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
  let maxFileBytes: Int
  let files: [ComputerFileReceipt]
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
  private let directory: URL
  private var journalUsable = true
  private var adapter: any AgentClient { get throws { try AgentClients.resolve(client) } }

  init(client: Client) {
    self.client = client
    let target = client.agentConnection?.target ?? client.readingTarget
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
  }
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
    guard !busy, journalUsable else { return }
    busy = true
    defer { busy = false }
    do {
      let value = try await adapter.fileTransfers(client)
      guard value.maxFileBytes > 0, value.maxFileBytes <= 20 * 1024 * 1024,
        value.files.allSatisfy({ $0.hostId == client.id && UUID(uuidString: $0.id) != nil && ["prepared", "completed"].contains($0.phase) })
      else { throw HostServiceError.invalidResponse }
      for file in value.files { try await apply(file) }
      catalog = value
      error = nil
    } catch { self.error = error.localizedDescription }
  }
  func importFiles(_ urls: [URL]) async {
    guard !busy, let catalog else { return }
    busy = true
    defer { busy = false; activity = nil }
    for url in urls {
      do {
        let hostId = client.id, limit = catalog.maxFileBytes, directory = directory
        activity = L10n.tr("正在读取 \(url.lastPathComponent)…")
        let file = try await Task.detached { () throws -> ComputerFileManifest in
          let scoped = url.startAccessingSecurityScopedResource()
          defer { if scoped { url.stopAccessingSecurityScopedResource() } }
          let metadata = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .contentTypeKey])
          guard metadata.isRegularFile == true, let size = metadata.fileSize, size > 0, size <= limit else { throw HostServiceError.message(L10n.tr("请选择非空且不超过 20 MB 的文件。")) }
          let data = try Data(contentsOf: url)
          guard data.count == size else { throw HostServiceError.message(L10n.tr("文件在读取时发生变化，请重新选择。")) }
          let file = ComputerFileManifest(hostId: hostId, id: UUID().uuidString.lowercased(), name: url.lastPathComponent, mediaType: metadata.contentType?.preferredMIMEType ?? "application/octet-stream", sizeBytes: data.count, sha256: HostService.hash(data))
          try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
          try data.write(to: directory.appendingPathComponent(file.id + ".data"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
          return file
        }.value
        pending.append(PendingComputerFile(file: file))
        try await persist()
        await deliver(file.id)
      } catch { self.error = error.localizedDescription }
    }
  }
  private func deliver(_ id: String) async {
    guard let index = pending.firstIndex(where: { $0.id == id }) else { return }
    let file = pending[index].file
    do {
      let url = localURL(id)
      let data = try await Task.detached { try Data(contentsOf: url) }.value
      guard data.count == file.sizeBytes, HostService.hash(data) == file.sha256 else { throw HostServiceError.message(L10n.tr("手机保存的文件已变化，请保留原记录并重新选择文件。")) }
      pending[index].phase = "unknown"
      pending[index].error = nil
      try await persist() // Durable immutable intent before the first remote write.
      activity = L10n.tr("正在上传 \(file.name)…")
      var receipt = try await adapter.prepareFileTransfer(client, file: file)
      if receipt.phase != "completed" { receipt = try await adapter.uploadFileTransfer(client, file: file, data: data) }
      try await apply(receipt)
      if let old = catalog {
        catalog = ComputerFileCatalog(hostId: old.hostId, destinationDirectory: old.destinationDirectory, maxFileBytes: old.maxFileBytes, files: [receipt] + old.files.filter { $0.id != id })
      }
      error = nil
    } catch {
      if let index = pending.firstIndex(where: { $0.id == id }) {
        pending[index].error = error.localizedDescription
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
            .disabled(model.catalog == nil || model.busy).accessibilityIdentifier("choose-transfer-files")
        } footer: { Text(L10n.tr("手机 → 电脑 · 每个文件最多 20 MB。同名文件会保留独立副本。")) }
        if let activity = model.activity { Section { ProgressView(activity).accessibilityIdentifier("file-transfer-progress") } }
        else if model.busy { Section { ProgressView(L10n.tr("正在查询传输记录…")) } }
        if let error = model.error { Section { Text(error).foregroundStyle(.red) } }
        if !model.pending.isEmpty {
          Section(L10n.tr("待完成")) {
            ForEach(model.pending) { pending in
              VStack(alignment: .leading, spacing: 8) {
                Text(pending.file.name)
                Text(pending.phase == "unknown" ? L10n.tr("接收结果待确认 · 文件已保留在手机") : L10n.tr("待上传"))
                  .font(.caption).foregroundStyle(.secondary)
                if let error = pending.error { Text(error).font(.caption).foregroundStyle(.red) }
                Button(pending.phase == "unknown" ? L10n.tr("查询结果") : L10n.tr("上传到电脑")) { Task { await model.retry(pending.id) } }
                  .disabled(model.busy || model.catalog == nil).accessibilityIdentifier("retry-transfer-\(pending.id)")
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
          ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("完成")) { dismiss() }.disabled(model.busy) }
          ToolbarItem(placement: .primaryAction) { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.disabled(model.busy).accessibilityLabel(L10n.tr("刷新传输记录")) }
        }
    }.task { await model.refresh() }
      .interactiveDismissDisabled(model.busy)
      .fileImporter(isPresented: $selecting, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
        selecting = false
        switch result {
        case .success(let urls): Task { await model.importFiles(urls) }
        case .failure(let error): model.error = error.localizedDescription
        }
      }
  }
}
