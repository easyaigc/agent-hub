package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NativeReadingWriteFenceTest {
    @Test fun queuedOldOwnerCannotMutateAfterResetWhileNewOwnerCan() {
        val fence=NativeReadingWriteFence();val old=fence.capture();val storageLock=ReentrantLock()
        val attempted=CountDownLatch(1);val worker=Executors.newSingleThreadExecutor();var writes=0
        try {
            val pending=storageLock.withLock {
                val task=worker.submit<Boolean> {attempted.countDown();storageLock.withLock {old.ifCurrent { writes++;true } ?: false}}
                assertTrue(attempted.await(5,TimeUnit.SECONDS));fence.invalidate();task
            }
            assertFalse(pending.get(5,TimeUnit.SECONDS));assertEquals(0,writes)
            assertEquals(true,fence.capture().ifCurrent {writes++;true});assertEquals(1,writes)
        }finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun repeatedResetsCannotReviveAnyEarlierOwner() {
        val fence=NativeReadingWriteFence();val leases=mutableListOf<NativeReadingWriteFence.Lease>()
        repeat(20) {leases.add(fence.capture());fence.invalidate()}
        leases.forEach {assertNull(it.ifCurrent {fail("Old owner revived")})}
        assertEquals("current",fence.capture().ifCurrent {"current"})
    }
    @Test fun independentPhoneOwnerDoesNotShareResetOrLeaseState() {
        val first=NativeReadingWriteFence();val second=NativeReadingWriteFence()
        val a=first.capture();val b=second.capture();first.invalidate()
        assertNull(a.ifCurrent {true});assertEquals(true,b.ifCurrent {true})
    }
}
