package com.agenthub.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.os.Build
import androidx.test.filters.SdkSuppress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Parent grants only this exact acceptance package. All posted notices use a silent channel. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationOsGrantedTest : NativeChatNotificationOsBase() {
    override val permissionMode = "granted"
    @Test fun realSilentPostHasPrivatePublicVersionAndOriginalImmutablePendingIntent() {
        val f = fixture(); f.configure(); val event = f.event(1)
        assertEquals("submitted-to-system", f.deliver(event)); f.awaitCount(1)
        val notice = f.active().single().notification; val original = f.receipt(event)
        assertEquals("posted", original.state); assertFalse(original.consumed)
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility); assertEquals(7, notice.number)
        assertEquals("Private title 1", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(notice.publicVersion.extras.toString().contains("Private")); assertNull(notice.publicVersion.contentIntent)
        val channel = checkNotNull(f.manager.getNotificationChannel(notice.channelId)); assertTrue(channel.id.startsWith(f.scope.prefix)); assertNull(channel.sound)
        assertSystemOwnedVisibility(f, channel)
        val expected = NativeChatNotificationPlatform.openIntent(context, original.ticket)
        assertEquals(setOf(NativeChatNotificationPlatform.TICKET), expected.extras!!.keySet()); assertNull(expected.clipData)
        assertEquals(original.ticket, expected.data!!.lastPathSegment)
        val existing = checkNotNull(PendingIntent.getActivity(context, 0, expected, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        assertNotNull(existing); assertEquals(existing, notice.contentIntent); assertEquals(context.packageName, existing.creatorPackage); assertEquals(context.applicationInfo.uid, existing.creatorUid)
        if (Build.VERSION.SDK_INT >= 31) { assertTrue(existing.isActivity); assertTrue(existing.isImmutable) }
        assertEquals(listOf(event.id), f.received.toList())
        f.notes.put("pendingIntentIdentity", "canonical original ticket; payload/Activity dispatch not inspected").put("launcherBadgeAndLockscreenRender", "not-tested")
    }
    /** Compare system policy using a real, fresh owned control channel; do not assume app setter authority. */
    private fun assertSystemOwnedVisibility(f: NotificationOsFixture, channel: NotificationChannel) {
        val submitted = checkNotNull(f.platform.lastChannelSubmission)
        assertEquals(channel.id, submitted.id); assertEquals(Notification.VISIBILITY_PRIVATE, submitted.lockscreenVisibility)
        // API26 AOSP uses this sentinel internally; it became a public Ranking constant in API31.
        val noOverride = -1000
        val control = NotificationChannel(f.scope.prefix + "visibility-policy-reference", "Owned visibility reference", NotificationManager.IMPORTANCE_LOW)
        assertEquals(noOverride, control.lockscreenVisibility); control.setSound(null, null)
        f.manager.createNotificationChannel(control)
        val policy = checkNotNull(f.manager.getNotificationChannel(control.id)).lockscreenVisibility
        val meaning = when(policy) {
            noOverride -> "no-system-user-override"
            Notification.VISIBILITY_PRIVATE -> "system-user-private-override"
            Notification.VISIBILITY_SECRET -> "system-user-secret-override"
            Notification.VISIBILITY_PUBLIC -> "system-user-public-override"
            else -> error("Unknown system channel visibility $policy")
        }
        f.notes.put("channelVisibilitySubmitted", submitted.lockscreenVisibility).put("channelVisibilityObserved", channel.lockscreenVisibility)
            .put("controlVisibilitySubmitted", noOverride).put("controlVisibilityObserved", policy).put("channelVisibilityMeaning", meaning)
            .put("lockscreenUi", "not-verified")
        assertEquals("Stored visibility must match the independent same-app system policy control", policy, channel.lockscreenVisibility)
    }
    @Test fun previewOffAndBadgeOffReachActualPostedNotificationFields() {
        val f = fixture(); f.configure(preview = false, badge = false); val event = f.event(1)
        assertEquals("submitted-to-system", f.deliver(event)); f.awaitCount(1)
        val notice = f.active().single().notification
        assertFalse(notice.extras.toString().contains("Private")); assertFalse(notice.publicVersion.extras.toString().contains("Private")); assertEquals(0, notice.number)
        assertFalse(checkNotNull(f.manager.getNotificationChannel(notice.channelId)).canShowBadge()); assertNull(checkNotNull(f.manager.getNotificationChannel(notice.channelId)).sound)
    }
    @Test fun cancelEventsAndTargetNeverCancelAnotherOwnedRunOrTarget() {
        val f = fixture(); val witness = fixture(); f.configure(); witness.configure()
        val one = f.event(1); val two = f.event(2); val other = f.event(3, f.target.copy(workspaceId = "other-workspace")); val separate = witness.event(1)
        for (event in listOf(one, two, other)) assertEquals("submitted-to-system", f.deliver(event))
        assertEquals("submitted-to-system", witness.deliver(separate)); f.awaitCount(3); witness.awaitCount(1)
        f.main { f.platform.cancelEvents(f.target, setOf(one.id)) }; f.awaitCount(2); witness.awaitCount(1)
        assertTrue(f.active().none { it.tag.endsWith(one.id) }); assertTrue(f.active().any { it.tag.endsWith(two.id) })
        f.main { f.platform.cancelOwned(f.target) }; f.awaitCount(1); witness.awaitCount(1)
        assertTrue(f.active().single().tag.endsWith(other.id))
        f.main { f.platform.cancelOwned() }; f.awaitCount(0); witness.awaitCount(1)
        val retainedTicket = f.receipt(one).ticket
        f.io { assertNotNull(f.notices.ticket(retainedTicket, System.currentTimeMillis())) }
    }
    @Test fun ownedBlockedCategoryCannotBeBypassedWithAnotherSoundVariant() {
        val f = fixture(); val id = f.scope.prefix + NativeChatNoticeKind.APPROVAL.wire + ".owned-block"
        f.manager.createNotificationChannel(NotificationChannel(id, "Owned blocked fixture", NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationManager.IMPORTANCE_NONE, checkNotNull(f.manager.getNotificationChannel(id)).importance)
        assertNull(f.main { f.platform.prepareAsync(NativeChatNoticeKind.APPROVAL, "none", true) { true } })
        assertEquals(listOf(id), f.platform.channels().map { it.id })
        assertNotNull(f.main { f.platform.prepareAsync(NativeChatNoticeKind.QUESTION, "none", false) { true } })
        f.notes.put("blockSource", "own pre-created channel, not a system UI interaction")
    }
    @Test fun ordinaryClipChannelUsesRegisteredProviderUriWithoutPlayingIt() {
        val f = fixture(); val item = f.imported(NotificationOsFixture.tone())
        val id = f.main { f.platform.prepareAsync(NativeChatNoticeKind.APPROVAL, item.id, true) { true } }!!
        val channel = checkNotNull(f.manager.getNotificationChannel(id))
        assertEquals(f.platform.sounds.uri(item), channel.sound); assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertSystemOwnedVisibility(f, channel); assertTrue(channel.canShowBadge())
        assertEquals(item.sha256, NativeChatNotificationSoundLibrary.sha(f.readUri(item)))
        f.notes.put("systemProcessUriReadAndAudibility", "not-tested; no normal-sound post")
    }
    @Test fun requiredReadingGuardStillPreventsClaimAfterRealChannelPreparation() {
        val f = fixture(); f.configure(); f.mayPresent.set(false); val event = f.event(1)
        assertEquals("duplicate-expired-or-read", f.deliver(event)); f.awaitCount(0)
        assertEquals(1, f.platform.channels().size); assertEquals(listOf(event.id), f.received.toList())
        f.io { assertEquals(0, JSONObject(f.storage.read(NativeChatNotificationStore.RECORD)!!).getJSONArray("receipts").length()) }
    }
    @SdkSuppress(minSdkVersion = 33)
    @Test fun permissionCycleRecoveryShowsOnlyNewEventWithoutAnotherOriginalSubmit() {
        val f = fixture(cycleId()); val marker = f.io { JSONObject(checkNotNull(f.storage.read(NotificationOsFixture.CYCLE_RECORD))) }
        assertEquals("denied", marker.getString("stage")); assertEquals(f.scope.runId, marker.getString("runId"))
        val source = cycle(f, false); val old = source.event(1); val fresh = source.event(2)
        source.events.addAll(listOf(old, fresh)); source.active = setOf(old.id, fresh.id)
        val before = f.io { source.view() }; assertEquals(1L, before.after)
        assertEquals(marker.getString("originalHash"), NativeChatNotificationSoundLibrary.sha(before.operations.single().original.encoded().toByteArray()))
        assertEquals("foreground-synchronized", sync(f, source)?.state); f.awaitCount(1)
        assertEquals(listOf(fresh.id), f.received.toList()); assertTrue(f.active().single().tag.endsWith(fresh.id)); assertTrue(source.submitted.isEmpty())
        f.io {
            val after = source.view(); assertEquals(2L, after.after); assertEquals("system-unavailable", after.outcomes.single { it.event.id == old.id }.state)
            assertEquals(marker.getString("identityHash"), NativeChatNotificationSoundLibrary.sha(f.storage.read(NativeChatNotificationIdentity.RECORD)!!.toByteArray()))
            assertEquals(marker.getString("originalHash"), NativeChatNotificationSoundLibrary.sha(after.operations.single().original.encoded().toByteArray()))
        }
        f.notes.put("cycleStage", "granted-complete").put("originalOperationId", before.operations.single().original.operationId)
    }
    /** Separate v2 result: query cache restored only from the exact writer-captured synthetic response. */
    @SdkSuppress(minSdkVersion = 33)
    @Test fun durableFixtureCycleRecoveryUsesRecordedResponseWithoutOriginalResubmit() {
        val runId = cycleId(); val seed = recordedCycle(runId)
        val f = fixture(runId); f.preserveData = true
        val markerRaw = seed.markerRaw; val identityHash = seed.identityHash
        val source = cycle(f, false); val before = f.io { source.view() }; assertEquals(1L, before.after)
        val original = before.operations.single().original
        assertEquals(seed.operation.original.encoded(), original.encoded())
        val response = seed.response
        assertEquals(NativeChatRecoveryCanonical.value(response.json()), NativeChatRecoveryCanonical.value(checkNotNull(before.operations.single().receipt).json()))
        assertTrue(source.receipts.isEmpty()); source.receipts[original.operationId] = response
        val old = source.event(1); val fresh = source.event(2)
        source.events.addAll(listOf(old, fresh)); source.active = setOf(old.id, fresh.id)
        assertEquals("foreground-synchronized", sync(f, source)?.state); f.awaitCount(1)
        assertEquals(listOf(fresh.id), f.received.toList()); assertTrue(f.active().single().tag.endsWith(fresh.id)); assertTrue(source.submitted.isEmpty())
        assertEquals(listOf(original.operationId), source.queried.map { it.operationId })
        f.io {
            val after = source.view(); assertEquals(2L, after.after); assertEquals("system-unavailable", after.outcomes.single { it.event.id == old.id }.state)
            assertEquals(identityHash, NativeChatNotificationWire.hash(checkNotNull(f.storage.read(NativeChatNotificationIdentity.RECORD))))
            assertEquals(original.encoded(), after.operations.single().original.encoded())
            assertEquals(NativeChatRecoveryCanonical.value(response.json()), NativeChatRecoveryCanonical.value(checkNotNull(after.operations.single().receipt).json()))
            assertEquals(markerRaw, f.storage.read(NotificationOsFixture.CYCLE_RECORD))
        }
        f.notes.put("cycleStage", "independent-v2-granted-complete").put("originalOperationId", original.operationId)
            .put("restoredFixtureResponseHash", NativeChatRecoveryCanonical.hash(response.json())).put("backendProvenance", "exact writer-captured synthetic response only; not real Host confirmation")
    }
}
