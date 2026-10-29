package com.agenthub.android

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owns independent EasyWork scopes, including already-retiring routes. No parent Job is copied. */
internal class NativeEasyWorkMigrationOwners: AbstractCoroutineContextElement(Key) {
    companion object Key: CoroutineContext.Key<NativeEasyWorkMigrationOwners>
    private val lock=Any()
    private val owners=mutableSetOf<Owner>()
    private val retirement=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var sealed=false
    val stopping get()=synchronized(lock){sealed}
    inner class Owner internal constructor(private val stop: ()->Unit,private val join: suspend ()->Unit) {
        private val started=AtomicBoolean()
        private val completed=CompletableDeferred<Unit>()
        fun retire() {
            if(!started.compareAndSet(false,true))return
            try {stop()}catch(failure: Throwable){completed.completeExceptionally(failure);return}
            retirement.launch {
                try {
                    join()
                    synchronized(lock){owners.remove(this@Owner)}
                    completed.complete(Unit)
                }catch(failure: Throwable){completed.completeExceptionally(failure)}
            }
        }
        suspend fun await(){retire();completed.await()}
    }
    fun register(stop: ()->Unit,join: suspend ()->Unit): Owner=synchronized(lock) {
        check(!sealed){"EasyWork migration owner is stopping"}
        Owner(stop,join).also {owners.add(it)}
    }
    fun seal(){synchronized(lock){sealed=true}}
    suspend fun stopAndJoin() {
        val snapshot=synchronized(lock){sealed=true;owners.toList()}
        snapshot.forEach {it.retire()}
        snapshot.forEach {it.await()}
        check(synchronized(lock){owners.isEmpty()})
    }
    fun reopen(){synchronized(lock){check(owners.isEmpty());sealed=false}}
}
