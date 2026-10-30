package com.agenthub.android

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** Session owns the whole action, including gaps between requests; Compose owns waiting only. */
internal object NativeCodexFormAction {
    suspend fun <T> run(sessionTasks: CoroutineScope,ownerContext: CoroutineContext,checkpoint: ()->Unit,work: suspend ()->T): T {
        requireNotNull(ownerContext[NativeMigrationIoWork])
        checkpoint()
        val finished=NativeMigrationIoWork.callback(ownerContext)
        val running=try {sessionTasks.async(ownerContext.minusKey(Job),start=CoroutineStart.LAZY) {checkpoint();work()}}
            catch(failure: Throwable){finished();throw failure}
        running.invokeOnCompletion {finished()}
        running.start()
        return try{running.await()}finally{running.cancel()}
    }
}
