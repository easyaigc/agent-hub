import SwiftUI
import Network
import Darwin
import UIKit

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

#if DEBUG
/// Ordinary independent App lifecycle proof without timers, background assertions
/// or model input. Only a separately authorized Simulator enrollment case may
/// start the normal system permission and notification registration flow once.
@MainActor enum IndependentAcceptanceLifecycle {
    private static var recorder: Recorder?
    static var enabled: Bool { recorder != nil }
    static var requested: Bool {
        Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance"
            && ProcessInfo.processInfo.arguments.contains("-acceptance-lifecycle-observer")
    }
    static func install() {
        guard recorder == nil else { return }
        let process = ProcessInfo.processInfo
        guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
            process.arguments.contains("-acceptance-lifecycle-observer"),
            process.environment["XCTestConfigurationFilePath"] == nil,
            NSClassFromString("XCTestCase") == nil,
            let run = process.environment["EASYWORK_LIFECYCLE_CASE"],
            UUID(uuidString: run)?.uuidString.lowercased() == run,
            let raw = process.environment["EASYWORK_LIFECYCLE_CONFIG_JSON"],
            let config = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
            config["caseId"] as? String == run,
            config["newModelInputsAuthorized"] as? Int == 0,
            config["newBusinessCommandsAuthorized"] as? Int == 0,
            let value = config["target"],
            let data = try? JSONSerialization.data(withJSONObject: value),
            let target = try? JSONDecoder().decode(AgentRuntimeTarget.self, from: data),
            target.agentId == "easywork" else { return }
        let enrollment = config["requestIndependentSandboxEnrollment"] as? Bool == true
            && config["independentSandboxEnrollmentAuthorized"] as? Bool == true
            && config["maximumEnrollmentStartsAuthorized"] as? Int == 1
        recorder = try? Recorder(caseId: run, target: target, enrollmentAuthorized: enrollment)
    }
    static func attach(_ store: SessionStore) -> Bool { recorder?.attach(store) == true }
    static func beginAuthorizedEnrollment() { recorder?.beginAuthorizedEnrollment() }
    static func scene(_ phase: ScenePhase) { recorder?.record("scene-\(phase)") }
    static func notificationStatusChanged() { recorder?.record("apns-registration-state") }
    static func notificationCallback(_ event: String) { recorder?.record(event) }
    static func protectedComponentMatches() -> [String: Bool] { recorder?.componentMatches() ?? [:] }

    @MainActor private final class Recorder {
        let caseId: String, target: AgentRuntimeTarget, file: URL
        private weak var store: SessionStore?
        private var baseline: String?
        private var baselineComponents: [String: String] = [:]
        private var events: [[String: Any]] = []
        private var observers: [NSObjectProtocol] = []
        private var dropped = 0
        private let enrollmentAuthorized: Bool
        private var enrollmentStarted = false
        init(caseId: String, target: AgentRuntimeTarget, enrollmentAuthorized: Bool) throws {
            self.caseId = caseId; self.target = target; self.enrollmentAuthorized = enrollmentAuthorized
            let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("easywork-lifecycle", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            file = directory.appendingPathComponent(caseId + ".json")
            // A relaunched process cannot silently replace another process's proof.
            guard !FileManager.default.fileExists(atPath: file.path) else {
                throw HostServiceError.message("Lifecycle case already exists")
            }
            for name in [UIApplication.didBecomeActiveNotification, UIApplication.willResignActiveNotification,
                         UIApplication.didEnterBackgroundNotification, UIApplication.willEnterForegroundNotification,
                         UIApplication.willTerminateNotification] {
                observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                    let event = note.name.rawValue
                    MainActor.assumeIsolated { self?.record(event) }
                })
            }
            record("ordinary-process-started")
        }
        func attach(_ store: SessionStore) -> Bool {
            guard self.store == nil, !store.localRecoveryRequired,
                store.saved.clients.filter({ !$0.isDemo }).count == 1,
                store.saved.clients.first(where: { !$0.isDemo })?.agentConnection?.target == target else { return false }
            self.store = store
            baseline = try? fingerprint(store)
            baselineComponents = (try? componentFingerprints(store)) ?? [:]
            guard baseline != nil else { record("protected-state-unavailable"); return false }
            record("app-owned-store-ready")
            return true
        }
        // A separate opt-in uses the normal product enrollment flow once.
        // Historical observer cases remain read-only and never enter this path.
        func beginAuthorizedEnrollment() {
            #if targetEnvironment(simulator)
            guard enrollmentAuthorized, !enrollmentStarted, let store,
                !store.localRecoveryRequired,
                store.saved.clients.filter({ !$0.isDemo }).count == 1,
                store.saved.clients.first(where: { !$0.isDemo })?.agentConnection?.target == target,
                Bundle.main.object(forInfoDictionaryKey: "NotificationSimulatorUsesSharedKeychain") as? Bool == true,
                Bundle.main.object(forInfoDictionaryKey: "NotificationEnvironment") as? String == "development" else { return }
            guard !TaskNotificationController.shared.preferences.enabled else {
                record("authorized-enrollment-already-enabled-no-repeat"); return
            }
            enrollmentStarted = true
            record("authorized-real-system-notification-enrollment-started")
            TaskNotificationController.shared.enable(true)
            #endif
        }
        private func fingerprint(_ store: SessionStore) throws -> String {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            var data = try encoder.encode(store.drafts.values)
            data.append(try encoder.encode(store.saved.outbox))
            data.append(try encoder.encode(store.saved.replyRecovery))
            data.append(try encoder.encode(store.saved.generationPreferences))
            data.append(try encoder.encode([store.saved.selectedClientId, store.selectedThreadId, store.selectedProjectId]))
            data.append(Data(try (CredentialVault.existingToken("easywork-device-identity") ?? "").utf8))
            return HostService.hash(data)
        }
        private func componentFingerprints(_ store: SessionStore) throws -> [String: String] {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            return [
                "identity": HostService.hash(Data(try (CredentialVault.existingToken("easywork-device-identity") ?? "").utf8)),
                "drafts": HostService.hash(try encoder.encode(store.drafts.values)),
                "outbox": HostService.hash(try encoder.encode(store.saved.outbox)),
                "recovery": HostService.hash(try encoder.encode(store.saved.replyRecovery)),
                "generationPreferences": HostService.hash(try encoder.encode(store.saved.generationPreferences)),
                "selection": HostService.hash(try encoder.encode([store.saved.selectedClientId, store.selectedThreadId, store.selectedProjectId])),
            ]
        }
        func componentMatches() -> [String: Bool] {
            guard let store, let current = try? componentFingerprints(store), !baselineComponents.isEmpty else { return [:] }
            return Dictionary(uniqueKeysWithValues: baselineComponents.map {
                ($0.key, current[$0.key] == $0.value)
            })
        }
        func record(_ event: String) {
            let protectedUnchanged = store.flatMap { try? fingerprint($0) }.map { baseline == $0 } ?? false
            let notifications = TaskNotificationController.shared
            let status = store?.saved.clients.first(where: { !$0.isDemo }).flatMap { notifications.statuses[$0.id] }
            let ready = status?.target == target && status?.state == "ready" && status?.delivery == "apns"
                && status?.revision == notifications.preferences.revision && status?.bindingId != nil
            events.append(["event": event, "at": ISO8601DateFormatter().string(from: Date()),
                "unixTime": Date().timeIntervalSince1970, "uptime": ProcessInfo.processInfo.systemUptime,
                "applicationState": UIApplication.shared.applicationState.rawValue,
                "sceneStates": UIApplication.shared.connectedScenes.map { $0.activationState.rawValue }.sorted(),
                "apnsReady": ready,
                "apnsState": status?.state ?? "none", "apnsDelivery": status?.delivery ?? "none",
                "apnsBindingId": status?.bindingId ?? "", "apnsRevision": status?.revision ?? "",
                "notificationPreferencesEnabled": notifications.preferences.enabled,
                "apnsTargetMatches": status?.target == target,
                "apnsRevisionMatches": status?.revision == notifications.preferences.revision,
                "uikitAPNsRegistered": UIApplication.shared.isRegisteredForRemoteNotifications,
                "uikitAPNsRegistrationLabel": notifications.apnsRegistration,
                "applicationDelegateClass": UIApplication.shared.delegate.map { String(describing: type(of: $0)) } ?? "none",
                "originalProtectedComponentMatches": componentMatches(),
                "originalIdentityDraftsOutboxRecoveryPreferencesSelectionUnchanged": protectedUnchanged])
            if events.count > 64 { events.removeFirst(); dropped += 1 }
            let value: [String: Any] = ["caseId": caseId, "bundleId": Bundle.main.bundleIdentifier!,
                "pid": ProcessInfo.processInfo.processIdentifier, "ordinaryAppWithoutXCTest": true,
                "events": events, "droppedEvents": dropped, "newModels": 0, "newBusinessCommands": 0,
                "syntheticEvents": 0, "addedBackgroundAssertions": 0, "wholeFeaturePassed": false]
            do {
                try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
                    .write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } catch { /* No business action is available in this observer mode. Missing proof fails acceptance. */ }
        }
    }
}
#endif
