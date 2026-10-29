package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Pure decision tests, not a native AX/Home observation or tree-grant pass. */
class NativeTreeHomeReadinessRulesTest {
    private val home="com.google.android.apps.nexuslauncher"
    private fun exercise(values: List<NativeTreeHomeReadiness.Sample>,budget: Long=120): NativeTreeHomeReadiness.Result {
        var time=0L;var index=0
        return NativeTreeHomeReadiness.await(home,budget,{time},{time+=it}) {values[minOf(index++,values.lastIndex)]}
    }
    @Test fun initialNullRootsWaitForTheExactRefreshedHome() {
        val result=exercise(listOf(NativeTreeHomeReadiness.Sample(null,false),NativeTreeHomeReadiness.Sample(null,false),NativeTreeHomeReadiness.Sample(home,true)))
        assertTrue(result.ready);assertEquals(home,result.actualOwner);assertEquals(80,result.waitedMs);assertEquals(3,result.samples);assertEquals(2,result.unavailableSamples)
    }
    @Test fun absentRootStopsAtItsBoundedReadinessDeadline() {
        val result=exercise(listOf(NativeTreeHomeReadiness.Sample(null,false)))
        assertFalse(result.ready);assertEquals("ax-readiness-timeout",result.outcome);assertNull(result.actualOwner);assertEquals(120,result.waitedMs);assertEquals(4,result.samples)
    }
    @Test fun aDifferentReadyOwnerFailsImmediatelyAndCannotWaitForALaterHome() {
        for(owner in listOf("com.google.android.documentsui","com.android.systemui","com.easyai.agenthub.android.acceptance")) {
            val result=exercise(listOf(NativeTreeHomeReadiness.Sample(owner,true),NativeTreeHomeReadiness.Sample(home,true)))
            assertFalse(result.ready);assertEquals("different-owner",result.outcome);assertEquals(owner,result.actualOwner);assertEquals(0,result.waitedMs);assertEquals(1,result.samples)
        }
    }
    @Test fun aStaleOrUnreadyHomeNodeCannotPass() {
        val result=exercise(listOf(NativeTreeHomeReadiness.Sample(home,false),NativeTreeHomeReadiness.Sample(null,false),NativeTreeHomeReadiness.Sample(home,true)))
        assertTrue(result.ready);assertEquals(80,result.waitedMs);assertEquals(2,result.unavailableSamples)
    }
    @Test fun aDifferentOwnerCannotBorrowMissingWindowMetadata() {
        val result=exercise(listOf(NativeTreeHomeReadiness.Sample("com.android.systemui",false),NativeTreeHomeReadiness.Sample(home,true)))
        assertFalse(result.ready);assertEquals("different-owner",result.outcome);assertFalse(result.windowReady);assertEquals(1,result.samples)
    }
    @Test fun anOwnerReturnedAfterBudgetCannotCreateReadiness() {
        var time=0L
        val result=NativeTreeHomeReadiness.await(home,120,{time},{time+=it}) {time=121;NativeTreeHomeReadiness.Sample(home,true)}
        assertFalse(result.ready);assertEquals("ax-readiness-timeout",result.outcome);assertEquals(121,result.waitedMs)
    }
}
