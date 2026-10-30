package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class NativeSavedVoiceActionTest {
    @Test fun nestedTimeoutIsFailureButDoesNotCancelTheOriginalCaller()=runBlocking {
        val result=attemptSavedVoiceAction {withTimeout(1){delay(1000)}}
        assertTrue(result is SavedVoiceAttempt.Failed&&result.timedOut)
        currentCoroutineContext().ensureActive()
        assertEquals(SavedVoiceAttempt.Complete("explicit next action"),attemptSavedVoiceAction {"explicit next action"})
    }
    @Test fun parentTimeoutIsNeverReclassifiedAsAnOperationFailure()=runBlocking {
        var returned=false
        try {withTimeout(1){attemptSavedVoiceAction {delay(1000)};returned=true};fail("Parent timeout must escape")}
        catch(_: TimeoutCancellationException){assertFalse(returned)}
    }
    @Test fun ordinaryFailureDoesNotAutomaticallyReplayWorkAndExplicitRetryRunsOnce()=runBlocking {
        var calls=0;val failure=IOException("partial archive")
        val result=attemptSavedVoiceAction {calls++;throw failure}
        assertTrue(result is SavedVoiceAttempt.Failed&&!result.timedOut);assertSame(failure,(result as SavedVoiceAttempt.Failed).error)
        delay(5);assertEquals(1,calls)
        assertEquals(SavedVoiceAttempt.Complete(2),attemptSavedVoiceAction {++calls})
    }
    @Test fun cancelledCallerRejectsLateSuccessAndLateIOExceptionAfterActualWorkerExit()=runBlocking {
        for(fails in listOf(false,true)) {
            val entered=CompletableDeferred<Unit>();val exit=CompletableDeferred<Unit>();val result=AtomicReference<SavedVoiceAttempt<String>?>()
            val worker=launch(Dispatchers.Default) {
                result.set(attemptSavedVoiceAction {
                    withContext(NonCancellable){entered.complete(Unit);exit.await()}
                    if(fails)throw IOException("late failed read")else "late model"
                })
            }
            entered.await();worker.cancel();assertFalse(worker.isCompleted);exit.complete(Unit);worker.join()
            assertTrue(worker.isCancelled);assertNull(result.get())
        }
    }
}
