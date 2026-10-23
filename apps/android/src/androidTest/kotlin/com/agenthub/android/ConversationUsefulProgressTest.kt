package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Native monotonic/time parsing policy counterexamples, without network or model calls. */
@RunWith(AndroidJUnit4::class)
class ConversationUsefulProgressTest {
    private val date="2026-10-22T00:00:00Z"
    @Test fun repeatedCachedCheckDoesNotPostponeActiveCatchUp() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("target",true,date,true)
        now=1_500;p.accepted("target",true,date,true);now=2_000
        assertEquals(0L,p.lastUsefulAt("target"));assertTrue(p.shouldCatchUp("target",true))
    }
    @Test fun olderCheckedAtCannotRenewUsefulness() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("target",true,date,true)
        now=1_500;p.accepted("target",true,"2026-10-21T23:59:59Z",true);now=2_000
        assertEquals(0L,p.lastUsefulAt("target"));assertTrue(p.shouldCatchUp("target",true))
    }
    @Test fun equivalentOffsetTimestampIsNotNewProgress() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("target",true,date,true)
        now=1_500;p.accepted("target",true,"2026-10-22T08:00:00+08:00",true);now=2_000
        assertEquals(0L,p.lastUsefulAt("target"));assertTrue(p.shouldCatchUp("target",true))
    }
    @Test fun onlyStrictlyNewerHistoryTimeRenewsUsefulClock() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("target",true,date,true)
        now=1_500;p.accepted("target",true,"2026-10-22T00:00:01Z",true);now=2_000
        assertEquals(1_500L,p.lastUsefulAt("target"));assertFalse(p.shouldCatchUp("target",true));now=3_500;assertTrue(p.shouldCatchUp("target",true))
    }
    @Test fun unavailableOrRestoredCacheNeverCreatesUsefulEvidence() {
        var now=1_000L;val p=ConversationUsefulProgress { now };p.accepted("unavailable",false,null,true);p.accepted("cache",false,null,false,false)
        assertNull(p.lastUsefulAt("unavailable"));assertNull(p.lastUsefulAt("cache"));assertTrue(p.shouldCatchUp("cache",false))
    }
    @Test fun liveSnapshotUpdatesMonotonicEvidenceWithoutHistoryFallbackTime() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("target",false,null,false)
        now=1_500;p.accepted("target",false,null,false);now=2_000
        assertEquals(1_500L,p.lastUsefulAt("target"));assertFalse(p.shouldCatchUp("target",true))
    }
    @Test fun distinctFullTargetKeysNeverShareUsefulProgress() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("host-a/workspace-a/runtime-a/thread",true,date,true)
        now=1_500;p.accepted("host-a/workspace-b/runtime-a/thread",true,date,true);now=2_000
        assertTrue(p.shouldCatchUp("host-a/workspace-a/runtime-a/thread",true));assertFalse(p.shouldCatchUp("host-a/workspace-b/runtime-a/thread",true))
    }
    @Test fun graceAndIdleWindowRemainIndependentFromActiveInterval() {
        var now=0L;val p=ConversationUsefulProgress { now };assertFalse(p.shouldCatchUp("empty",true,8_000));now=8_000;assertTrue(p.shouldCatchUp("empty",true,8_000))
        p.accepted("idle",false,null,false);now=10_000;assertFalse(p.shouldCatchUp("idle",false));now=18_000;assertTrue(p.shouldCatchUp("idle",false))
    }
    @Test fun invalidCheckedAtIsNotUsefulProgressAndCannotThrowAwayCachedContent() {
        var now=1_000L;val p=ConversationUsefulProgress { now };p.accepted("target",true,"not-a-time",true)
        assertNull(p.lastUsefulAt("target"));assertTrue(p.shouldCatchUp("target",true))
    }
    @Test fun pathRotationInvalidatesClockWithoutReclassifyingCachedCheckedAtAsNew() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("target",true,date,true)
        now=1_500;p.invalidateUsefulClock("target");assertNull(p.lastUsefulAt("target"));assertTrue(p.shouldCatchUp("target",true))
        p.accepted("target",true,"2026-10-22T08:00:00+08:00",true);assertNull(p.lastUsefulAt("target"));assertTrue(p.shouldCatchUp("target",true))
        p.accepted("target",true,"2026-10-22T00:00:01Z",true);assertEquals(1_500L,p.lastUsefulAt("target"));assertFalse(p.shouldCatchUp("target",true))
    }
    @Test fun pathRotationDoesNotInvalidateAnotherTargetProgress() {
        var now=0L;val p=ConversationUsefulProgress { now };p.accepted("other",false,null,false)
        now=500;p.invalidateUsefulClock("target");assertEquals(0L,p.lastUsefulAt("other"));assertFalse(p.shouldCatchUp("other",true))
    }

}
