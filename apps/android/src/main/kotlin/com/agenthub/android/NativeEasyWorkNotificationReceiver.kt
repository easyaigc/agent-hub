package com.agenthub.android

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject

internal class NativeEasyWorkNotificationReceiver(private val context: Context, private val storage: NativeSessionStorage, private val configuration: String) {
    suspend fun receive(envelope: NativeChatPushEnvelope): String = withContext(Dispatchers.IO) {
        val store = NativeEasyWorkNotificationStore(storage)
        val row = store.rows().singleOrNull { it.optString("phase") == "ready" && it.optString("bindingId") == envelope.bindingId }
            ?: return@withContext "binding-unavailable"
        val scope = NativeTaskNotificationCodec.target(row.getJSONObject("target"))
        require(scope.agentId == "easywork")
        val client = NativeEasyWorkNoticeAuthority.saved(storage).singleOrNull { it.id == row.getString("clientId") && sameScope(it.scope, scope) }
            ?: return@withContext "authority-unavailable"
        val authority = NativeEasyWorkNoticeAuthority.capture(storage, client)
        val prefs = NativeChatNotificationStore(storage)
        val configurationLease = prefs.configurationLease()
        val visible = NativeEasyWorkNotificationFence.lease(storage)
        fun current() = configurationLease() && visible() && authority.epoch == row.getString("credentialEpoch") && authority.current(storage) &&
            store.current(scope, row) && row.getString("configuration") == configuration &&
            NativeChatNotificationPushState(storage).matches(row.getString("tokenHash"), configuration) &&
            prefs.preferences().revision == row.getString("revision") && java.time.Instant.now() < envelope.expiresAt
        if (!current()) return@withContext "stale-target-or-generation"
        val raw = NativeChatNotificationIdentity.openScopedPush(storage, envelope, row.getString("peerPublicKey"))
        val event = NativeTaskNotificationCodec.notice(raw, scope); require(event.id == envelope.eventId)
        val occurred = java.time.Instant.parse(event.occurredAt).toEpochMilli()
        if (!current() || occurred > System.currentTimeMillis() + 60_000 || occurred <= System.currentTimeMillis() - NativeChatNotificationStore.TTL_MILLIS)
            return@withContext "expired-or-stale"
        val ledger = NativeReadingLedger(storage)
        val original = event.threadId?.takeIf { event.kind != "connection" }?.let { NativeReadingEvent(event.id, scope, it, event.kind, event.readPosition?.let { p -> ThreadReadPosition(p.revision, p.messageId, p.contentVersion) }, event.runId, event.requestId, event.operationId) }
        val unread = if (original != null) ledger.receive(original) else ledger.snapshot()
        val preferences = prefs.preferences()
        val process = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        fun present() = current() && preferences.enabled && event.kind in preferences.kinds.map { it.wire } &&
            scope.hostId !in preferences.excludedHosts && !envelope.badgeOnly && (original?.let(ledger::mayPresent) ?: (event.kind == "connection")) &&
            (process.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND || preferences.foregroundBanner)
        if (!present()) return@withContext "suppressed"
        val platform = NativeChatNotificationPlatform(context, NativeL10n(context, "system"), NativeChatNotificationSoundLibrary(context, storage))
        val kind = NativeChatNoticeKind.from(event.kind)
        val channel = platform.prepareAsync(kind, preferences.soundFor(kind), preferences.badgeEnabled, ::present) ?: return@withContext "system-unavailable"
        val binding = JSONObject(row.toString()).apply { remove("originals"); remove("intent"); remove("ack") }
        val ticket = store.claim(event, binding, ::present) ?: return@withContext "duplicate-or-stale"
        try {
            val posted = withContext(Dispatchers.Main.immediate) {
                if (!configurationLease() || !visible() || !platform.permission().canPost) false else { platform.postEasyWork(ticket, event, client.name, preferences, unread.count, channel); true }
            }
            withContext(NonCancellable + Dispatchers.IO) { store.finish(ticket, if (posted) "posted" else "suppressed") }
            if (posted) "submitted-to-system" else "stale-target-or-generation"
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { store.finish(ticket, "unknown") }
            if (failure is CancellationException) throw failure
            "unknown-no-replay"
        }
    }
}
