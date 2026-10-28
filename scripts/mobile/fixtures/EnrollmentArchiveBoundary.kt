package com.agenthub.android

/** JVM seam for the archive-validation result only. Does not test/promote a P7 archive graph. */
internal object NativeMigrationNotificationArchives {
    const val FIXTURE_BOUNDARY = "fixture-p14-requires-notification-setup"
    fun requiresExplicitSetup(storage: NativeSessionStorage): Boolean {
        val value = storage.read(FIXTURE_BOUNDARY)
        check(value != "invalid") { "Fixture archive validation failure" }
        return value == "required"
    }
}

/** Display-only pair avoids the Android Compose compiler's synthetic stability field at this JVM boundary. */
internal data class NativeChatNotificationComputer(val target: NativeChatNoticeTarget, val name: String)
