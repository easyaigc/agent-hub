package com.agenthub.android

import android.content.Intent
import android.os.Looper
import org.json.JSONObject

/** Foreground authenticated local-event entry. Background encrypted/vendor ingress is intentionally absent. */
internal class NativeChatNotificationRuntime(
    private val store: NativeChatNotificationStore,
    private val platform: NativeChatNotificationPlatform,
    private val reading: NativeChatNotificationReading,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun main() = check(Looper.myLooper() == Looper.getMainLooper())
    val preferences get() = store.preferences()
    val remotePushReady get() = false
    fun update(change: (NativeChatNotificationPreferences) -> NativeChatNotificationPreferences) {
        main(); store.update(change); platform.cancelOwned()
    }
    fun bind(target: NativeChatNoticeTarget, credentialEpoch: String): NativeChatNoticeLease {
        main(); return store.bind(target, credentialEpoch)
    }
    fun revoke(target: NativeChatNoticeTarget) { main(); store.revoke(target); platform.cancelOwned(target) }
    /** Caller supplies a lease captured by the already authenticated exact-target event reader. */
    suspend fun receive(wire: String, lease: NativeChatNoticeLease, computer: String, foreground: Boolean): String {
        main(); require(wire.length <= 16_384)
        val event = NativeChatNotificationCodec.event(JSONObject(wire))
        if (event.target != lease.target || !store.current(lease)) return "stale-target-or-generation"
        val occurred = java.time.Instant.parse(event.occurredAt).toEpochMilli()
        if (occurred > now() + 60_000 || occurred <= now() - NativeChatNotificationStore.TTL_MILLIS) return "expired-or-future"
        val decision = reading.receive(event)
        main()
        if (!store.current(lease)) return "stale-target-or-generation"
        val prefs = preferences
        if (!prefs.permits(event) || !decision.shouldAlert || foreground && !prefs.foregroundBanner || !reading.mayPresent(event)) return "suppressed"
        val channel = platform.prepare(event.kind, prefs.soundFor(event.kind), prefs.badgeEnabled) ?: return "system-unavailable"
        val receipt = store.claim(event, lease, now()) { reading.mayPresent(event) } ?: return "duplicate-expired-or-read"
        return try {
            platform.post(receipt, prefs, computer, decision.unreadConversationCount, channel)
            store.finish(receipt.ticket, true)
            // notify() returning is not proof of display, sound, vendor acceptance or delivery.
            "submitted-to-system"
        } catch (_: Exception) { store.finish(receipt.ticket, false); "unknown-no-replay" }
    }
    suspend fun open(intent: Intent, route: NativeChatNotificationRoute): Boolean {
        main()
        if (intent.action != NativeChatNotificationPlatform.ACTION) return false
        val ticket = intent.getStringExtra(NativeChatNotificationPlatform.TICKET) ?: return false
        if (runCatching { java.util.UUID.fromString(ticket).toString() == ticket }.getOrDefault(false).not()) return false
        val receipt = store.ticket(ticket, now()) ?: return false
        if (!route.refreshExactTarget(receipt.event.target, receipt.lease.credentialEpoch)) return false
        main()
        if (!store.current(receipt.lease) || !preferences.permits(receipt.event)) return false
        val original = store.consume(ticket, now()) ?: return false
        route.openPreservingDraft(original.event)
        return true
    }
}
