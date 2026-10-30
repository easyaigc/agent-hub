package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Host boundary proof of the same deadline engine used by the native sampler; no native pass. */
class NativeSavedVoiceStartupBudgetTest {
    @Test fun orientationAloneCannotHideWrongIdentityOrAnyActualFontSource() {
        assertTrue(nativeSavedVoiceStartupMismatches(true,false,2f,2f,2f,2,2).isEmpty())
        assertEquals(listOf("activity-orientation"),nativeSavedVoiceStartupMismatches(true,false,2f,2f,2f,1,2))
        assertEquals(listOf("activity-identity","activity-ended","os-font-scale","target-font-scale","activity-font-scale"),
            nativeSavedVoiceStartupMismatches(false,true,1f,1f,1f,2,2))
    }
    @Test fun nonfiniteFontValuesCannotSatisfyTheOriginalFiniteTolerance() {
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,1.9f,2.1f)) {
            assertEquals(listOf("os-font-scale"),nativeSavedVoiceStartupMismatches(true,false,value,2f,2f,2,2))
            assertEquals(listOf("target-font-scale"),nativeSavedVoiceStartupMismatches(true,false,2f,value,2f,2,2))
            assertEquals(listOf("activity-font-scale"),nativeSavedVoiceStartupMismatches(true,false,2f,2f,value,2,2))
        }
    }
    @Test fun delayedConfigurationCanBecomeReadyInsideOneSharedBudget() {
        var time=0L;var reads=0;val passedRemaining=mutableListOf<Long>()
        val r=awaitNativeSavedVoiceStartup({time},{left->passedRemaining.add(left);reads++;time+=10;reads==3},{it},{time+=it})
        assertTrue(r.ready);assertEquals(130L,r.elapsedMs);assertEquals(listOf(5000L,4940L,4880L),passedRemaining)
        assertEquals(3,r.samples.size)
    }
    @Test fun persistentMismatchDoesNotResetOrExtendFiveSecondBudget() {
        var time=0L
        val r=awaitNativeSavedVoiceStartup({time},{false},{it},{time+=it})
        assertFalse(r.ready);assertEquals("deadline-exceeded",r.reason);assertEquals(5000L,r.elapsedMs);assertEquals(100,r.samples.size)
    }
    @Test fun matchingDataAtOrAfterDeadlineCannotPass() {
        for(finish in listOf(5000L,5001L)) {
            var time=0L
            val r=awaitNativeSavedVoiceStartup({time},{time=finish;true},{it},{fail("Must not pause after a late read")})
            assertFalse(r.ready);assertEquals("deadline-exceeded",r.reason);assertFalse(r.samples.single().withinDeadline)
        }
    }
    @Test fun sampleWaitReceivesOnlyRemainingTimeAndNoRetryAfterUnavailableRead() {
        var time=0L;var reads=0
        val r=awaitNativeSavedVoiceStartup<Boolean>({time},{remaining->
            reads++;if(reads==1){time+=4975;false}else {assertEquals(24L,remaining);time+=remaining;null}
        },{it},{time+=minOf(it,1)})
        assertFalse(r.ready);assertEquals(2,reads);assertEquals(5000L,r.elapsedMs)
    }
    @Test fun sampleCapPreventsAStalledClockFromSpinningForever() {
        val r=awaitNativeSavedVoiceStartup({0L},{false},{it},{})
        assertFalse(r.ready);assertEquals("sample-limit",r.reason);assertEquals(101,r.samples.size)
    }
    @Test fun alreadyMatchingSampleNeedsNoSleepAndReadExceptionsPropagate() {
        val r=awaitNativeSavedVoiceStartup({0L},{true},{it},{fail("No artificial settle delay")})
        assertTrue(r.ready);assertEquals(1,r.samples.size)
        val original=IllegalStateException("sample failed")
        try {awaitNativeSavedVoiceStartup<Boolean>({0L},{throw original},{it},{}) ;fail("Original read failure must propagate")}
        catch(t: IllegalStateException) {assertSame(original,t)}
    }
}
