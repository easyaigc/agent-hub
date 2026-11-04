import SwiftUI
import MobileCore

func projectGitTargetJSON(_ target: AgentRuntimeTarget) throws -> String {
  String(decoding: try JSONSerialization.data(withJSONObject: ["agentId": target.agentId, "hostId": target.hostId,
    "workspaceId": target.workspaceId, "runtimeId": target.runtimeId], options: [.sortedKeys]), as: UTF8.self)
}
struct ProjectGitMetric: Decodable, Identifiable {
  let kind: String; let symbol: String; let value: String
  var id: String { kind }
  var label: String { L10n.tr(["changed":"未提交文件", "unpublished":"未推送提交", "ahead":"领先项目主线", "behind":"落后项目主线", "conflicts":"冲突文件"][kind] ?? "Git 状态") }
}
struct ProjectGitComparison: Decodable {
  let ref: String?; let state: String; let reason: String?; let ahead: Int?; let behind: Int?; let source: String?
}
struct ProjectGitChanges: Decodable {
  let changed: Int; let staged: Int; let unstaged: Int; let untracked: Int; let conflicts: Int; let submodules: Int
}
struct ProjectGitWorktree: Decodable, Identifiable {
  let id: String; let displayPath: String; let state: String; let reason: String?; let branch: String?; let head: String?
  let unborn: Bool; let shallow: Bool; let changes: ProjectGitChanges?
  let upstream: ProjectGitComparison; let publish: ProjectGitComparison; let baseline: ProjectGitComparison
  let operation: String?; let consistency: String; let metrics: [ProjectGitMetric]?
}
struct ProjectGitRepository: Decodable, Identifiable { let id: String; let name: String; let worktrees: [ProjectGitWorktree] }
struct ProjectGitSnapshot: Decodable {
  struct Issue: Decodable { let reason: String; let displayPath: String? }
  let schemaVersion: Int; let target: AgentRuntimeTarget; let projectId: String; let snapshotId: String
  let observedAt: String; let kind: String; let complete: Bool; let issues: [Issue]; let remoteKnowledge: String
  let repositories: [ProjectGitRepository]
  struct Item: Identifiable { let repository: ProjectGitRepository; let tree: ProjectGitWorktree; var id: String { tree.id } }
  var items: [Item] { repositories.flatMap { repo in repo.worktrees.map { Item(repository: repo, tree: $0) } } }
}
struct ProjectGitFile: Decodable, Identifiable {
  let id: String; let path: String; let oldPath: String?; let staged: Bool; let unstaged: Bool; let untracked: Bool; let conflict: Bool; let submodule: Bool
}
struct ProjectGitFilePage: Decodable {
  let schemaVersion: Int; let target: AgentRuntimeTarget; let projectId: String; let snapshotId: String
  let worktreeId: String; let files: [ProjectGitFile]; let nextCursor: String?; let truncated: Bool
}
struct ProjectGitContext: Identifiable {
  let client: Client; let target: AgentRuntimeTarget; let project: Project; let sourceKey: String
  var id: String { sourceKey + ":" + project.id }
}
func projectGitReason(_ reason: String?) -> String {
  L10n.tr(["unconfigured":"尚未配置比较目标", "missing-ref":"本地缺少分支记录", "shallow-history":"历史不完整，差距未知", "unborn":"尚无提交", "unrelated-history":"历史无共同祖先", "timeout":"仓库读取超时", "output-limit":"仓库文件过多，暂无法读取", "git-unavailable":"电脑尚未安装可用的 Git", "git-failed":"Git 状态暂不可用", "directory-unavailable":"项目目录暂不可用", "outside-project":"目录超出项目范围", "scan-limit":"仓库扫描未完成", "changed-during-read":"读取期间有变化，请刷新", "unsupported":"此仓库类型暂不支持"][reason ?? ""] ?? "Git 状态暂不可用")
}

@MainActor final class ProjectGitStore: ObservableObject {
  static let shared = ProjectGitStore()
  struct Entry {
    var snapshot: ProjectGitSnapshot?; var failed: Bool = false; var attempted: Date
    var index: Int = 0; var lastRotation: Date = Date(); var pausedUntil: Date = .distantPast
  }
  @Published private var entries: [String: Entry] = [:]
  private var pending: [String: Task<Void, Never>] = [:]
  private var activeReads = 0
  func key(_ client: Client, _ target: AgentRuntimeTarget, _ projectId: String) -> String {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let connection = (try? encoder.encode(client.agentConnection)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    return [client.id, client.endpoint ?? "", client.readingScopeId ?? "", connection,
      (try? projectGitTargetJSON(target)) ?? "", projectId].joined(separator: "\n")
  }
  func entry(_ client: Client, _ target: AgentRuntimeTarget, _ projectId: String) -> Entry? { entries[key(client,target,projectId)] }
  func current(_ client: Client, _ target: AgentRuntimeTarget, _ projectId: String) -> ProjectGitSnapshot.Item? {
    guard let entry = entry(client,target,projectId), let rows = entry.snapshot?.items, !rows.isEmpty else { return nil }
    return rows[min(entry.index, rows.count - 1)]
  }
  func advance(_ client: Client, _ target: AgentRuntimeTarget, _ projectId: String, manual: Bool) {
    let key = key(client,target,projectId)
    guard var value = entries[key], let count = value.snapshot?.items.count, count > 1 else { return }
    let now = Date()
    guard manual || (now >= value.pausedUntil && now.timeIntervalSince(value.lastRotation) >= 4.9) else { return }
    value.index = (value.index + 1) % count; value.lastRotation = now
    if manual { value.pausedUntil = now.addingTimeInterval(10) }
    entries[key] = value
  }
  func refresh(_ client: Client, _ target: AgentRuntimeTarget, _ projectId: String, force: Bool = false) async {
    guard !client.isDemo, client.online else { return }
    let key = key(client,target,projectId)
    if let task = pending[key] { await task.value; return }
    guard pending.count < 16, force || Date().timeIntervalSince(entries[key]?.attempted ?? .distantPast) >= 15 else { return }
    let task = Task {
      while self.activeReads >= 2 && !Task.isCancelled { do { try await Task.sleep(for: .milliseconds(100)) } catch { return } }
      guard !Task.isCancelled else { return }
      self.activeReads += 1
      defer { self.activeReads -= 1; self.pending[key] = nil }
      do {
        var bound = client; bound.readingTarget = target
        let bytes = try await AgentClients.resolve(bound).projectGit(bound, projectId: projectId, target: target, refresh: force)
        guard bytes.count <= 131072, let raw = String(data: bytes, encoding: .utf8) else { throw HostServiceError.invalidResponse }
        let projected = try ProjectGitRules.shared.project(json: raw, targetJson: projectGitTargetJSON(target), projectId: projectId)
        let snapshot = try JSONDecoder().decode(ProjectGitSnapshot.self, from: Data(projected.utf8))
        guard try projectGitTargetJSON(snapshot.target) == projectGitTargetJSON(target), snapshot.projectId == projectId else { throw HostServiceError.wrongHost }
        let previous = self.entries[key]
        let selected = previous.flatMap { entry in entry.snapshot?.items.isEmpty == false ? entry.snapshot?.items[min(entry.index,entry.snapshot!.items.count-1)].id : nil }
        let index = snapshot.items.firstIndex { $0.id == selected } ?? 0
        self.entries[key] = Entry(snapshot: snapshot, attempted: Date(), index: index)
      } catch {
        var previous = self.entries[key] ?? Entry(snapshot: nil, attempted: Date())
        previous.failed = true; previous.attempted = Date(); self.entries[key] = previous
      }
      if self.entries.count > 64, let oldest = self.entries.min(by: { $0.value.attempted < $1.value.attempted })?.key { self.entries.removeValue(forKey: oldest) }
    }
    pending[key] = task
    await task.value
  }
}

struct ProjectGitProjectLabel: View {
  let client: Client; let target: AgentRuntimeTarget; let project: Project; let supported: Bool; let active: Bool
  let expanded: Bool
  @ObservedObject private var git = ProjectGitStore.shared
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.isFocused) private var focused
  @State private var hovered = false
  @State private var voiceOver = UIAccessibility.isVoiceOverRunning
  @ScaledMetric(relativeTo:.subheadline) private var titleSize = 14.0
  @ScaledMetric(relativeTo:.caption) private var captionSize = 11.0
  var body: some View {
    let entry = git.entry(client,target,project.id)
    let snapshot = entry?.snapshot
    let current = git.current(client,target,project.id)
    HStack(spacing: 10) {
      Image(systemName: "folder").frame(width:20)
      VStack(alignment:.leading,spacing:1) {
        Text(project.name).font(.system(size:titleSize)).lineLimit(1).truncationMode(.tail)
          .frame(maxWidth:.infinity,alignment:.leading)
        if supported {
          if snapshot?.kind != "non-git" {
            HStack(spacing:6) {
              if let current {
                if (snapshot?.items.count ?? 0) > 1 {
                  Text(current.repository.worktrees.count > 1 ? current.tree.displayPath : current.repository.name)
                    .lineLimit(1).truncationMode(.middle).frame(maxWidth:64,alignment:.leading)
                }
                if current.tree.state == "ready" {
                  ForEach(current.tree.metrics ?? []) { metric in
                    HStack(spacing:3) { Image(systemName:metric.symbol).font(.system(size:captionSize)); Text(metric.value).monospacedDigit() }
                      .foregroundStyle(metric.kind == "conflicts" ? Color.red : .secondary)
                      .accessibilityElement(children:.ignore).accessibilityLabel(metric.label + " " + metric.value)
                  }
                  if entry?.failed == true || !client.online { Image(systemName:"clock").accessibilityLabel(L10n.tr("缓存状态")) }
                } else { Text(projectGitReason(current.tree.reason)).lineLimit(1) }
              } else { Text(snapshot?.kind == "unknown" ? L10n.tr("仓库扫描未完成") : entry?.failed == true ? L10n.tr("Git 状态暂不可用") : L10n.tr("正在检查仓库")).lineLimit(1) }
            }.font(.system(size:captionSize)).foregroundStyle(.secondary)
              .accessibilityIdentifier("project-git-summary-\(project.id)")
          }
        } else if !client.isDemo {
          Text(L10n.tr("此电脑暂未提供仓库状态")).font(.system(size:captionSize)).foregroundStyle(.secondary).lineLimit(1)
        }
      }
      Image(systemName:expanded ? "chevron.down" : "chevron.right").font(.caption2).foregroundStyle(.secondary)
    }.frame(minHeight:44).contentShape(Rectangle())
      .onHover { hovered = $0 }
      .onReceive(NotificationCenter.default.publisher(for:UIAccessibility.voiceOverStatusDidChangeNotification)) { _ in voiceOver = UIAccessibility.isVoiceOverRunning }
      .task(id:"\(git.key(client,target,project.id)):\(active):\(scenePhase)") {
        guard active, supported, scenePhase == .active else { return }
        while !Task.isCancelled {
          await git.refresh(client,target,project.id)
          do { try await Task.sleep(for:.seconds(30)) } catch { return }
        }
      }
      .task(id:"rotation:\(active):\(scenePhase):\(reduceMotion):\(hovered):\(focused):\(voiceOver):\(snapshot?.items.count ?? 0)") {
        guard active, supported, scenePhase == .active, !reduceMotion, !hovered, !focused, !voiceOver, (snapshot?.items.count ?? 0) > 1 else { return }
        while !Task.isCancelled {
          do { try await Task.sleep(for:.seconds(5)) } catch { return }
          if !hovered && !UIAccessibility.isVoiceOverRunning { git.advance(client,target,project.id,manual:false) }
        }
      }
  }
}
struct ProjectGitCycleButton: View {
  let client: Client; let target: AgentRuntimeTarget; let projectId: String
  @ObservedObject private var git = ProjectGitStore.shared
  var body: some View {
    if let value = git.entry(client,target,projectId), let count = value.snapshot?.items.count, count > 1 {
      Button { git.advance(client,target,projectId,manual:true) } label: {
        Text("\(min(value.index+1,count))/\(count)").font(.system(size:11)).monospacedDigit().foregroundStyle(.secondary).frame(width:44,height:44)
      }.accessibilityLabel(L10n.tr("切换下一个仓库"))
        .accessibilityValue("\(min(value.index+1,count)) / \(count)")
        .accessibilityIdentifier("project-git-next-\(projectId)")
    }
  }
}

struct ProjectGitDetailsView: View {
  let context: ProjectGitContext
  @ObservedObject private var git = ProjectGitStore.shared
  @Environment(\.dismiss) private var dismiss
  @State private var refreshing = false
  var body: some View {
    let entry = git.entry(context.client,context.target,context.project.id)
    let snapshot = entry?.snapshot
    NavigationStack {
      Group {
        if let snapshot {
          if snapshot.items.count == 1, let item = snapshot.items.first { worktree(snapshot,item,entry?.failed == true) }
          else {
            List {
              source(snapshot,entry?.failed == true)
              if snapshot.repositories.isEmpty { Text(snapshot.kind == "non-git" ? L10n.tr("当前项目没有 Git 仓库") : L10n.tr("仓库扫描未完成")) }
              ForEach(snapshot.repositories) { repo in
                Section(repo.name) {
                  ForEach(repo.worktrees) { tree in
                    NavigationLink { worktree(snapshot,.init(repository:repo,tree:tree),entry?.failed == true) } label: {
                      VStack(alignment:.leading,spacing:5) {
                        Text(tree.displayPath).font(.subheadline).lineLimit(2)
                        Text(tree.branch ?? (tree.unborn ? L10n.tr("尚无提交") : L10n.tr("分离 HEAD"))).font(.caption).foregroundStyle(.secondary)
                        if let changes = tree.changes { Text(L10n.tr("未提交文件") + " \(changes.changed) · " + L10n.tr("未推送提交") + " " + (tree.publish.ahead.map(String.init) ?? "—")).font(.caption) }
                        else { Text(projectGitReason(tree.reason)).font(.caption).foregroundStyle(.secondary) }
                      }
                    }.accessibilityIdentifier("git-worktree-\(tree.id)")
                  }
                }
              }
            }
          }
        } else {
          ContentUnavailableView(L10n.tr(entry?.failed == true ? "Git 状态暂不可用" : "正在检查仓库"),systemImage:"point.3.connected.trianglepath.dotted",description:Text(context.project.name))
        }
      }
      .navigationTitle(L10n.tr("Git 仓库状态")).navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement:.cancellationAction) { Button(L10n.tr("完成")) { dismiss() } }
        ToolbarItem(placement:.primaryAction) { Button { Task { await refresh() } } label: { Image(systemName:"arrow.clockwise") }.disabled(refreshing || !context.client.online).accessibilityLabel(L10n.tr("刷新仓库状态")).accessibilityIdentifier("git-refresh") }
      }
      .task(id:context.id) { await refresh() }
    }.accessibilityIdentifier("project-git-details")
  }
  private func refresh() async {
    guard !refreshing else { return };refreshing=true
    await git.refresh(context.client,context.target,context.project.id,force:true);refreshing=false
  }
  @ViewBuilder private func source(_ snapshot: ProjectGitSnapshot, _ failed: Bool) -> some View {
    Section {
      Text(context.client.name + " · " + context.project.name).font(.subheadline)
      if failed || !context.client.online { Label(L10n.tr("离线或更新失败，显示上次读取"),systemImage:"clock").font(.caption).foregroundStyle(.orange) }
      Text(L10n.tr("读取时间") + " " + snapshot.observedAt).font(.caption).foregroundStyle(.secondary)
      Text(L10n.tr("远端依据本地记录，尚未核实最新状态")).font(.caption).foregroundStyle(.secondary)
      if !snapshot.complete { Label(L10n.tr("部分仓库状态未完成"),systemImage:"exclamationmark.circle").font(.caption).foregroundStyle(.orange) }
      ForEach(Array(snapshot.issues.enumerated()),id:\.offset) { _,issue in Text(projectGitReason(issue.reason)).font(.caption).foregroundStyle(.secondary) }
    }
  }
  private func worktree(_ snapshot:ProjectGitSnapshot,_ item:ProjectGitSnapshot.Item,_ failed:Bool) -> some View {
    List {
      source(snapshot,failed)
      Section(item.repository.name) {
        Text(item.tree.displayPath).font(.caption).textSelection(.enabled)
        Text(item.tree.branch ?? (item.tree.unborn ? L10n.tr("尚无提交") : L10n.tr("分离 HEAD") + " " + (item.tree.head.map { String($0.prefix(8)) } ?? ""))).font(.subheadline)
        if let operation = item.tree.operation { Label(operation,systemImage:"exclamationmark.triangle").foregroundStyle(.orange) }
      }
      if let changes = item.tree.changes {
        Section(L10n.tr("工作区")) {
          LabeledContent(L10n.tr("未提交文件"),value:String(changes.changed));LabeledContent(L10n.tr("已暂存"),value:String(changes.staged))
          LabeledContent(L10n.tr("未暂存"),value:String(changes.unstaged));LabeledContent(L10n.tr("未跟踪"),value:String(changes.untracked))
          LabeledContent(L10n.tr("冲突文件"),value:String(changes.conflicts))
          Text(L10n.tr("同一文件可以同时已暂存与未暂存，未提交按文件去重。")).font(.caption).foregroundStyle(.secondary)
          NavigationLink(L10n.tr("查看文件状态")) { ProjectGitFilesView(context:context,snapshotId:snapshot.snapshotId,tree:item.tree) }.accessibilityIdentifier("git-files")
        }
        comparison(L10n.tr("与当前上游比较"),item.tree.upstream)
        comparison(L10n.tr("与推送目标比较"),item.tree.publish)
        comparison(L10n.tr("与项目主线比较"),item.tree.baseline)
      } else { Section { Text(projectGitReason(item.tree.reason)) } }
    }.navigationTitle(item.repository.name).navigationBarTitleDisplayMode(.inline)
  }
  private func comparison(_ label:String,_ value:ProjectGitComparison) -> some View {
    Section(label) {
      if let ref = value.ref { Text(ref).font(.caption).textSelection(.enabled) }
      if value.state == "ready" {
        LabeledContent(L10n.tr("领先"),value:value.ahead.map(String.init) ?? "—")
        LabeledContent(L10n.tr("落后"),value:value.behind.map(String.init) ?? "—")
      } else { Text(projectGitReason(value.reason)).foregroundStyle(.secondary) }
    }
  }
}

private struct ProjectGitFilesView: View {
  let context:ProjectGitContext; let snapshotId:String; let tree:ProjectGitWorktree
  @State private var files:[ProjectGitFile]=[]; @State private var cursor:String?; @State private var loaded=false
  @State private var busy=false; @State private var error:String?
  var body:some View {
    List {
      if let error { Text(error).foregroundStyle(.secondary).accessibilityIdentifier("git-files-error") }
      ForEach(files) { file in
        VStack(alignment:.leading,spacing:4) {
          Text(file.path).font(.system(.subheadline,design:.monospaced)).textSelection(.enabled)
          if let old=file.oldPath { Text(old + " → " + file.path).font(.caption).foregroundStyle(.secondary) }
          Text([file.staged ? L10n.tr("已暂存") : nil,file.unstaged ? L10n.tr("未暂存") : nil,file.untracked ? L10n.tr("未跟踪") : nil,file.conflict ? L10n.tr("冲突文件") : nil,file.submodule ? L10n.tr("子模块") : nil].compactMap{$0}.joined(separator:" · ")).font(.caption).foregroundStyle(.secondary)
        }
      }
      if !loaded || cursor != nil || error != nil { Button(L10n.tr(error != nil ? "重试" : "加载更多")) { Task { await load() } }.disabled(busy) }
      if loaded && files.isEmpty && error == nil { Text(L10n.tr("工作区干净")) }
    }.navigationTitle(L10n.tr("文件状态")).task { if !loaded { await load() } }
  }
  private func load() async {
    guard !busy else{return};busy=true;defer{busy=false}
    do {
      var bound=context.client;bound.readingTarget=context.target
      let bytes=try await AgentClients.resolve(bound).projectGitFiles(bound,projectId:context.project.id,target:context.target,snapshotId:snapshotId,worktreeId:tree.id,cursor:cursor)
      guard bytes.count<=131072,let raw=String(data:bytes,encoding:.utf8) else{throw HostServiceError.invalidResponse}
      let validated=try ProjectGitRules.shared.files(json:raw,targetJson:projectGitTargetJSON(context.target),projectId:context.project.id,snapshotId:snapshotId,worktreeId:tree.id)
      let page=try JSONDecoder().decode(ProjectGitFilePage.self,from:Data(validated.utf8))
      var seen=Set(files.map(\.id));files += page.files.filter { seen.insert($0.id).inserted };cursor=page.nextCursor;loaded=true;error=nil
    } catch { self.error=L10n.tr("文件状态读取失败；快照可能已变化，请返回刷新仓库状态。") }
  }
}

extension SessionStore {
  var supportsProjectGit: Bool { client.map { $0.agentConnection?.target.agentId == "easywork" && saved.workspaces[$0.id]?.capabilities?.contains("project-git.status.v1") == true } ?? false }
  var conversationGitProject: Project? {
    guard let conversation, let projectId=conversation.projectId else{return nil}
    return projects.first { $0.id==projectId && $0.hostId==conversation.hostId }
  }
  var currentProjectGitContext: ProjectGitContext? {
    guard let client=projectGitClient,let project=conversationGitProject else{return nil}
    return ProjectGitContext(client:client,target:mobileReadingTarget(client),project:project,sourceKey:targetKey)
  }
  func openProjectGit(_ context: ProjectGitContext) {
    guard supportsProjectGit, context.sourceKey==targetKey, conversationGitProject?.id==context.project.id,
      let client, mobileReadingTarget(client)==context.target else{return}
    projectGitContext=context
    sheet = .projectGit
  }
}

struct ProjectGitMenuEntry: View {
  @ObservedObject var store:SessionStore
  @ObservedObject private var git = ProjectGitStore.shared
  var body:some View {
    if let context=store.currentProjectGitContext {
      let entry=git.entry(context.client,context.target,context.project.id)
      let value=entry?.snapshot
      if value?.kind != "non-git" {
        Button(L10n.tr("Git 仓库状态"),systemImage:"arrow.triangle.branch") { store.openProjectGit(context) }
          .disabled(!store.supportsProjectGit || (value == nil && entry?.failed != true))
          .accessibilityIdentifier("chat-project-git")
          .accessibilityHint(!store.supportsProjectGit ? L10n.tr("此电脑暂未提供仓库状态") : entry?.failed == true ? L10n.tr("Git 状态暂不可用") : value == nil ? L10n.tr("正在检查仓库") : context.project.name)
      }
    } else {
      Button(L10n.tr("Git 仓库状态"),systemImage:"arrow.triangle.branch") {}.disabled(true)
        .accessibilityHint(L10n.tr("当前对话未关联项目")).accessibilityIdentifier("chat-project-git-unavailable")
    }
  }
}
