package com.agenthub.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Tracks actual executor exit, including disconnect. Future cancellation is not an IO completion. */
internal class NativeMigrationIoWork: AbstractCoroutineContextElement(Key) {
    companion object Key: CoroutineContext.Key<NativeMigrationIoWork> {
        private val cancellations=Executors.newFixedThreadPool(2) {task->Thread(task,"native-io-disconnect").apply {isDaemon=true}}
        fun submit(executor: ExecutorService,context: CoroutineContext,action: ()->Unit): Future<*> {
            val unit=context[Key]?.begin(false)
            val state=AtomicInteger(0) // queued, running, exited/cancelled-before-start
            val task=try {executor.submit {
                if(!state.compareAndSet(0,1))return@submit
                try {action()}finally{state.set(2);unit?.finish()}
            }}catch(failure: Throwable){state.set(2);unit?.finish();throw failure}
            return object:Future<Any?> {
                override fun cancel(interrupt: Boolean): Boolean {
                    // A queued action cannot run after this CAS. A running action keeps its token
                    // until its own finally exits, even when Future.isDone becomes true.
                    if(state.compareAndSet(0,2))unit?.finish()
                    return task.cancel(interrupt)
                }
                override fun isCancelled()=task.isCancelled
                override fun isDone()=task.isDone
                override fun get(): Any?=task.get()
                override fun get(timeout: Long,unit: TimeUnit): Any?=task.get(timeout,unit)
            }
        }
        /** One callback keeps ownership through its outermost finally, including response close. */
        fun callback(context: CoroutineContext): ()->Unit {
            val unit=context[Key]?.begin(false)
            return {unit?.finish()}
        }
        /** Cleanup remains admissible after close and completes only on actual executor exit. */
        fun cleanup(executor: ExecutorService,context: CoroutineContext,action: ()->Unit) {
            val unit=context[Key]?.begin(true)
            try {executor.execute {
                try {action()}catch(failure: Throwable){context[Key]?.failedCleanup(failure)}finally {unit?.finish()}
            }}catch(failure: Throwable){context[Key]?.failedCleanup(failure);unit?.finish();throw failure}
        }
        /** Invoked by cancellation handlers on Main: capture and enqueue only. */
        fun cancelHttp(context: CoroutineContext,active: AtomicReference<HttpURLConnection?>,future: Future<*>) {
            val connection=active.getAndSet(null)
            val unit=context[Key]?.begin(true)
            try {cancellations.execute {try{future.cancel(true);connection?.disconnect()}finally{unit?.finish()}}}
            catch(failure: Throwable){unit?.finish();throw failure}
        }
    }
    private val lock=Any()
    private val pending=mutableSetOf<Work>()
    private var closed=false
    private var cleanupFailure: Throwable?=null
    private fun failedCleanup(failure: Throwable){synchronized(lock){if(cleanupFailure==null)cleanupFailure=failure}}
    private var idle=CompletableDeferred<Unit>().apply {complete(Unit)}
    private inner class Work {
        fun finish() {
            val completed=synchronized(lock){if(!pending.remove(this)||pending.isNotEmpty())null else idle}
            completed?.complete(Unit)
        }
    }
    private fun begin(cleanup: Boolean): Work=synchronized(lock) {
        if(closed&&!cleanup)throw CancellationException("Native IO owner is closed")
        if(pending.isEmpty())idle=CompletableDeferred()
        Work().also {pending.add(it)}
    }
    fun close(){synchronized(lock){closed=true}}
    suspend fun awaitIdle() {
        while(true) {
            val waiting=synchronized(lock){
                if(pending.isEmpty()){cleanupFailure?.let {throw IllegalStateException("Native IO cleanup did not complete",it)};null}else idle
            }?:return
            waiting.await()
        }
    }
}
