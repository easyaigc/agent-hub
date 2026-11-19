import SwiftUI
import MobileCore

struct UsageMetricViewData: Decodable, Identifiable {
  let id: String; let label: String; let bucket: String?; let kind: String; let state: String
  let value: String; let unit: String; let validUntil: String; let observedAt: String
  let resetAt: String?; let expiresAt: String?; let percent: Double?
  let remainingPercent: Double?; let remainingValue: String?
  let entitlement: String?; let note: String?
  struct Applies: Decodable { let kind: String; let ids: [String]? }
  struct Combination: Decodable { let kind: String }
  let appliesTo: Applies; let combination: Combination
}
struct UsageViewData: Decodable {
  let status: String; let reason: String?; let partial: Bool?; let stale: Bool?
  let snapshotId: String?; let sampledAt: String?; let metrics: [UsageMetricViewData]?
  let ringMetricId: String?
  let ringLabel: String?; let percent: Double?; let balance: String?; let balanceUnit: String?; let extraBalances: Int?
  static let unknown = UsageViewData(status: "unavailable", reason: "not-returned", partial: nil, stale: nil, snapshotId: nil, sampledAt: nil, metrics: nil, ringMetricId: nil, ringLabel: nil, percent: nil, balance: nil, balanceUnit: nil, extraBalances: nil)
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
  #if DEBUG && targetEnvironment(simulator)
  private static let fixtureReset = ISO8601DateFormatter().string(from: Date().addingTimeInterval(3661))
  #endif
  func value(_ client: Client, target: AgentRuntimeTarget) -> UsageViewData {
    #if DEBUG && targetEnvironment(simulator)
    if client.isDemo, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-usage-details-fixture") {
      let window = UsageMetricViewData(id: "fixture-window", label: "7d", bucket: "7d", kind: "window-limit",
        state: "percent", value: "65", unit: "%", validUntil: Self.fixtureReset, observedAt: "fixture",
        resetAt: Self.fixtureReset, expiresAt: nil, percent: 65, remainingPercent: 35, remainingValue: nil,
        entitlement: nil, note: nil, appliesTo: .init(kind: "all", ids: nil), combination: .init(kind: "all-of"))
      return UsageViewData(status: "ok", reason: nil, partial: false, stale: false, snapshotId: "fixture",
        sampledAt: "fixture", metrics: [window], ringMetricId: window.id, ringLabel: "7d", percent: 35,
        balance: nil, balanceUnit: nil, extraBalances: nil)
    }
    #endif
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
enum UsagePresentation {
  static func remaining(_ metric: UsageMetricViewData) -> String? {
    if metric.state == "unlimited" { return L10n.tr("无限额") }
    if metric.state == "amount", metric.kind == "balance" { return usageValue(metric) }
    if let value = metric.remainingValue { return value + (metric.unit.isEmpty ? "" : " " + metric.unit) }
    guard let percent = metric.remainingPercent, percent.isFinite else { return nil }
    if percent > 0 && percent < 0.01 { return "<0.01%" }
    let formatter = NumberFormatter(); formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = 2
    return (formatter.string(from: NSNumber(value: percent)) ?? "—") + "%"
  }
  static func countdown(_ raw: String, now: Date, compact: Bool = false) -> String {
    guard let seconds = UsageRules.shared.resetSeconds(resetAt: raw, nowMillis: Int64(now.timeIntervalSince1970 * 1000))?.int64Value else { return L10n.tr("暂不可用") }
    guard seconds > 0 else { return L10n.tr("已到重置时间，等待更新") }
    let parts = [(seconds / 86400, L10n.tr("天")), (seconds % 86400 / 3600, L10n.tr("小时")), (seconds % 3600 / 60, L10n.tr("分钟")), (seconds % 60, L10n.tr("秒"))]
    return parts.filter { $0.0 > 0 }.prefix(compact ? 2 : 4).map { "\($0.0) " + $0.1 }.joined(separator: " ")
  }
  static func resetSummary(_ metric: UsageMetricViewData, now: Date) -> String {
    guard let reset = metric.resetAt else { return L10n.tr("重置时间暂不可用") }
    let value = countdown(reset, now: now, compact: true)
    if value == L10n.tr("已到重置时间，等待更新") || value == L10n.tr("暂不可用") { return value }
    return L10n.tr("\(value)后重置")
  }
  static func windowSummary(_ metric: UsageMetricViewData, now: Date) -> String {
    resetSummary(metric, now: now) + " · " + L10n.tr("剩余\(remaining(metric) ?? L10n.tr("暂不可用"))")
  }
}
struct UsageRing: View {
  let percent: Double?
  var body: some View {
    ZStack {
      Circle().stroke(Color.secondary.opacity(0.15), lineWidth: 3)
      if let percent { Circle().trim(from: 0, to: min(1, max(0, percent / 100))).stroke(Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90)) }
      Text(percent.map { "\(Int(min(100, max(0, $0)).rounded(.down)))%" } ?? "—").font(.system(size: 10, weight: .medium)).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
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
      let limits = (data.metrics ?? []).filter { $0.kind == "window-limit" && $0.state != "unlimited" }
      if !limits.isEmpty {
        TimelineView(.periodic(from: .now, by: 1)) { context in
          VStack(alignment: .leading, spacing: 2) {
            ForEach(limits) { metric in
              Text((limits.count > 1 ? metric.label + " · " : "") + UsagePresentation.windowSummary(metric, now: context.date))
            }
          }.accessibilityIdentifier("client-usage-windows-" + client.id)
        }
      }
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
    let window = (data.metrics ?? []).first { $0.id == data.ringMetricId && $0.kind == "window-limit" }
    Button(action: action) {
      HStack(spacing: 8) {
        if window != nil, let percent = data.percent {
          UsageRing(percent: percent).accessibilityIdentifier("usage-remaining-ring")
        }
        if let balance = data.balance {
          VStack(alignment: .trailing, spacing: 1) {
            Text(balance).font(.system(size: 12, weight: .medium)).monospacedDigit()
            Text((data.balanceUnit ?? "") + ((data.extraBalances ?? 0) > 0 ? " +\(data.extraBalances!)" : "") + (cached ? " · " + L10n.tr("缓存") : ""))
              .font(.system(size: 10)).foregroundStyle(.secondary)
          }.lineLimit(1).minimumScaleFactor(0.7)
        } else if cached { Text(L10n.tr("缓存")).font(.caption2).foregroundStyle(.secondary) }
        else if window == nil { Text(L10n.tr("当前客户端额度")).font(.caption2).foregroundStyle(.secondary) }
      }.frame(minHeight: 44).contentShape(Rectangle())
    }.accessibilityIdentifier("current-client-usage")
      .accessibilityLabel(([L10n.tr("当前客户端额度")] + [window.flatMap { UsagePresentation.remaining($0) }.map { L10n.tr("剩余\($0)") }, data.balance.map { $0 + " " + (data.balanceUnit ?? "") }, cached ? L10n.tr("缓存额度") : nil, window == nil && data.balance == nil ? L10n.tr("暂不可用") : nil].compactMap { $0 }).joined(separator: "，"))
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
    let metrics = data.metrics ?? []
    let limitCount = metrics.filter { $0.kind == "window-limit" || $0.kind == "capacity-limit" }.count
    let balanceCount = metrics.filter { $0.kind == "balance" }.count
    NavigationStack {
      List {
        if usage.cached(client, target: target) || data.partial == true {
          Section {
            if usage.cached(client, target: target) { Label(L10n.tr("缓存额度"), systemImage: "clock") }
            if data.partial == true { Text(L10n.tr("部分额度暂不可用")) }
          }
        }
        ForEach(metrics) { metric in
          Section {
            if metric.kind != "extension", (metric.kind == "balance" ? balanceCount : limitCount) > 1 {
              Text(metric.label).font(.caption).foregroundStyle(.secondary)
            }
            if metric.kind == "window-limit" {
              if let reset = metric.resetAt {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                  LabeledContent(L10n.tr("距离下次重置"), value: UsagePresentation.countdown(reset, now: context.date))
                    .monospacedDigit().accessibilityIdentifier("usage-reset-countdown-" + metric.id)
                }
              } else { Text(L10n.tr("重置时间暂不可用")).font(.caption).foregroundStyle(.secondary) }
            }
            if metric.kind == "extension" { LabeledContent(metric.label, value: usageValue(metric)).textSelection(.enabled) }
            else {
              LabeledContent(L10n.tr("剩余额度"), value: UsagePresentation.remaining(metric) ?? L10n.tr("暂不可用"))
                .textSelection(.enabled).accessibilityIdentifier("usage-remaining-" + metric.id)
            }
          }
        }
        if data.status != "ok" { Text(L10n.tr("暂未提供额度")) }
      }.accessibilityIdentifier("usage-details-content")
        .navigationTitle(L10n.tr("当前客户端额度"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L10n.tr("完成")) { dismiss() } } }
        .refreshable { await usage.refresh(client, target: target, force: true) }
        .task(id: scenePhase) { if scenePhase == .active { await usage.poll(client, target: target) } }
    }.presentationDetents([.medium, .large])
  }
}
