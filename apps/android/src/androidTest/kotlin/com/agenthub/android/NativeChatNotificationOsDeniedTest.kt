package com.agenthub.android

import androidx.test.filters.SdkSuppress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Parent owns grant/revoke/UI and restores the prior permission. No permission mutation in these tests. */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationOsDeniedTest : NativeChatNotificationOsBase() {
    override val permissionMode = "denied"
    @Test fun deniedPermissionReconcilesRequiredReadingWithoutChannelClaimOrPost() {
        val f = fixture(); f.configure(); val event = f.event(1)
        assertFalse(f.platform.permission().canPost); assertEquals("system-unavailable", f.deliver(event)); f.awaitCount(0)
        assertTrue(f.platform.channels().isEmpty()); assertEquals(listOf(event.id), f.received.toList())
        f.io { assertEquals(0, JSONObject(f.storage.read(NativeChatNotificationStore.RECORD)!!).getJSONArray("receipts").length()) }
    }
    @Test fun permissionCycleDenialPersistsTerminalOldEventForExplicitGrantPhase() {
        val f = fixture(cycleId()); f.preserveData = true
        f.io { check(f.storage.read(NotificationOsFixture.CYCLE_RECORD) == null && f.storage.read(NativeChatNotificationIdentity.RECORD) == null) { "Do not replay an existing permission cycle; inspect its original proof" } }
        val source = cycle(f, true); f.io { source.notices.update { it.copy(soundEnabled = false) } }
        val original = source.event(1); source.events.add(original); source.active = setOf(original.id)
        assertEquals("foreground-synchronized", sync(f, source)?.state); f.awaitCount(0)
        assertEquals(listOf(original.id), f.received.toList()); assertTrue(f.platform.channels().isEmpty()); assertEquals(1, source.submitted.size)
        f.io {
            val view = source.view(); assertEquals(1L, view.after); assertEquals("system-unavailable", view.outcomes.single().state)
            f.storage.write(NotificationOsFixture.CYCLE_RECORD, JSONObject().put("version", 1).put("stage", "denied").put("runId", f.scope.runId)
                .put("originalHash", NativeChatNotificationSoundLibrary.sha(view.operations.single().original.encoded().toByteArray()))
                .put("identityHash", NativeChatNotificationSoundLibrary.sha(f.storage.read(NativeChatNotificationIdentity.RECORD)!!.toByteArray())).toString())
        }
        f.notes.put("cycleStage", "denied-awaiting-parent-grant").put("releaseCondition", "same exact cycle UUID grant method succeeds, then cleanup its own store")
    }
    /** Independent v2 case only: fresh UUID; legacy failed cycles are never reseeded. */
    @Test fun durableFixtureCycleDenialRecordsActualSyntheticResponseForNewGrantCase() {
        val runId = cycleId(); NativeChatNotificationCycleFiles.requireFresh(context.noBackupFilesDir, runId)
        val f = fixture(runId); f.preserveData = true
        f.io { check(f.storage.read(NotificationOsFixture.CYCLE_RECORD) == null && f.storage.read(NativeChatNotificationIdentity.RECORD) == null && f.storage.read(NativeChatNotificationRecoveryStore.RECORD) == null) { "Fresh independent cycle required; preserve every original cycle" } }
        val source = cycle(f, true); f.io { source.notices.update { it.copy(soundEnabled = false) } }
        val original = source.event(1); source.events.add(original); source.active = setOf(original.id)
        assertEquals("foreground-synchronized", sync(f, source)?.state); f.awaitCount(0)
        assertEquals(listOf(original.id), f.received.toList()); assertTrue(f.platform.channels().isEmpty()); assertEquals(1, source.submitted.size)
        f.io {
            val view = source.view(); assertEquals(1L, view.after); assertEquals("system-unavailable", view.outcomes.single().state)
            val operation = view.operations.single()
            val generated = checkNotNull(source.receipts[operation.original.operationId])
            val marker = NativeChatNotificationCycleEvidence.capture(f.scope.runId, lease, android.os.Process.myPid(), NativeChatNotificationCycleEvidence.processNonce,
                NativeChatNotificationWire.hash(checkNotNull(f.storage.read(NativeChatNotificationIdentity.RECORD))), operation, generated)
            f.storage.write(NotificationOsFixture.CYCLE_RECORD, marker)
            f.notes.put("recordedFixtureResponseHash", NativeChatRecoveryCanonical.hash(generated.json())).put("fixtureEpoch", source.epoch)
        }
        f.notes.put("cycleStage", "independent-v2-denied-awaiting-new-process-grant").put("backendProvenance", "writer-generated synthetic response; no real Host")
    }
}
