package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Real encrypted local receipts; no account login, Firebase, system notification or network traffic. */
class NativeEasyWorkNotificationBoundaryTest {
    @Test fun accountScopeDedupUnknownClaimAndColdRouteClaimRemainSeparate() = runBlocking { withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = NativeSessionStorage(context, "p4-easy-notice-" + UUID.randomUUID())
        val store = NativeEasyWorkNotificationStore(storage)
        val a = TargetScope("easywork", "https://fixture.invalid", "subject-a", "fixture-host", "fixture-workspace", "fixture-runtime")
        val b = TargetScope("easywork", "https://fixture.invalid", "subject-b", "fixture-host", "fixture-workspace", "fixture-runtime")
        fun event(scope: TargetScope): NativeTaskNotice = NativeTaskNotificationCodec.notice(JSONObject().put("version", 1)
            .put("id", "a".repeat(64)).put("target", CodexCommandCodec.target(scope)).put("kind", "question")
            .put("occurredAt", Instant.now().toString()).put("threadId", "fixture-thread"), scope)
        try {
            assertNotEquals(NativeEasyWorkNotificationStore.key(a), NativeEasyWorkNotificationStore.key(b))
            fun binding() = JSONObject().put("phase", "ready").put("lease", UUID.randomUUID().toString()).put("credentialEpoch", "fixture-epoch").put("revision", "fixture-revision")
            val originalBinding = binding(); store.save(a, originalBinding)
            val ticket = requireNotNull(store.claim(event(a), originalBinding) { true })
            store.finish(ticket, "unknown")
            assertNull(store.claim(event(a), originalBinding) { true }) // Unknown OS post never replays.
            val other = binding(); store.save(b, other)
            assertNotNull(store.claim(event(b), other) { true }) // Same event ID, different real account is separate.
            val claim = requireNotNull(store.claimNavigation(ticket) { true })
            assertEquals(claim, store.claimNavigation(ticket) { true }) // Resume original local route, no new intent.
            store.acknowledgeNavigation(ticket, claim)
            assertNull(store.ticket(ticket))
            store.save(a, binding().put("phase", "revoked"))
            assertFalse(store.current(a, originalBinding))
        } finally { storage.remove(NativeEasyWorkNotificationStore.RECORD) }
    } }
}
