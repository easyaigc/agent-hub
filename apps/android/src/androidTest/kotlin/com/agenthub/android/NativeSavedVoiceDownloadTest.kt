package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory transport boundaries. No official models, network, native decoder or device. */
class NativeSavedVoiceDownloadTest {
    private val original=ByteArray(193){(it%67).toByte()}
    private fun hash(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    private suspend fun copy(data: ByteArray,out: ByteArrayOutputStream=ByteArrayOutputStream()) {
        copySavedVoiceArchive(ByteArrayInputStream(data),out,original.size.toLong(),hash(original)) {currentCoroutineContext().ensureActive()}
    }
    @Test fun exactPinnedArchiveReturnsOnlyAfterAllBytesAndChecksumMatch()=runBlocking {
        val out=ByteArrayOutputStream();copy(original,out);assertArrayEquals(original,out.toByteArray())
    }
    @Test fun partialOrOversizedArchiveCannotReturnSuccess()=runBlocking {
        for(value in listOf(original.copyOf(50),original+byteArrayOf(9))) {
            val out=ByteArrayOutputStream()
            try {copy(value,out);fail("Incomplete/oversize must fail") }catch(_: IllegalArgumentException) {}
            assertTrue(out.size()<=original.size)
        }
    }
    @Test fun completeWrongChecksumCannotReturnSuccess()=runBlocking {
        try {copy(original.copyOf().apply {this[100]=99});fail("Wrong checksum must fail") }catch(_: IllegalArgumentException) {}
    }
    @Test fun retryUsesFreshFullInputAndDoesNotAppendFailedPartialBytes()=runBlocking {
        val failed=ByteArrayOutputStream()
        try {copy(original.copyOf(17),failed);fail("Partial must fail") }catch(_: IllegalArgumentException) {}
        val retry=ByteArrayOutputStream();copy(original,retry)
        assertEquals(17,failed.size());assertArrayEquals(original,retry.toByteArray())
    }
    @Test fun zeroByteReadFailsInsteadOfSpinning()=runBlocking {
        val zero=object: InputStream() {override fun read()=0;override fun read(b: ByteArray,off: Int,len: Int)=0}
        try {copySavedVoiceArchive(zero,ByteArrayOutputStream(),193,hash(original)) {};fail("No progress must fail") }catch(_: IllegalArgumentException) {}
    }
    @Test fun cancellationOrOwnerFailureAfterReadPreventsTheNextWrite()=runBlocking {
        for(cancel in listOf(false,true)) {
            var checkpoints=0;val out=ByteArrayOutputStream()
            val failure=if(cancel)CancellationException("cancelled")else IllegalStateException("owner changed")
            try {
                copySavedVoiceArchive(ByteArrayInputStream(original),out,193,hash(original)) {if(++checkpoints==2)throw failure}
                fail("Returned bytes must not bypass authority check")
            }catch(t: Exception) {assertSame(failure,t)}
            assertEquals(0,out.size())
        }
    }
    @Test fun explicitCancelAbortsBlockingReadAndWaitsForActualCleanup()=runBlocking {
        val entered=CountDownLatch(1);val transportCancelled=CountDownLatch(1);val exit=CountDownLatch(1)
        val resourceClosed=AtomicBoolean(false);val published=AtomicBoolean(false)
        val worker=launch(Dispatchers.IO) {
            ownSavedVoiceDownload({transportCancelled.countDown()}) {
                try {entered.countDown();check(transportCancelled.await(5,TimeUnit.SECONDS));throw IOException("transport aborted")}
                finally {check(exit.await(5,TimeUnit.SECONDS));resourceClosed.set(true)}
            }
            published.set(true)
        }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));worker.cancel()
            assertTrue(transportCancelled.await(5,TimeUnit.SECONDS));assertFalse(worker.isCompleted);assertFalse(resourceClosed.get())
        }finally {exit.countDown();worker.join()}
        assertTrue(resourceClosed.get());assertFalse(published.get());assertTrue(worker.isCancelled)
    }
    @Test fun nestedTimeoutKeepsCancellationCauseAfterTransportIOExceptionAndRealExit()=runBlocking {
        val cancelled=CountDownLatch(1);val closed=AtomicBoolean(false)
        try {
            withContext(Dispatchers.IO) {withTimeout(50) {
                ownSavedVoiceDownload({cancelled.countDown()}) {
                    try {check(cancelled.await(5,TimeUnit.SECONDS));throw IOException("aborted read")}
                    finally {closed.set(true)}
                }
            }}
            fail("Timeout must not return success")
        }catch(_: TimeoutCancellationException) {assertTrue(closed.get())}
    }
    @Test fun normalCompletionAndFailureBothReleaseTheCancelWatcher()=runBlocking {
        var closed=0
        assertEquals("ok",ownSavedVoiceDownload({closed++}) {"ok"});assertEquals(1,closed)
        val error=IOException("read failed")
        try {ownSavedVoiceDownload({closed++}) {throw error};fail("Original IO failure must propagate")}
        catch(t: IOException){assertSame(error,t)}
        assertEquals(2,closed)
    }
}
