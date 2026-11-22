import SwiftUI

/// Dedicated native DEV entry: an actual authorized resource, no synthetic
/// desktop message and no model turn. Release and other bundles remain inert.
private struct EasyWorkMediaAcceptance: ViewModifier {
  @ObservedObject var store: SessionStore
  @ObservedObject var coordinator: MediaPlaybackCoordinator
  @State private var anchor = UUID()
  @State private var probeId: String?
  @State private var probe: MediaReadDescriptor?
  @State private var probeTask: Task<Void, Never>?
  @State private var probeStatus = "未保留读取"
  #if DEBUG && targetEnvironment(simulator)
  private struct Source: Decodable {
    let version: Int
    let caseId: String
    let newModelInputsAuthorized: Int
    let target: AgentRuntimeTarget
    let threadId: String
    let resourceId: String
    let name: String
    let mediaType: String
    let sizeBytes: Int
    let sha256: String
  }
  private var source: Source? {
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.f77acceptance",
      ProcessInfo.processInfo.arguments.contains("-f77-media-acceptance"),
      let json = ProcessInfo.processInfo.environment["F77_MEDIA_SOURCE_JSON"], json.utf8.count <= 8192,
      let value = try? JSONDecoder().decode(Source.self, from: Data(json.utf8)),
      value.version == 1, UUID(uuidString: value.caseId) != nil, value.newModelInputsAuthorized == 0,
      value.target.agentId == "easywork", value.target.account != nil,
      !value.threadId.isEmpty, !value.resourceId.isEmpty, value.resourceId.utf8.count <= 512,
      value.name == (value.name as NSString).lastPathComponent,
      value.sizeBytes >= 0, value.sizeBytes <= 2 * 1024 * 1024 * 1024,
      value.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      MediaPlaybackFormat.accepts(name: value.name, type: value.mediaType) else { return nil }
    return value
  }
  private struct ImportSource: Decodable {
    let caseId: String
    let target: AgentRuntimeTarget
    let directory: String
    let threadId: String
    let newModelInputsAuthorized: Int
  }
  @State private var importStatus = "等待导入"
  private var importSource: ImportSource? {
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.f77acceptance",
      ProcessInfo.processInfo.arguments.contains("-f77-media-import"),
      let raw = ProcessInfo.processInfo.environment["F77_IMPORT_JSON"], raw.utf8.count <= 8192,
      let value = try? JSONDecoder().decode(ImportSource.self, from: Data(raw.utf8)),
      UUID(uuidString: value.caseId) != nil, value.newModelInputsAuthorized == 0,
      ProcessInfo.processInfo.environment["F77_IMPORT_CASE_ID"] == value.caseId,
      ProcessInfo.processInfo.environment["F77_NEW_MODEL_INPUTS_AUTHORIZED"] == "0" else { return nil }
    return value
  }
  private func importVideo(_ value: ImportSource, client: Client) {
    guard importStatus == "等待导入" else { return }
    importStatus = "导入中"
    Task {
      do {
        let result = try await EasyWorkAgentClient().f77ImportSyntheticVideo(client, target: value.target,
          directory: value.directory, threadId: value.threadId, caseId: value.caseId)
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let file = support.appendingPathComponent("F77Acceptance/source-" + value.caseId + ".json")
        try JSONSerialization.data(withJSONObject: result).write(to: file, options: .withoutOverwriting)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        importStatus = "导入完成"
      } catch { importStatus = "导入失败或结果待核" }
    }
  }
  private func retainProbe(_ client: Client, _ file: RemoteAttachment) {
    guard probeId == nil, probeTask == nil else { return }
    let id = UUID().uuidString.lowercased(); probeId = id; probeStatus = "准备独立读取"
    probeTask = Task {
      defer { if probeId == id { probeTask = nil } }
      do {
        let service = try AgentClients.resolve(client)
        let value = try await service.prepareMediaRead(client, file: file, readId: id, progress: { _, _ in })
        guard !Task.isCancelled, probeId == id else { return }
        probe = value; probeStatus = value == nil ? "无渐进读取能力" : "读取已保留"
      } catch { if !Task.isCancelled, probeId == id { probeStatus = "独立读取准备失败" } }
    }
  }
  private func checkProbe(_ client: Client, _ file: RemoteAttachment) {
    guard let id = probeId, let read = probe, probeTask == nil else { return }
    probeTask = Task {
      defer { if probeId == id { probeTask = nil } }
      do {
        _ = try await AgentClients.resolve(client).mediaBytes(client, source: file, read: read, offset: 0, count: min(64, file.sizeBytes))
        if !Task.isCancelled, probeId == id { probeStatus = "原读取仍有效" }
      } catch HostServiceError.rejected(410, _) {
        if !Task.isCancelled, probeId == id { probeStatus = "原读取已过期" }
      } catch { if !Task.isCancelled, probeId == id { probeStatus = "原读取核对失败" } }
    }
  }
  private func releaseProbe(_ client: Client, _ file: RemoteAttachment, publish: Bool = false) {
    guard let id = probeId else { return }
    let work = probeTask; work?.cancel(); probeId = nil; probe = nil
    probeTask = Task {
      await work?.value
      let released = (try? AgentClients.resolve(client)).map { service in
        Task { await service.cancelMediaRead(client, file: file, readId: id) }
      }
      let confirmed = await released?.value ?? false
      if publish, probeId == nil { probeStatus = confirmed ? "独立读取已释放" : "读取释放待核" }
      if probeId == nil { probeTask = nil }
    }
  }
  #endif
  @ViewBuilder func body(content: Content) -> some View {
    #if DEBUG && targetEnvironment(simulator)
    if let source, let client = store.client, client.agentConnection?.target == source.target,
      store.saved.clients.filter({ $0.agentConnection?.target == source.target }).count == 1 {
      let file = RemoteAttachment(id: source.resourceId, hostId: client.id, threadId: source.threadId,
        name: source.name, mediaType: source.mediaType, sizeBytes: source.sizeBytes, sha256: source.sha256)
      content.safeAreaInset(edge: .bottom) {
        VStack(spacing: 8) {
          MediaInlineCard(coordinator: coordinator, anchor: anchor, fallback:
            Button("DEV · 视频读取验收") { coordinator.open(client: client, file: file, anchor: anchor) }
              .accessibilityIdentifier("f77-media-entry"))
          HStack {
            Button("保留读取") { retainProbe(client, file) }.disabled(probeId != nil || probeTask != nil).accessibilityIdentifier("f77-probe-retain")
            Button("核对原读取") { checkProbe(client, file) }.disabled(probe == nil || probeTask != nil).accessibilityIdentifier("f77-probe-check")
            Button("释放读取") { releaseProbe(client, file, publish: true) }.disabled(probeId == nil).accessibilityIdentifier("f77-probe-release")
          }.buttonStyle(.bordered).font(.caption)
          Text(probeStatus).font(.caption2).accessibilityIdentifier("f77-probe-status")
        }.padding(8).background(.regularMaterial)
          .onDisappear { releaseProbe(client, file) }
      }
    } else if let value = importSource, let client = store.client,
      client.agentConnection?.target == value.target,
      store.saved.clients.filter({ $0.agentConnection?.target == value.target }).count == 1 {
      content.safeAreaInset(edge: .bottom) {
        VStack {
          Button("DEV · 导入合成视频") { importVideo(value, client: client) }
            .disabled(importStatus != "等待导入").accessibilityIdentifier("f77-import")
          Text(importStatus).accessibilityIdentifier("f77-import-status")
        }.padding(8).background(.regularMaterial)
      }
    } else { content }
    #else
    content
    #endif
  }
}

extension View {
  @MainActor @ViewBuilder func easyWorkMediaAcceptance(store: SessionStore,
    coordinator: MediaPlaybackCoordinator) -> some View {
    #if DEBUG && targetEnvironment(simulator)
    if Bundle.main.bundleIdentifier == "com.easyai.agenthub.f77acceptance",
      (ProcessInfo.processInfo.arguments.contains("-f77-media-acceptance") ||
        ProcessInfo.processInfo.arguments.contains("-f77-media-import")) {
      self.modifier(EasyWorkMediaAcceptance(store: store, coordinator: coordinator))
    } else { self }
    #else
    self
    #endif
  }
}
