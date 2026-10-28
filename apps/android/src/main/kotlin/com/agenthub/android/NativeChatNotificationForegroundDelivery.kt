package com.agenthub.android

import android.content.Intent
import kotlinx.coroutines.*

/** The monitor cannot manufacture an unread count or replace P7's reading/active-set policy. */
internal interface NativeChatNotificationForegroundPort {
    suspend fun receive(event: NativeChatNotificationEvent, lease: NativeChatNoticeLease, computer: String, current: () -> Boolean): String
    suspend fun reconcileActive(target: NativeChatNoticeTarget, activeIds: Set<String>, current: () -> Boolean)
    suspend fun cancel(target: NativeChatNoticeTarget)
}

/** Production adapter: ledger/claims/large JSON on IO; channel/post/navigation on Main. */
internal class NativeChatNotificationForegroundDelivery(private val store: NativeChatNotificationStore,
    private val platform: NativeChatNotificationSystem, private val reading: NativeChatNotificationReading,
    private val reconcile: suspend (NativeChatNoticeTarget, Set<String>, () -> Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) : NativeChatNotificationForegroundPort {
    override suspend fun receive(event: NativeChatNotificationEvent, lease: NativeChatNoticeLease, computer: String, current: () -> Boolean): String {
        val presentation = store.presentationLease(lease.target)
        val valid = { current() && presentation() }
        if (!valid() || event.target != lease.target) return "stale-target-or-generation"
        val occurred = java.time.Instant.parse(event.occurredAt).toEpochMilli()
        if (occurred > now() + 60_000 || occurred <= now() - NativeChatNotificationStore.TTL_MILLIS) return "expired-or-future"
        val preferences = withContext(Dispatchers.IO) { if (!valid() || !store.current(lease)) null else store.preferences() }
            ?: return "stale-target-or-generation"
        // Even a muted kind may be a Host-provided badge event. P7 receives the exact original first.
        val decision = withContext(Dispatchers.IO) { if (valid()) reading.receive(event) else null } ?: return "stale-target-or-generation"
        if (!valid()) return "stale-target-or-generation"
        if (!preferences.permits(event) || !decision.shouldAlert || !preferences.foregroundBanner) return "suppressed"
        val channel = platform.prepareAsync(event.kind, preferences.soundFor(event.kind), preferences.badgeEnabled, valid) ?: return "system-unavailable"
        val receipt = withContext(Dispatchers.IO) {
            if (!valid()) null else store.claim(event, lease, now()) { valid() && reading.mayPresent(event) }
        } ?: return "duplicate-expired-or-read"
        try {
            val submitted = withContext(Dispatchers.Main.immediate) {
                if (!valid()) false else { platform.post(receipt, preferences, computer, decision.unreadConversationCount, channel); true }
            }
            withContext(NonCancellable + Dispatchers.IO) { store.finish(receipt.ticket, submitted) }
            return if (submitted) "submitted-to-system" else "stale-target-or-generation"
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { store.finish(receipt.ticket, false) }
            if (failure is CancellationException) throw failure
            return "unknown-no-replay"
        }
    }
    override suspend fun reconcileActive(target: NativeChatNoticeTarget, activeIds: Set<String>, current: () -> Boolean) {
        val presentation = store.presentationLease(target); val valid = { current() && presentation() }
        if (!valid()) return
        withContext(Dispatchers.IO) { if (valid()) reconcile(target, activeIds, valid) }
        val resolved = withContext(Dispatchers.IO) { if (valid()) store.inactiveActionable(target, activeIds) else emptySet() }
        withContext(Dispatchers.Main.immediate) { if (valid()) platform.cancelEvents(target, resolved) }
    }
    override suspend fun cancel(target: NativeChatNoticeTarget) {
        try { withContext(Dispatchers.IO) { store.revoke(target) } }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { platform.cancelOwned(target) } }
    }
    /** Parent supplies the existing exact-target, draft-preserving route; there is no replay/action port. */
    suspend fun open(intent: Intent, route: NativeChatNotificationRoute, current: () -> Boolean): Boolean {
        val presentation = store.presentationLease(); val valid = { current() && presentation() }
        if (!valid() || intent.action != NativeChatNotificationPlatform.ACTION) return false
        val ticket = intent.getStringExtra(NativeChatNotificationPlatform.TICKET) ?: return false
        if (!runCatching { NativeChatNotificationWire.uuid(ticket) == ticket }.getOrDefault(false)) return false
        val receipt = withContext(Dispatchers.IO) { if (valid()) store.ticket(ticket, now()) else null } ?: return false
        val exact = store.presentationLease(receipt.event.target); val routeCurrent = { valid() && exact() }
        if (!withContext(Dispatchers.Main.immediate) { routeCurrent() && route.refreshExactTarget(receipt.event.target, receipt.lease.credentialEpoch) }) return false
        val original = withContext(Dispatchers.IO) {
            if (!routeCurrent() || !store.current(receipt.lease) || !store.preferences().permits(receipt.event)) null else store.consume(ticket, now())
        } ?: return false
        return withContext(Dispatchers.Main.immediate) { if (!routeCurrent()) false else { route.openPreservingDraft(original.event); true } }
    }
}
