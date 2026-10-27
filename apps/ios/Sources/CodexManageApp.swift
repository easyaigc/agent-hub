import SwiftUI
import Network
import Darwin

@main
struct CodexManageApp: App {
    @UIApplicationDelegateAdaptor(TaskNotificationAppDelegate.self) private var notificationDelegate
    @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @Environment(\.scenePhase) private var scenePhase
    init() {
        SessionBackgroundSave.installUnreadTransactionLifetime()
        #if DEBUG
        QuickAttachmentAcceptance.resetOwnedInboxIfRequested()
        NativeNetworkAcceptanceProbe.start()
        NativeThermalAcceptanceProbe.start()
        #endif
    }
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.locale, L10n.locale)
                .onAppear { L10n.syncNotificationLanguage() }
                .onChange(of: appLanguage) { _, _ in L10n.syncNotificationLanguage() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { L10n.syncNotificationLanguage() }
                }
        }
    }
}

#if DEBUG
enum NativeThermalIOCounters {
    private final class Storage: @unchecked Sendable {
        let lock = NSLock()
        var writes = 0, bytes = 0, reads = 0, readBytes = 0, clones = 0, cloneBytes = 0
        var stages: [String: Double] = [:]
        var writeKinds: [String: Int] = [:]
    }
    private static let storage = Storage()
    private static let enabled = ProcessInfo.processInfo.arguments.contains("-thermal-performance-probe")
    static func recordWrite(bytes: Int, kind: String) {
        guard enabled else { return }
        storage.lock.lock(); defer { storage.lock.unlock() }
        storage.writes += 1; storage.bytes += bytes
        storage.writeKinds[kind, default: 0] += bytes
    }
    static func recordRead(bytes: Int) {
        guard enabled else { return }
        storage.lock.lock(); defer { storage.lock.unlock() }
        storage.reads += 1; storage.readBytes += bytes
    }
    static func recordClone(bytes: Int) {
        guard enabled else { return }
        storage.lock.lock(); defer { storage.lock.unlock() }
        storage.clones += 1; storage.cloneBytes += bytes
    }
    static func measure<T>(_ stage: String, _ body: () throws -> T) rethrows -> T {
        guard enabled else { return try body() }
        let start = ProcessInfo.processInfo.systemUptime
        defer {
            let elapsed = (ProcessInfo.processInfo.systemUptime - start) * 1000
            storage.lock.lock(); storage.stages[stage, default: 0] += elapsed; storage.lock.unlock()
        }
        return try body()
    }
    static var snapshot: [String: Any] {
        storage.lock.lock(); defer { storage.lock.unlock() }
        var values: [String: Any] = ["sessionWrites": storage.writes, "sessionWriteBytes": storage.bytes,
            "sessionReads": storage.reads, "sessionReadBytes": storage.readBytes,
            "sessionClones": storage.clones, "sessionCloneReferencedBytes": storage.cloneBytes]
        for (stage, milliseconds) in storage.stages { values["session" + stage + "WallMS"] = milliseconds }
        for (kind, bytes) in storage.writeKinds { values["session" + kind + "WriteBytes"] = bytes }
        return values
    }
}

@MainActor enum NativeThermalAcceptanceProbe {
    static func start() {
        guard ProcessInfo.processInfo.arguments.contains("-thermal-performance-probe") else { return }
        UIDevice.current.isBatteryMonitoringEnabled = true
        Task { @MainActor in
            while !Task.isCancelled {
                var usage = rusage()
                let usageResult = getrusage(RUSAGE_SELF, &usage)
                var power = task_power_info_data_t()
                var count = mach_msg_type_number_t(MemoryLayout<task_power_info_data_t>.size / MemoryLayout<integer_t>.size)
                let powerResult = withUnsafeMutablePointer(to: &power) { pointer in
                    pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                        task_info(mach_task_self_, task_flavor_t(TASK_POWER_INFO), $0, &count)
                    }
                }
                let cpu = Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec)
                    + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
                var row: [String: Any] = ["utc": Date().timeIntervalSince1970,
                    "uptime": ProcessInfo.processInfo.systemUptime, "cpuSeconds": cpu,
                    "usageResult": usageResult, "powerResult": powerResult,
                    "interruptWakeups": power.task_interrupt_wakeups,
                    "idleWakeups": power.task_platform_idle_wakeups,
                    "thermalState": ProcessInfo.processInfo.thermalState.rawValue,
                    "batteryLevel": UIDevice.current.batteryLevel,
                    "batteryState": UIDevice.current.batteryState.rawValue,
                    "brightness": UIScreen.main.brightness,
                    "lowPowerMode": ProcessInfo.processInfo.isLowPowerModeEnabled]
                for (key, value) in NativeThermalIOCounters.snapshot { row[key] = value }
                if var data = try? JSONSerialization.data(withJSONObject: row, options: .sortedKeys) {
                    data.append(10)
                    let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    let url = directory.appendingPathComponent("thermal-acceptance.jsonl")
                    if FileManager.default.fileExists(atPath: url.path), let file = try? FileHandle(forWritingTo: url) {
                        try? file.seekToEnd(); try? file.write(contentsOf: data); try? file.close()
                    } else { try? data.write(to: url, options: .atomic) }
                }
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }
}

// Explicit opt-in, read-only diagnostics for the independent physical acceptance App.
// Never record addresses, credentials, request headers, or response content.
enum NativeNetworkAcceptanceProbe {
    static func start() {
        guard ProcessInfo.processInfo.arguments.contains("-thermal-network-probe"),
              let text = ProcessInfo.processInfo.environment["THERMAL_NETWORK_ENDPOINT"],
              let endpoint = URL(string: text), endpoint.scheme == "https",
              endpoint.user == nil, endpoint.password == nil,
              endpoint.query == nil, endpoint.fragment == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            emit(["kind": "path", "status": String(describing: path.status),
                  "reason": String(describing: path.unsatisfiedReason),
                  "wifi": path.usesInterfaceType(.wifi), "cellular": path.usesInterfaceType(.cellular),
                  "other": path.usesInterfaceType(.other), "expensive": path.isExpensive,
                  "constrained": path.isConstrained])
        }
        monitor.start(queue: DispatchQueue(label: "agent-hub.acceptance.network"))
        Task {
            defer { monitor.cancel() }
            for round in 0..<4 {
                for (label, url) in [("host", endpoint.appendingPathComponent("host")),
                                     ("internet", URL(string: "https://www.apple.com/library/test/success.html")!)] {
                    var request = URLRequest(url: url)
                    request.timeoutInterval = 8
                    let session = URLSession(configuration: .ephemeral)
                    defer { session.invalidateAndCancel() }
                    do {
                        let (data, response) = try await session.data(for: request)
                        emit(["kind": label, "round": round, "http": (response as? HTTPURLResponse)?.statusCode ?? 0, "bytes": data.count])
                    } catch {
                        let failure = error as NSError
                        let underlying = failure.userInfo[NSUnderlyingErrorKey] as? NSError
                        emit(["kind": label, "round": round, "domain": failure.domain, "code": failure.code,
                              "underlyingDomain": underlying?.domain ?? "", "underlyingCode": underlying?.code ?? 0,
                              "path": String(describing: failure.userInfo["_NSURLErrorNWPathKey"] ?? ""),
                              "unavailableReason": failure.userInfo[NSURLErrorNetworkUnavailableReasonKey] as? Int ?? -1])
                    }
                }
                try? await Task.sleep(for: .seconds(8))
            }
            emit(["kind": "completed"])
        }
    }
    private static func emit(_ row: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: row, options: .sortedKeys),
              let text = String(data: data, encoding: .utf8) else { return }
        print("THERMAL_NETWORK " + text)
    }
}
#endif
