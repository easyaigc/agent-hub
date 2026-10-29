import SwiftUI
import MobileCore

struct UsageMetricViewData: Decodable, Identifiable {
  let id: String; let label: String; let bucket: String?; let kind: String; let state: String
  let value: String; let unit: String; let validUntil: String; let observedAt: String
  let resetAt: String?; let expiresAt: String?; let percent: Double?
  let entitlement: String?; let note: String?
  struct Applies: Decodable { let kind: String; let ids: [String]? }
  struct Combination: Decodable { let kind: String }
  let appliesTo: Applies; let combination: Combination
}
struct UsageViewData: Decodable {
  let status: String; let reason: String?; let partial: Bool?; let stale: Bool?
  let snapshotId: String?; let sampledAt: String?; let metrics: [UsageMetricViewData]?
  let ringLabel: String?; let percent: Double?; let balance: String?; let balanceUnit: String?; let extraBalances: Int?
  static let unknown = UsageViewData(status: "unavailable", reason: "not-returned", partial: nil, stale: nil, snapshotId: nil, sampledAt: nil, metrics: nil, ringLabel: nil, percent: nil, balance: nil, balanceUnit: nil, extraBalances: nil)
}
func usageTargetJSON(_ target: AgentRuntimeTarget) throws -> String {
  String(decoding: try JSONSerialization.data(withJSONObject: ["agentId": target.agentId, "hostId": target.hostId,
    "workspaceId": target.workspaceId, "runtimeId": target.runtimeId], options: [.sortedKeys]), as: UTF8.self)
}
@MainActor final class UsageStore: ObservableObject {
  static let shared = UsageStore()
  struct Entry { var raw: String; var value: UsageViewData; var failed: Bool; var attempted: Date }
  @Published private var entries: [String: Entry] = [:]
  private var pending: [String: Task<Void, Never>] = [:]
  private func key(_ client: Client, _ target: AgentRuntimeTarget) -> String {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let connection = (try? encoder.encode(client.agentConnection)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    let direct = (try? encoder.encode(client.codexDirect)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    return [client.id, client.endpoint ?? "", (try? usageTargetJSON(target)) ?? "", connection, direct].joined(separator: "\n")
  }
  func identity(_ client: Client, target: AgentRuntimeTarget) -> String { key(client, target) }
  func value(_ client: Client, target: AgentRuntimeTarget) -> UsageViewData {
    guard let entry = entries[key(client, target)] else { return .unknown }
    guard let json = try? UsageRules.shared.project(json: entry.raw, targetJson: usageTargetJSON(target), nowMillis: Int64(Date().timeIntervalSince1970 * 1000)), let value = try? JSONDecoder().decode(UsageViewData.self, from: Data(json.utf8)) else { return entry.value }
    return value
  }
  func cached(_ client: Client, target: AgentRuntimeTarget) -> Bool {
    guard let entry = entries[key(client, target)], entry.value.status == "ok" else { return false }
    return entry.failed || value(client, target: target).stale == true
  }
  func refresh(_ client: Client, target: AgentRuntimeTarget, force: Bool = false) async {
    guard !client.isDemo else { return }
    let key = key(client, target)
    if let task = pending[key] { await task.value; return }
    guard force || Date().timeIntervalSince(entries[key]?.attempted ?? .distantPast) >= 5 else { return }
    // The bounded read belongs to the cache. Moving between two visible consumers does not cancel it.
    let task = Task { await self.read(client, target: target, key: key); self.pending[key] = nil }
    pending[key] = task
    await task.value
  }
  private func read(_ client: Client, target: AgentRuntimeTarget, key: String) async {
    do {
      var bound = client; bound.readingTarget = target
      let data = try await AgentClients.resolve(bound).usage(bound, target: target)
      guard data.count <= 262144, let raw = String(data: data, encoding: .utf8) else { throw HostServiceError.invalidResponse }
      let json = try UsageRules.shared.project(json: raw, targetJson: usageTargetJSON(target), nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
      let value = try JSONDecoder().decode(UsageViewData.self, from: Data(json.utf8))
      // A source failure may retain a clearly marked previous sample. It never becomes a live zero.
      if value.status == "unavailable", ["timeout", "offline", "upstream-error"].contains(value.reason ?? ""), var previous = entries[key], previous.value.status == "ok" {
        previous.failed = true; previous.attempted = Date(); entries[key] = previous
      } else { entries[key] = Entry(raw: raw, value: value, failed: false, attempted: Date()) }
    } catch {
      if Task.isCancelled { return }
      if var previous = entries[key] { previous.failed = true; previous.attempted = Date(); entries[key] = previous }
      else { entries[key] = Entry(raw: "", value: .unknown, failed: true, attempted: Date()) }
    }
    // Cache is memory-only and bounded; connection changes never reuse another endpoint's sample.
    if entries.count > 64, let oldest = entries.min(by: { $0.value.attempted < $1.value.attempted })?.key { entries.removeValue(forKey: oldest) }
  }
  func poll(_ client: Client, target: AgentRuntimeTarget) async {
    while !Task.isCancelled {
      await refresh(client, target: target)
      do { try await Task.sleep(for: .seconds(30)) } catch { return }
    }
  }
}
private func usageValue(_ metric: UsageMetricViewData) -> String {
  switch metric.state {
  case "unlimited": return L10n.tr("无限额")
  case "unavailable": return L10n.tr("暂不可用")
  default: return metric.value + (metric.unit.isEmpty ? "" : " " + metric.unit)
  }
}
struct UsageRing: View {
  let percent: Double?
  var body: some View {
    ZStack {
      Circle().stroke(Color.secondary.opacity(0.15), lineWidth: 3)
      if let percent { Circle().trim(from: 0, to: min(1, max(0, percent / 100))).stroke(Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90)) }
      Text(percent.map { "\(Int(min($0, 9999).rounded()))%" } ?? "—").font(.system(size: 10, weight: .medium)).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
    }.frame(width: 34, height: 34).accessibilityHidden(true)
  }
}
struct ClientUsageSummary: View {
  @Environment(\.scenePhase) private var scenePhase
  let client: Client; let target: AgentRuntimeTarget
  @ObservedObject private var usage = UsageStore.shared
  var body: some View {
    let data = usage.value(client, target: target)
    VStack(alignment: .leading, spacing: 2) {
      if let balance = data.balance { Text(balance + " " + (data.balanceUnit ?? "") + ((data.extraBalances ?? 0) > 0 ? " +\(data.extraBalances!)" : "")) }
      let limits = (data.metrics ?? []).filter { $0.kind == "window-limit" || $0.kind == "capacity-limit" }
      if !limits.isEmpty { Text(limits.map { $0.label + " " + usageValue($0) }.joined(separator: " · ")) }
      if data.status != "ok" { Text(L10n.tr("暂未提供额度")) }
      if usage.cached(client, target: target) { Text(L10n.tr("缓存额度")) }
      else if data.partial == true { Text(L10n.tr("部分额度暂不可用")) }
    }.font(.caption2).foregroundStyle(.secondary).lineLimit(2)
      .task(id: "\(client):\(target):\(scenePhase)") { if scenePhase == .active { await usage.poll(client, target: target) } }
  }
}
struct UsageFooterButton: View {
  let client: Client; let target: AgentRuntimeTarget; let active: Bool; let action: () -> Void
  @ObservedObject private var usage = UsageStore.shared
  @Environment(\.scenePhase) private var scenePhase
  var body: some View {
    let data = usage.value(client, target: target)
    let cached = usage.cached(client, target: target)
    Button(action: action) {
      HStack(spacing: 8) {
        UsageRing(percent: data.percent)
        if let balance = data.balance {
          VStack(alignment: .trailing, spacing: 1) {
            Text(balance).font(.system(size: 12, weight: .medium)).monospacedDigit()
            Text((data.balanceUnit ?? "") + ((data.extraBalances ?? 0) > 0 ? " +\(data.extraBalances!)" : "") + (cached ? " · " + L10n.tr("缓存") : ""))
              .font(.system(size: 10)).foregroundStyle(.secondary)
          }.lineLimit(1).minimumScaleFactor(0.7)
        } else if cached { Text(L10n.tr("缓存")).font(.caption2).foregroundStyle(.secondary) }
      }.frame(minHeight: 44).contentShape(Rectangle())
    }.accessibilityIdentifier("current-client-usage")
      .accessibilityLabel(([L10n.tr("当前客户端额度")] + [data.percent.map { (data.ringLabel ?? "") + " \(Int(min(9999, $0).rounded()))%" }, data.balance.map { $0 + " " + (data.balanceUnit ?? "") }, cached ? L10n.tr("缓存额度") : nil, data.percent == nil && data.balance == nil ? L10n.tr("暂不可用") : nil].compactMap { $0 }).joined(separator: "，"))
      .task(id: "\(usage.identity(client, target: target)):\(scenePhase):\(active)") { if active && scenePhase == .active { await usage.poll(client, target: target) } }
  }
}
struct UsageDetailsView: View {
  @Environment(\.scenePhase) private var scenePhase
  let client: Client; let target: AgentRuntimeTarget
  @ObservedObject private var usage = UsageStore.shared
  @Environment(\.dismiss) private var dismiss
  var body: some View {
    let data = usage.value(client, target: target)
    NavigationStack {
      List {
        Section {
          Text(client.name)
          if usage.cached(client, target: target) { Label(L10n.tr("缓存额度"), systemImage: "clock") }
          if data.partial == true { Text(L10n.tr("部分额度暂不可用")) }
          if let sampled = data.sampledAt { Text(L10n.tr("更新时间") + "：" + sampled).font(.caption).foregroundStyle(.secondary) }
        }
        ForEach(data.metrics ?? []) { metric in
          Section(metric.bucket ?? metric.label) {
            LabeledContent(metric.label, value: usageValue(metric)).textSelection(.enabled)
            if metric.kind == "window-limit" || metric.kind == "capacity-limit" { Text(L10n.tr("百分比为已用用量，各窗口单独计算")).font(.caption).foregroundStyle(.secondary) }
            Text(L10n.tr("适用范围") + "：" + (metric.appliesTo.kind == "all" ? L10n.tr("所有模型和功能") : (metric.appliesTo.ids ?? []).joined(separator: " · "))).font(.caption)
            Text(L10n.tr(metric.combination.kind == "all-of" ? "全部限制同时生效" : metric.combination.kind == "any-of" ? "任一限制可用" : "按服务端规则")).font(.caption).foregroundStyle(.secondary)
            Text(L10n.tr("观察时间") + "：" + metric.observedAt).font(.caption).foregroundStyle(.secondary)
            if let note = metric.note { Text(note).font(.caption) }
            if let reset = metric.resetAt { Text(L10n.tr("重置时间") + "：" + reset).font(.caption) }
            if let expiry = metric.expiresAt { Text(L10n.tr("到期时间") + "：" + expiry).font(.caption) }
            if metric.entitlement == "unlimited" { Text(L10n.tr("无限额")) }
          }
        }
        if data.status != "ok" { Text(L10n.tr("暂未提供额度")); if let reason = data.reason { Text(reason).font(.caption).foregroundStyle(.secondary) } }
      }.navigationTitle(L10n.tr("当前客户端额度"))
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L10n.tr("完成")) { dismiss() } } }
        .refreshable { await usage.refresh(client, target: target, force: true) }
        .task(id: scenePhase) { if scenePhase == .active { await usage.poll(client, target: target) } }
    }.presentationDetents([.medium, .large])
  }
}
