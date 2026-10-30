package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual executor/owner boundaries with controlled connections; no HTTP or platform service. */
class NativeCodexIoOwnerTest {
    private fun await(latch: CountDownLatch) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(true)try {check(latch.await((deadline-System.nanoTime()).coerceAtLeast(1),TimeUnit.NANOSECONDS));return}
            catch(_: InterruptedException) {check(System.nanoTime()<deadline)}
    }
    private class Connection(val close: ()->Unit): HttpURLConnection(URL("https://example.invalid")) {
        override fun disconnect()=close()
        override fun connect()=error("No network")
        override fun usingProxy()=false
    }
    @Test fun queuedCancellationNeverRunsAndRetiresOnlyAfterCancellationTask()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();val occupied=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            executor.submit {occupied.countDown();await(release)};await(occupied)
            val io=NativeMigrationIoWork();val calls=AtomicInteger()
            val future=NativeMigrationIoWork.submit(executor,io){calls.incrementAndGet()}
            NativeMigrationIoWork.cancelHttp(io,AtomicReference<HttpURLConnection?>(),future)
            withTimeout(2000){io.awaitIdle()};assertEquals(0,calls.get());assertTrue(future.isCancelled)
        }finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun cancelledFutureAndExitedRequestDoNotReleaseBlockedDisconnect()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();val entered=CountDownLatch(1);val bodyExit=CountDownLatch(1)
        val closeEntered=CountDownLatch(1);val closeExit=CountDownLatch(1);val closes=AtomicInteger();val closeThread=AtomicReference<Thread>()
        val connection=Connection {closes.incrementAndGet();closeThread.set(Thread.currentThread());closeEntered.countDown();await(closeExit)}
        val active=AtomicReference<HttpURLConnection?>(connection);val io=NativeMigrationIoWork();val caller=Thread.currentThread()
        try {
            val future=NativeMigrationIoWork.submit(executor,io){try{entered.countDown();await(bodyExit)}finally{NativeMigrationIoWork.finishHttp(io,active)}}
            await(entered);NativeMigrationIoWork.cancelHttp(io,active,future);await(closeEntered)
            assertNotSame(caller,closeThread.get());assertTrue(future.isDone)
            val idle=async {io.awaitIdle()};yield();assertFalse(idle.isCompleted)
            bodyExit.countDown();executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));yield()
            assertFalse(idle.isCompleted);assertEquals(1,closes.get())
            closeExit.countDown();withTimeout(2000){idle.await()};assertEquals(1,closes.get())
        }finally {bodyExit.countDown();closeExit.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun requestFinallyKeepsOwnershipUntilActualCloseReturns()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();val entered=CountDownLatch(1);val exit=CountDownLatch(1);val io=NativeMigrationIoWork()
        val active=AtomicReference<HttpURLConnection?>(Connection {entered.countDown();await(exit)})
        try {
            NativeMigrationIoWork.submit(executor,io){NativeMigrationIoWork.finishHttp(io,active)};await(entered)
            val idle=async {io.awaitIdle()};yield();assertFalse(idle.isCompleted)
            exit.countDown();withTimeout(2000){idle.await()};assertNull(active.get())
        }finally {exit.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun failedDisconnectCannotBeReportedAsIdleSuccess()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();val io=NativeMigrationIoWork()
        try {
            val active=AtomicReference<HttpURLConnection?>(Connection {error("controlled close failure")})
            NativeMigrationIoWork.submit(executor,io){NativeMigrationIoWork.finishHttp(io,active)}.get(2,TimeUnit.SECONDS)
            assertTrue(runCatching {withTimeout(2000){io.awaitIdle()}}.exceptionOrNull() is IllegalStateException)
        }finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun cancellationCloseFailureAlsoBlocksSuccessfulIdle()=runBlocking {
        val io=NativeMigrationIoWork();val closed=CountDownLatch(1)
        val future=java.util.concurrent.FutureTask<Unit>({Unit})
        NativeMigrationIoWork.cancelHttp(io,AtomicReference<HttpURLConnection?>(Connection {closed.countDown();error("controlled cancellation failure")}),future)
        await(closed)
        assertTrue(runCatching {withTimeout(2000){io.awaitIdle()}}.exceptionOrNull() is IllegalStateException)
    }
    @Test fun rejectedExecutorHasNoAcceptedWorkToWaitFor()=runBlocking {
        val executor=Executors.newSingleThreadExecutor();executor.shutdown();val io=NativeMigrationIoWork();val calls=AtomicInteger()
        assertTrue(runCatching {NativeMigrationIoWork.submit(executor,io){calls.incrementAndGet()}}.isFailure)
        withTimeout(2000){io.awaitIdle()};assertEquals(0,calls.get())
    }
    @Test fun formCancellationKeepsSessionAndIoOwnershipAcrossRequestGaps()=runBlocking {
        val io=NativeMigrationIoWork();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val exit=CompletableDeferred<Unit>()
        try {
            val caller=launch {NativeCodexFormAction.run(scope,io,{}) {
                assertSame(io,currentCoroutineContext()[NativeMigrationIoWork])
                withContext(NonCancellable){entered.complete(Unit);exit.await()}
            }}
            entered.await();caller.cancelAndJoin();assertTrue(scope.coroutineContext[Job]!!.children.any())
            val idle=async {io.awaitIdle()};yield();assertFalse(idle.isCompleted)
            exit.complete(Unit);withTimeout(2000){idle.await()}
        }finally {exit.complete(Unit);scope.coroutineContext[Job]!!.cancelAndJoin()}
    }
    @Test fun changedAdmissionRefusesBeforeWorkAndDoesNotLeakOwner()=runBlocking {
        val io=NativeMigrationIoWork();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        try {
            assertTrue(runCatching {NativeCodexFormAction.run(scope,io,{error("retired or changed target")}){calls++}}.isFailure)
            assertEquals(0,calls);withTimeout(2000){io.awaitIdle()}
        }finally {scope.coroutineContext[Job]!!.cancelAndJoin()}
    }
}
