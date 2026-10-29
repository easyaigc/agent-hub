package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Host-executable input-contract checks, not the production controller or native UI acceptance. */
class NativeSavedVoiceLateReturnContractTest {
    private fun contract(success: Boolean)=runBlocking {
        val engine=NativeSavedVoicePanelFixture.Engine().apply {returnSuccess=success;gate=CompletableDeferred()}
        val received=AtomicReference<String?>();val published=AtomicInteger()
        val job=launch(Dispatchers.Default) {
            val value=engine.transcribe("en-US",WavePCM.header(3200)+ByteArray(3200)) {currentCoroutineContext().ensureActive()}
            received.set(value.text)
            currentCoroutineContext().ensureActive();published.incrementAndGet()
        }
        withTimeout(5_000){while(engine.decodes==0)delay(1)}
        job.cancel();engine.gate!!.complete(Unit);job.join()
        assertTrue(engine.actualExit);assertEquals(0,published.get())
        assertEquals(if(success)1 else 0,engine.successfulReturns)
        assertEquals(if(success)NativeSavedVoicePanelFixture.Engine.LATE_TEXT else null,received.get())
    }
    @Test fun checkpointCancellationDoesNotCountAsSuccessfulReturn() {contract(false)}
    @Test fun nonCooperativeGateReallyReturnsTextBeforeTheCallerCancellationGuard() {contract(true)}
}
