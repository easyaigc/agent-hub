package com.agenthub.android

import android.content.Context
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject

/** Same encrypted store, P7 ledger, claim and opaque click ticket as foreground notices. */
internal class NativeChatNotificationPushReceiver(private val context: Context, private val storage: NativeSessionStorage,
    private val configuration: String) {
    suspend fun receive(value: JSONObject): String = withContext(Dispatchers.IO) {
        val envelope = NativeChatPushEnvelopeCodec.decode(value)
        val push = NativeChatNotificationPushState(storage)
        val row = push.rows().singleOrNull { it.optString("stage") == "ready" && it.optString("bindingId") == envelope.bindingId }
            ?: return@withContext "binding-unavailable"
        val target = NativeChatNotificationCodec.target(row.getJSONObject("target"))
        val lease = NativeChatNotificationCodec.lease(row.getJSONObject("lease"))
        require(lease.target == target)
        val notices = NativeChatNotificationStore(storage)
        val authority = NativeChatNotificationAuthorizations(storage).all().singleOrNull {
            it.target == target && it.snapshot.authorizationFingerprint == row.getString("authorization")
        } ?: return@withContext "authority-unavailable"
        val presentation = notices.presentationLease(target)
        fun current(): Boolean = presentation() && java.time.Instant.now() < envelope.expiresAt &&
            notices.current(lease) && row.getString("configuration") == configuration &&
            push.matches(row.getString("tokenHash"), configuration) &&
            push.row(target)?.toString() == row.toString() && NativeChatNotificationPushAuthority(storage).current(authority)
        if (!current()) return@withContext "stale-target-or-generation"
        val event = NativeChatNotificationIdentity.openPush(storage, envelope, row.getString("peerPublicKey"), target)
        if (!current()) return@withContext "stale-target-or-generation"
        val ledger = NativeReadingLedger(storage)
        fun original(event: NativeChatNotificationEvent): NativeReadingEvent? {
            if (event.kind == NativeChatNoticeKind.CONNECTION) return null
            val thread = event.threadId ?: return null
            val scope = TargetScope("codex", null, null, target.hostId, target.workspaceId, target.runtimeId)
            return NativeReadingEvent(event.id, scope, thread, event.kind.wire,
                event.readPosition?.let { ThreadReadPosition(it.revision, it.messageId, it.contentVersion) },
                event.runId, event.requestId, event.operationId)
        }
        val reading = object : NativeChatNotificationReading {
            override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult {
                check(current())
                val source = original(event)
                val snapshot = if (source == null) ledger.snapshot() else ledger.receive(source)
                return NativeChatNoticeReadingResult(!envelope.badgeOnly && (source?.let(ledger::mayPresent)
                    ?: (event.kind == NativeChatNoticeKind.CONNECTION)), snapshot.count)
            }
            override fun mayPresent(event: NativeChatNotificationEvent) = current() && !envelope.badgeOnly &&
                (original(event)?.let(ledger::mayPresent) ?: (event.kind == NativeChatNoticeKind.CONNECTION))
        }
        val platform = NativeChatNotificationPlatform(context, NativeL10n(context, "system"), NativeChatNotificationSoundLibrary(context, storage))
        val process = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        val background = process.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val delivery = NativeChatNotificationForegroundDelivery(notices, platform, reading, { _, _, _ -> }, background = background)
        delivery.receive(event, lease, authority.client.name, ::current)
    }
}
