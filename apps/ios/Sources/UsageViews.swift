import SwiftUI
import MobileCore

struct UsageMetricViewData: Codable, Identifiable {
  let id: String; let label: String; let bucket: String?; let kind: String; let state: String
  let value: String; let unit: String; let validUntil: String; let observedAt: String
  let resetAt: String?; let expiresAt: String?; let percent: Double?
  let remainingPercent: Double?; let remainingValue: String?
  let entitlement: String?; let note: String?; let debt: Bool?
  struct Applies: Codable { let kind: String; let ids: [String]? }
  struct Combination: Codable { let kind: String }
  let appliesTo: Applies; let combination: Combination
}
struct UsageViewData: Codable {
  struct Source: Codable { let adapterId: String; let scopeRef: String?; let scopeLabel: String?; let planLabel: String? }
  let source: Source?; let balanceDebt: Bool?; let cached: Bool?; let failureReason: String?
  let status: String; let reason: String?; let partial: Bool?; let stale: Bool?
  let snapshotId: String?; let sampledAt: String?; let metrics: [UsageMetricViewData]?
  let ringMetricId: String?
  let ringLabel: String?; let percent: Double?; let balance: String?; let balanceUnit: String?; let extraBalances: Int?
  static let unknown = UsageViewData(source: nil, balanceDebt: nil, cached: nil, failureReason: nil, status: "unavailable", reason: "not-returned", partial: nil, stale: nil, snapshotId: nil, sampledAt: nil, metrics: nil, ringMetricId: nil, ringLabel: nil, percent: nil, balance: nil, balanceUnit: nil, extraBalances: nil)
}
func usageTargetJSON(_ target: AgentRuntimeTarget) throws -> String {
  String(decoding: try JSONSerialization.data(withJSONObject: ["agentId": target.agentId, "hostId": target.hostId,
    "workspaceId": target.workspaceId, "runtimeId": target.runtimeId], options: [.sortedKeys]), as: UTF8.self)
}
/// Cancellation of a hidden waiter is visible before a queued shared read may start.
private final class UsageReadConsumers: @unchecked Sendable {
  private let lock = NSLock()
  private var tokens: Set<UUID> = []
  func add(_ id: UUID) { lock.lock(); defer { lock.unlock() }; tokens.insert(id) }
  @discardableResult func remove(_ id: UUID) -> Bool { lock.lock(); defer { lock.unlock() }; tokens.remove(id); return tokens.isEmpty }
  var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return tokens.isEmpty }
}

@MainActor private final class UsageReadGate {
  static let shared = UsageReadGate()
  private var active: Set<UUID> = []
  private var waiting: [(UUID, CheckedContinuation<Void, Error>)] = []
  func acquire() async throws -> UUID {
    let id = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
        else if active.count < 2 { active.insert(id); continuation.resume() }
        else { waiting.append((id, continuation)) }
      }
      do { try Task.checkCancellation() } catch { release(id); throw error }
    } onCancel: { Task { @MainActor in self.cancelWaiting(id) } }
    return id
  }
  private func cancelWaiting(_ id: UUID) {
    if let index = waiting.firstIndex(where: { $0.0 == id }) { waiting.remove(at: index).1.resume(throwing: CancellationError()) }
  }
  func release(_ id: UUID) {
    guard active.remove(id) != nil else { return }
    if !waiting.isEmpty { let (next, continuation) = waiting.removeFirst(); active.insert(next); continuation.resume() }
  }
}
func usageFailureReason(_ error: Error) -> String {
  if case HostServiceError.rejected(let status, _) = error { return UsageReadRules.shared.httpFailure(status: Int32(status)) }
  if let rejection = error as? EasyWorkRemoteRejection { return UsageReadRules.shared.remoteFailure(code: rejection.code ?? "") }
  if case HostServiceError.wrongHost = error { return "wrong-target" }
  if case HostServiceError.invalidResponse = error { return "invalid-data" }
  if case HostServiceError.hostKeyRequired = error { return "http-unauthenticated" }
  if let url = error as? URLError {
    switch url.code {
    case .timedOut: return "timeout"
    case .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed: return "offline"
    case .userAuthenticationRequired: return "http-unauthenticated"
    default: return "connection-unverified"
    }
  }
  return "read-failed"
}
@MainActor final class UsageStore: ObservableObject {
  static let shared = UsageStore()
  struct Entry { var raw: String?; var value: UsageViewData }
  private final class Flight {
    let id = UUID(); let consumers = UsageReadConsumers()
    var task: Task<Void, Never>!
  }
  @Published private var entries: [String: Entry] = [:]
  private var attempts: [String: Date] = [:]
  @Published private var pending: [String: Flight] = [:]
  private let now: () -> Date
  private let readUsage: (Client, AgentRuntimeTarget) async throws -> Data
  init(now: @escaping () -> Date = Date.init, read: @escaping (Client, AgentRuntimeTarget) async throws -> Data = { client, target in
    var bound = client; bound.readingTarget = target
    return try await AgentClients.resolve(bound).usage(bound, target: target)
  }) { self.now = now; self.readUsage = read }
  private func key(_ client: Client, _ target: AgentRuntimeTarget) -> String {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let connection = (try? encoder.encode(client.agentConnection)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    // Normalize only quota identity; leave the saved connection and command payload untouched.
    var usageDirect = client.codexDirect
    if usageDirect?.target.agentId == "codex" { usageDirect?.target.account = nil }
    let direct = (try? encoder.encode(usageDirect)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    return [client.id, client.endpoint ?? "", (try? usageTargetJSON(target)) ?? "", connection, direct].joined(separator: "\n")
  }
  func identity(_ client: Client, target: AgentRuntimeTarget) -> String { key(client, target) }
  #if DEBUG && targetEnvironment(simulator)
  private static let fixtureReset = ISO8601DateFormatter().string(from: Date().addingTimeInterval(3661))
  #endif
  private func project(_ raw: String, target: AgentRuntimeTarget) -> UsageViewData {
    guard let json = try? UsageReadRules.shared.projectResponse(json: raw, targetJson: usageTargetJSON(target), nowMillis: Int64(now().timeIntervalSince1970 * 1000)),
      let value = try? JSONDecoder().decode(UsageViewData.self, from: Data(json.utf8)) else { return .unknown }
    return value
  }
  func value(_ client: Client, target: AgentRuntimeTarget) -> UsageViewData {
    #if DEBUG && targetEnvironment(simulator)
    if client.isDemo, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-usage-details-fixture") {
      let window = UsageMetricViewData(id: "fixture-window", label: "7d", bucket: "7d", kind: "window-limit",
        state: "percent", value: "65", unit: "%", validUntil: Self.fixtureReset, observedAt: "fixture",
        resetAt: Self.fixtureReset, expiresAt: nil, percent: 65, remainingPercent: 35, remainingValue: nil,
        entitlement: nil, note: nil, debt: nil, appliesTo: .init(kind: "all", ids: nil), combination: .init(kind: "all-of"))
      return UsageViewData(source: nil, balanceDebt: nil, cached: nil, failureReason: nil, status: "ok", reason: nil, partial: false, stale: false, snapshotId: "fixture",
        sampledAt: "fixture", metrics: [window], ringMetricId: window.id, ringLabel: "7d", percent: 35,
        balance: nil, balanceUnit: nil, extraBalances: nil)
    }
    #endif
    guard let entry = entries[key(client, target)] else { return .unknown }
    return entry.raw.map { project($0, target: target) } ?? entry.value
  }
  func cached(_ client: Client, target: AgentRuntimeTarget) -> Bool {
    let value = value(client, target: target)
    return value.status == "ok" && (value.cached == true || value.stale == true)
  }
  // A queued/shared read is visible before its task can acquire a transport slot.
  func refreshing(_ client: Client, target: AgentRuntimeTarget) -> Bool {
    guard let flight = pending[key(client, target)] else { return false }
    return flight.task?.isCancelled != true
  }
  func refresh(_ client: Client, target: AgentRuntimeTarget, force: Bool = false) async {
    guard !client.isDemo, !Task.isCancelled else { return }
    let key = key(client, target)
    let flight: Flight
    if let existing = pending[key], !existing.task.isCancelled { flight = existing }
    else {
      // User refresh and polling obey the same minimum; force never creates a second read.
      let attempted = attempts[key].map { Int64($0.timeIntervalSince1970 * 1000) } ?? Int64.min
      guard UsageReadRules.shared.mayRefresh(nowMillis: Int64(now().timeIntervalSince1970 * 1000), attemptedMillis: attempted) else { return }
      flight = Flight()
      pending[key] = flight
      flight.task = Task {
        defer { if self.pending[key]?.id == flight.id { self.pending[key] = nil } }
        do {
          let permit = try await UsageReadGate.shared.acquire()
          defer { UsageReadGate.shared.release(permit) }
          try Task.checkCancellation()
          guard !flight.consumers.isEmpty, self.pending[key]?.id == flight.id else { return }
          self.attempts[key] = self.now()
          await self.read(client, target: target, key: key, flight: flight)
        } catch { /* A cancelled queued read never reaches the transport. */ }
      }
    }
    let token = UUID(); flight.consumers.add(token)
    let task = flight.task!
    let consumers = flight.consumers
    await withTaskCancellationHandler { await task.value } onCancel: {
      if consumers.remove(token) { task.cancel() }
    }
    flight.consumers.remove(token)
  }
  private func read(_ client: Client, target: AgentRuntimeTarget, key: String, flight: Flight) async {
    let raw: String?
    let value: UsageViewData
    do {
      let data = try await readUsage(client, target)
      try Task.checkCancellation()
      if data.count <= 262144, let text = String(data: data, encoding: .utf8) { raw = text; value = project(text, target: target) }
      else { raw = nil; value = (try? JSONDecoder().decode(UsageViewData.self, from: Data(UsageReadRules.shared.unavailable(reason: "invalid-data").utf8))) ?? .unknown }
    } catch {
      guard !Task.isCancelled, self.pending[key]?.id == flight.id else { return }
      applyFailure(usageFailureReason(error), key: key); return
    }
    guard !Task.isCancelled, self.pending[key]?.id == flight.id else { return }
    if value.status == "ok" { entries[key] = Entry(raw: raw, value: value) }
    else { applyFailure(value.reason ?? "invalid-data", key: key) }
    if entries.count > 64, let oldest = entries.keys.first(where: { $0 != key && pending[$0] == nil }) { entries[oldest] = nil; attempts[oldest] = nil }
  }
  private func applyFailure(_ reason: String, key: String) {
    let previous = entries[key].flatMap { try? JSONEncoder().encode($0.value) }.map { String(decoding: $0, as: UTF8.self) }
    // No failed wire supplies a current source proof; prior scopeRef alone is insufficient.
    let json = UsageReadRules.shared.failed(previousProjection: previous, reason: reason, confirmedSourceJson: nil)
    let value = (try? JSONDecoder().decode(UsageViewData.self, from: Data(json.utf8))) ?? .unknown
    entries[key] = Entry(raw: nil, value: value)
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
  default: return metric.value + (metric.unit.isEmpty ? "" : " " + metric.unit) + (metric.debt == true ? " · " + L10n.tr("欠额") : "")
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
  @State private var visible = false
  @Environment(\.scenePhase) private var scenePhase
  let client: Client; let target: AgentRuntimeTarget
  @ObservedObject private var usage = UsageStore.shared
  var body: some View {
    let data = usage.value(client, target: target)
    VStack(alignment: .leading, spacing: 2) {
      if let balance = data.balance { Text(balance + " " + (data.balanceUnit ?? "") + ((data.extraBalances ?? 0) > 0 ? " +\(data.extraBalances!)" : "") + (data.balanceDebt == true ? " · " + L10n.tr("欠额") : "")) }
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
      let capacities = (data.metrics ?? []).filter { $0.kind == "capacity-limit" }
      if !capacities.isEmpty { Text(capacities.map { $0.label + " " + usageValue($0) }.joined(separator: " · ")) }
      if data.status != "ok" { Text(L10n.tr("暂未提供额度")) }
      if usage.cached(client, target: target) { Text(L10n.tr("缓存额度")) }
      else if data.partial == true { Text(L10n.tr("部分额度暂不可用")) }
    }.font(.caption2).foregroundStyle(.secondary).lineLimit(2)
      .onAppear { visible = true }.onDisappear { visible = false }
      .task(id: "\(usage.identity(client, target: target)):\(scenePhase):\(visible)") { if visible && scenePhase == .active { await usage.poll(client, target: target) } }
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
            Text(balance + (data.balanceDebt == true ? " · " + L10n.tr("欠额") : "")).font(.system(size: 12, weight: .medium)).monospacedDigit()
            Text((data.balanceUnit ?? "") + ((data.extraBalances ?? 0) > 0 ? " +\(data.extraBalances!)" : "") + (cached ? " · " + L10n.tr("缓存") : ""))
              .font(.system(size: 10)).foregroundStyle(.secondary)
          }.lineLimit(1).minimumScaleFactor(0.7)
        } else if cached { Text(L10n.tr("缓存")).font(.caption2).foregroundStyle(.secondary) }
        else if window == nil { Text(L10n.tr("当前客户端额度")).font(.caption2).foregroundStyle(.secondary) }
      }.frame(minHeight: 44).contentShape(Rectangle())
    }.accessibilityIdentifier("current-client-usage")
      .accessibilityLabel(([L10n.tr("当前客户端额度")] + [window.flatMap { UsagePresentation.remaining($0) }.map { L10n.tr("剩余\($0)") }, data.balance.map { $0 + " " + (data.balanceUnit ?? "") + (data.balanceDebt == true ? " · " + L10n.tr("欠额") : "") }, cached ? L10n.tr("缓存额度") : nil, data.partial == true ? L10n.tr("部分额度暂不可用") : nil, window == nil && data.balance == nil ? L10n.tr("暂不可用") : nil].compactMap { $0 }).joined(separator: "，"))
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
        if data.status != "ok" { Text(L10n.tr("暂未提供额度")); if let reason = data.reason { Text(usageReasonText(reason)).font(.caption).foregroundStyle(.secondary) }; if let failure = data.failureReason, failure != data.reason { Text(usageReasonText(failure)).font(.caption).foregroundStyle(.secondary) } }
      }.accessibilityIdentifier("usage-details-content")
        .navigationTitle(L10n.tr("当前客户端额度"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L10n.tr("完成")) { dismiss() } } }
        .refreshable { await usage.refresh(client, target: target, force: true) }
        .task(id: "\(usage.identity(client, target: target)):\(scenePhase)") { if scenePhase == .active { await usage.poll(client, target: target) } }
    }.presentationDetents([.medium, .large])
  }
}

private struct UsageMetricSection: View {
  let metric: UsageMetricViewData
  var body: some View {
    Section(metric.bucket ?? metric.label) {
      LabeledContent(metric.label, value: usageValue(metric)).textSelection(.enabled)
      if metric.state == "unavailable" { Text(usageReasonText(metric.value)).font(.caption) }
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
}

private func usageReasonText(_ reason: String) -> String {
  let key: String = UsageReadRules.shared.reasonKey(reason: reason)
  return L10n.tr(LocalizedText(stringLiteral: key))
}
