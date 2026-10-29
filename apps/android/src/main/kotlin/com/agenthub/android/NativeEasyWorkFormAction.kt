package com.agenthub.android

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** The caller's Compose Job owns waiting/UI only. The Session Job and migration registry own
 * the whole authorization action, through blocking IO/finally exit, even after the caller leaves.
 */
internal object NativeEasyWorkFormAction {
    suspend fun <T> run(sessionTasks: CoroutineScope,ownerContext: CoroutineContext,action: EasyWorkConnectionActionLease,work: suspend ()->T): T {
        val owners=requireNotNull(ownerContext[NativeEasyWorkMigrationOwners])
        val running=sessionTasks.async(ownerContext.easyWorkOwnerContext()+action,start=CoroutineStart.LAZY) {action.checkpoint();work()}
        val owner=try {owners.register({running.cancel()},{running.join()})}
            catch(failure: Throwable){running.cancel();throw failure}
        running.invokeOnCompletion {owner.retire()}
        running.start()
        return try {running.await()}finally {running.cancel()}
    }
}
