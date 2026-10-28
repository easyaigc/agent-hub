package com.agenthub.android

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Synthetic local boundaries with real Keystore/AtomicFile. Never posts an OS/remote notice. */
class NativeChatNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "P9-chat-notification-${UUID.randomUUID()}"
    private lateinit var storage: NativeSessionStorage
    private lateinit var store: NativeChatNotificationStore
    private val target = NativeChatNoticeTarget("host", "workspace", "desktop-runtime")
    private val now = 1_793_180_000_000L
    private fun event(id: String = "a".repeat(64), target: NativeChatNoticeTarget = this.target,
        occurred: Long = now, kind: NativeChatNoticeKind = NativeChatNoticeKind.APPROVAL) =
        NativeChatNotificationEvent(id, target, kind, Instant.ofEpochMilli(occurred).toString(), "thread",
            threadTitle = "private title", taskSummary = "private summary")
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }
    private fun enable(): NativeChatNoticeLease { store.update { it.copy(enabled = true) }; return store.bind(target, "credential-reference-1") }
    @Before fun prepare() {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        storage = NativeSessionStorage(context, namespace)
        store = NativeChatNotificationStore(storage)
    }
    @Test fun preferencesStrictRoundtripAndDefaults() {
        val initial = store.preferences(); assertFalse(initial.enabled); assertTrue(initial.showThreadTitle)
        assertEquals(7, initial.kinds.size)
        val encoded = NativeChatNotificationCodec.preferences(initial)
        assertEquals(initial, NativeChatNotificationCodec.preferences(encoded))
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("enabled", "false")) }
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("generation", 0.5)) }
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("future", true)) }
        rejected { NativeChatNotificationCodec.preferences(JSONObject(encoded.toString()).put("version", 2)) }
    }
    @Test fun filtersAndCategorySilencePersistAcrossColdStore() {
        store.update { it.copy(enabled = true, kinds = setOf(NativeChatNoticeKind.APPROVAL), excludedHosts = setOf("other"),
            showThreadTitle = false, categorySounds = mapOf(NativeChatNoticeKind.APPROVAL to "none")) }
        val cold = NativeChatNotificationStore(NativeSessionStorage(context, namespace)).preferences()
        assertTrue(cold.permits(event())); assertFalse(cold.permits(event(kind = NativeChatNoticeKind.COMPLETED)))
        assertFalse(cold.permits(event(target = target.copy(hostId = "other"))))
        assertFalse(cold.showThreadTitle); assertEquals("none", cold.soundFor(NativeChatNoticeKind.APPROVAL))
    }
    @Test fun exactTargetsIgnoreOnlyCodexAccountMetadata() {
        val a = NativeChatNoticeTarget.from(TargetScope("codex", "old", "user-a", "host", "workspace", "desktop-runtime"))
        val b = NativeChatNoticeTarget.from(TargetScope("codex", "new", "user-b", "host", "workspace", "desktop-runtime"))
        assertEquals(a, b); assertNotEquals(a, a.copy(runtimeId = "other"))
        rejected { NativeChatNoticeTarget.from(TargetScope("easywork", null, null, "host", "workspace", "runtime")) }
        rejected { NativeChatNotificationCodec.target(NativeChatNotificationCodec.target(a).put("hostId", JSONObject.NULL)) }
    }
    @Test fun eventRejectsUnknownPayloadAndReadScopeMismatch() {
        val valid = NativeChatNotificationCodec.event(event())
        assertEquals(event(), NativeChatNotificationCodec.event(valid))
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("kind", "business-task")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("shell", "unsafe")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("taskSummary", "secret\nsecond line")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("taskSummary", "secret\u202erendering")) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("readTarget", NativeChatNotificationCodec.target(target.copy(runtimeId = "wrong")))) }
        rejected { NativeChatNotificationCodec.event(JSONObject(valid.toString()).put("version", "1")) }
    }
    @Test fun completedReadVersionRemainsExactTransportValue() {
        val original = event(kind = NativeChatNoticeKind.COMPLETED).copy(readTarget = target,
            readPosition = NativeChatNoticeReadPosition(9, "message", "b".repeat(64)))
        assertEquals(original, NativeChatNotificationCodec.event(NativeChatNotificationCodec.event(original)))
        rejected { original.copy(kind = NativeChatNoticeKind.QUESTION) }
        rejected { original.copy(threadId = null) }
        rejected { NativeChatNoticeReadPosition(0, "message", "b".repeat(64)) }
    }
    @Test fun duplicateClaimCannotReplayAfterRestartOrUnknownSystemResult() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        store.finish(receipt.ticket, false)
        val cold = NativeChatNotificationStore(NativeSessionStorage(context, namespace))
        assertNull(cold.claim(event(), lease, now + 1))
        assertEquals("unknown", cold.ticket(receipt.ticket, now)!!.state)
    }
    @Test fun eventIdIsIsolatedByCompleteTargetAndRegistration() {
        val lease = enable(); assertNotNull(store.claim(event(), lease, now))
        val other = target.copy(workspaceId = "other-workspace")
        val next = store.bind(other, "credential-reference-1")
        assertNotNull(store.claim(event(target = other), next, now))
        assertNull(store.claim(event(target = other), lease, now))
    }
    @Test fun preferencesAndRevocationInvalidateCapturedLeaseAndTap() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        store.update { it.copy(showThreadTitle = false) }
        assertFalse(store.current(lease)); assertNull(store.ticket(receipt.ticket, now))
        assertNull(store.claim(event(), lease, now))
        val next = store.bind(target, "credential-reference-1"); store.revoke(target)
        assertFalse(store.current(next)); assertEquals(false, store.preferences().showThreadTitle)
    }
    @Test fun credentialRotationInvalidatesOldRegistrationWithoutReusingGeneration() {
        val old = enable(); assertEquals(old, store.bind(target, "credential-reference-1"))
        assertNotNull(store.claim(event(), old, now))
        val rotated = store.bind(target, "credential-reference-2")
        assertNotEquals(old.registrationGeneration, rotated.registrationGeneration)
        assertFalse(store.current(old)); assertTrue(store.current(rotated)); assertNull(store.claim(event(), old, now))
        assertNull(store.claim(event(), rotated, now))
    }
    @Test fun expiredAndFutureEventsAreNeverClaimed() {
        val lease = enable()
        assertNull(store.claim(event(occurred = now - NativeChatNotificationStore.TTL_MILLIS), lease, now))
        assertNull(store.claim(event(occurred = now + 60_001), lease, now))
        val receipt = store.claim(event(), lease, now)!!
        assertNull(store.ticket(receipt.ticket, now + NativeChatNotificationStore.TTL_MILLIS))
    }
    @Test fun clickRefreshesExactTargetAndConsumesOnceWithoutCommandSurface() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        var opened = 0; var refreshed = 0
        val route = object : NativeChatNotificationRoute {
            override suspend fun refreshExactTarget(target: NativeChatNoticeTarget, credentialEpoch: String): Boolean {
                assertEquals(lease.target, target); assertEquals(lease.credentialEpoch, credentialEpoch); refreshed++; return true
            }
            override fun openPreservingDraft(event: NativeChatNotificationEvent) { assertEquals(receipt.event, event); opened++ }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking {
                val runtime = runtime()
                val intent = Intent(NativeChatNotificationPlatform.ACTION).putExtra(NativeChatNotificationPlatform.TICKET, receipt.ticket)
                assertTrue(runtime.open(intent, route)); assertFalse(runtime.open(intent, route))
            }
        }
        assertEquals(1, opened); assertEquals(1, refreshed)
    }
    @Test fun lateClickRefreshCannotOpenAfterPreferenceChange() {
        val lease = enable(); val receipt = store.claim(event(), lease, now)!!
        val route = object : NativeChatNotificationRoute {
            override suspend fun refreshExactTarget(target: NativeChatNoticeTarget, credentialEpoch: String): Boolean {
                store.update { it.copy(enabled = false) }; return true
            }
            override fun openPreservingDraft(event: NativeChatNotificationEvent) { fail("Late click must not open") }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertFalse(runtime().open(Intent(NativeChatNotificationPlatform.ACTION).putExtra(NativeChatNotificationPlatform.TICKET, receipt.ticket), route)) }
        }
    }
    @Test fun readingReceivesExactVersionButCanSuppressPresentation() {
        val lease = enable(); var received = 0
        val reading = object : NativeChatNotificationReading {
            override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult {
                received++; return NativeChatNoticeReadingResult(false, 0)
            }
            override fun mayPresent(event: NativeChatNotificationEvent) = false
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertEquals("suppressed", runtime(reading).receive(NativeChatNotificationCodec.event(event()).toString(), lease, "computer", true)) }
        }
        assertEquals(1, received)
    }
    @Test fun expiredEventsNeverReachReadingAuthority() {
        val lease = enable()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertEquals("expired-or-future", runtime().receive(
                NativeChatNotificationCodec.event(event(occurred = now - NativeChatNotificationStore.TTL_MILLIS)).toString(), lease, "computer", true)) }
        }
    }
    @Test fun lateReadingResultCannotClaimAfterRevocation() {
        val lease = enable()
        val reading = object : NativeChatNotificationReading {
            override suspend fun receive(event: NativeChatNotificationEvent): NativeChatNoticeReadingResult {
                store.revoke(target); return NativeChatNoticeReadingResult(true, 1)
            }
            override fun mayPresent(event: NativeChatNotificationEvent): Boolean = error("Revoked delivery cannot proceed")
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runBlocking { assertEquals("stale-target-or-generation", runtime(reading).receive(
                NativeChatNotificationCodec.event(event()).toString(), lease, "computer", true)) }
        }
    }
    @Test fun previewOffRedactsCollapsedExpandedAndPublicPresentation() {
        val hidden = NativeChatNoticePresentation.create(event(), "computer", false) { it }
        assertEquals(event().kind.title, hidden.title); assertEquals("", hidden.summary)
        assertFalse(hidden.toString().contains("private"))
        val visible = NativeChatNoticePresentation.create(event(), "computer\u202e", true) { it }
        assertEquals("private title", visible.title); assertEquals("private summary", visible.summary)
        assertFalse(visible.metadata.contains('\u202e')); assertFalse(visible.publicTitle.contains("private"))
        val old = NativeChatNoticePresentation.create(event().copy(threadTitle = null, taskSummary = null), "computer", true) { it }
        assertEquals("对话 · thread", old.title); assertEquals(NativeChatNoticeKind.APPROVAL.fallback, old.summary)
    }
    @Test fun corruptPreferencesArePreservedAndFailClosed() {
        storage.write(NativeChatNotificationStore.RECORD, "{\"version\":9}")
        val before = storage.read(NativeChatNotificationStore.RECORD)
        rejected { store.preferences() }; rejected { store.bind(target, "credential-reference-1") }
        assertEquals(before, storage.read(NativeChatNotificationStore.RECORD))
    }
    private fun runtime(reading: NativeChatNotificationReading = object : NativeChatNotificationReading {
        override suspend fun receive(event: NativeChatNotificationEvent) = error("Route must never reconcile or execute")
        override fun mayPresent(event: NativeChatNotificationEvent) = false
    }) = NativeChatNotificationRuntime(store, NativeChatNotificationPlatform(context, NativeL10n(context, "en")), reading) { now }
}
