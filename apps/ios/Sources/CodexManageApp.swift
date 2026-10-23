import SwiftUI

@main
struct CodexManageApp: App {
    @UIApplicationDelegateAdaptor(TaskNotificationAppDelegate.self) private var notificationDelegate
    @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @Environment(\.scenePhase) private var scenePhase
    init() { SessionBackgroundSave.installUnreadTransactionLifetime() }
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
