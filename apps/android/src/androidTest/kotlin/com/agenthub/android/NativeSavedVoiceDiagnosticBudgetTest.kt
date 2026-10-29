package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Pure host checks for late-read rejection and generation-before-allocation bounds. */
class NativeSavedVoiceDiagnosticBudgetTest {
    @Test fun aLateFrameworkResultCannotBeReturnedOrFollowedByAnotherRead() {
        var now=0L;val budget=NativeSavedVoiceReadBudget(2){now};var calls=0
        val value=budget.read {calls++;now=3_000_000;"late private value"}
        assertNull(value);assertEquals(1,budget.lateReadsDiscarded)
        assertNull(budget.read {calls++;"must not run"});assertEquals(1,calls)
    }
    @Test fun hugeCharSequenceIsClippedBeforeCopyOrToString() {
        var copies=0
        val huge=object: CharSequence {
            override val length=10_000_000
            override fun get(index: Int): Char {copies++;return 'x'}
            override fun subSequence(startIndex: Int,endIndex: Int): CharSequence=error("No full sequence copy")
            override fun toString(): String=error("No unbounded toString")
        }
        val budget=NativeSavedVoiceReadBudget(1000)
        assertEquals("xxxxxxxx",budget.clip(huge,8));assertEquals(8,copies);assertTrue(budget.truncated)
    }
    @Test fun byteSinkRejectsBeforeGrowingPastItsLimit() {
        val bytes=NativeSavedVoiceBoundedBytes(4);bytes.write(byteArrayOf(1,2,3))
        try {bytes.write(byteArrayOf(4,5));fail("Expected byte cap") }catch(_: IOException){}
        assertArrayEquals(byteArrayOf(1,2,3),bytes.toByteArray())
    }
}
