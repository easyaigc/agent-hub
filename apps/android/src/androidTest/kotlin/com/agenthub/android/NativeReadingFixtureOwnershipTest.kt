package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Host-safe auxiliary ownership checks; these are not encrypted-storage or Native acceptance. */
class NativeReadingFixtureOwnershipTest {
    private fun queued(lock: ReentrantLock,thread: Thread) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!lock.hasQueuedThread(thread)) {check(System.nanoTime()<deadline);Thread.yield()}
    }
    private fun joinForAuxiliaryCleanup(worker: NativeReadingEpochWorkers.Worker) {
        worker.thread?.join(5_000)
        assertTrue(worker.executor.awaitTermination(5,TimeUnit.SECONDS));assertFalse(worker.thread?.isAlive==true)
    }
    @Test fun originalWorkerFailureSurvivesConfirmedPhysicalExit() {
        val owners=NativeReadingEpochWorkers("aux-failure");val worker=owners.create();val original=IOException("owned action")
        var observed: Throwable?=null
        try {worker.submit {throw original}.get(5,TimeUnit.SECONDS)}catch(error: ExecutionException){observed=error}
        finally {worker.awaitExit(observed)}
        assertSame(original,checkNotNull(observed).cause);assertTrue(observed.suppressed.isEmpty())
        assertTrue(owners.allTerminated);assertTrue(worker.executor.isTerminated);assertFalse(checkNotNull(worker.thread).isAlive)
    }
    @Test fun nonInterruptibleWaitRetainsExactLiveHandleAndOriginalFailureWithoutRetirementProof() {
        val owners=NativeReadingEpochWorkers("aux-non-interruptible",50);val worker=owners.create()
        val lock=ReentrantLock(true);val entered=CountDownLatch(1);val original=IOException("scenario first")
        lock.lock()
        try {
            worker.submit {entered.countDown();lock.lock();try {check(!Thread.currentThread().isInterrupted)}finally {lock.unlock()}}
            assertTrue(entered.await(5,TimeUnit.SECONDS));queued(lock,checkNotNull(worker.thread))
            worker.awaitExit(original)
            assertEquals(1,original.suppressed.size);assertSame(worker.exitFailure,original.suppressed.single())
            assertFalse(owners.allTerminated);assertTrue(checkNotNull(worker.thread).isAlive)
            assertTrue(lock.hasQueuedThread(worker.thread));assertFalse(checkNotNull(worker.future).isCancelled)
            assertSame(worker,owners.owned.single());assertEquals(worker.thread?.id,worker.record()["threadId"])
            assertEquals(false,worker.record()["executorTerminated"]);assertEquals(true,worker.record()["threadAlive"])
        }finally {lock.unlock();joinForAuxiliaryCleanup(worker)}
        // The auxiliary test releases its own controlled lock to reclaim its worker. No second
        // awaitExit is used, and later actual exit cannot retroactively confirm the failed wait.
        assertFalse(owners.allTerminated);assertTrue(worker.exitAttempted);assertNotNull(worker.exitNanos)
    }
    @Test fun interruptedObserverDoesNotInterruptOwnedActionOrReplaceOriginalFailure() {
        val owners=NativeReadingEpochWorkers("aux-interrupted",50);val worker=owners.create()
        val lock=ReentrantLock(true);val original=IOException("original before teardown");lock.lock()
        try {
            worker.submit {lock.lock();try {check(!Thread.currentThread().isInterrupted)}finally {lock.unlock()}}
            queued(lock,checkNotNull(worker.thread));Thread.currentThread().interrupt()
            worker.awaitExit(original)
            assertTrue(original.suppressed.single() is InterruptedException);assertFalse(owners.allTerminated)
            assertFalse(checkNotNull(worker.future).isCancelled);assertTrue(checkNotNull(worker.thread).isAlive)
        }finally {Thread.interrupted();lock.unlock();joinForAuxiliaryCleanup(worker)}
    }
    @Test fun failedDurableIntentPreventsDeletionAndCannotBeReplayed() {
        val root=Files.createTempDirectory("p7-cleanup-intent-");val alias=Files.createFile(root.resolve("synthetic-alias"))
        val original=IOException("journal unavailable");val cleanup=NativeReadingFixtureCleanup {_,_,_->throw original}
        try {
            try {cleanup.step("alias","removed") {Files.delete(alias)};fail("Expected journal failure")}
            catch(error: IOException){assertSame(original,error)}
            assertTrue(Files.exists(alias));assertEquals("not-attempted",cleanup.snapshot()["alias"])
            try {cleanup.step("alias","removed") {Files.delete(alias)};fail("Cleanup replay must fail")}
            catch(_: IllegalStateException){}
            assertTrue(Files.exists(alias))
        }finally {Files.deleteIfExists(alias);Files.delete(root)}
    }
    @Test fun failedCompletionReceiptReportsRemovedAliasAndUnknownIntentWithoutReplaying() {
        val root=Files.createTempDirectory("p7-cleanup-completion-");val alias=Files.createFile(root.resolve("synthetic-alias"))
        val durable=mutableListOf<Pair<String,Map<String,String>>>()
        val original=IOException("completion journal unavailable")
        val cleanup=NativeReadingFixtureCleanup {_,phase,states->
            if(phase=="alias-completed")throw original
            durable.add(phase to states)
        }
        try {
            try {cleanup.step("alias","removed") {Files.delete(alias)};fail("Expected receipt failure")}
            catch(error: IOException){assertSame(original,error)}
            assertFalse(Files.exists(alias));assertEquals("removed",cleanup.snapshot()["alias"])
            assertEquals("alias-intent",durable.single().first);assertEquals("unknown",durable.single().second["alias"])
            assertEquals("not-written",cleanup.snapshot()["result"])
        }finally {Files.deleteIfExists(alias);Files.delete(root)}
    }
    @Test fun mutationFailureLeavesUnknownAliasAndPreservesItsOriginalCause() {
        val original=IOException("provider outcome unknown");val durable=mutableListOf<Map<String,String>>()
        val cleanup=NativeReadingFixtureCleanup {_,_,states->durable.add(states)}
        try {cleanup.step("alias","removed") {throw original};fail("Expected provider failure")}
        catch(error: IOException){assertSame(original,error)}
        assertEquals("unknown",cleanup.snapshot()["alias"]);assertEquals("unknown",durable.single()["alias"])
        assertEquals("not-written",cleanup.snapshot()["result"])
    }
}
