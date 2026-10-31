package com.agenthub.android

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/** Exact read resources survive an unconfirmed close. No retry/timeout/detached cleanup. */
internal class NativeMigrationReadLifetime(private val wait: (Failure)->Unit={it.await()},private val onHold: (Failure)->Unit={}) {
    class Failure(val resource: Any,val owner: Thread,val primary: Throwable) {
        private val never=CountDownLatch(1)
        val waitEntered=CountDownLatch(1)
        val firstInterrupted=CountDownLatch(1)
        val twiceInterrupted=CountDownLatch(2)
        @Volatile var interruptions=0;private set
        @Volatile var latestInterruption: InterruptedException?=null;private set
        fun await(): Nothing {
            waitEntered.countDown()
            while(true)try{never.await()}catch(error: InterruptedException){
                interruptions++;latestInterruption=error
                if(interruptions==1)synchronized(primary){primary.addSuppressed(error)}
                firstInterrupted.countDown();twiceInterrupted.countDown()
            }
        }
    }
    companion object {
        private val failures=CopyOnWriteArrayList<Failure>()
        internal fun retained()=failures.toList()
    }
    private class Owned(val value: Any,val close: ()->Unit,var attempted: Boolean=false)
    private val owned=mutableListOf<Owned>()
    fun <R: Any> own(open: ()->R,close: (R)->Unit): R {
        val resource=open();owned.add(Owned(resource,{close(resource)}));return resource
    }
    fun closeOwned(original: Throwable?=null) {
        var first=original
        var closeFailed=false
        for(item in owned.asReversed())if(!item.attempted) {
            item.attempted=true
            try{item.close()}catch(error: Throwable){closeFailed=true;if(first==null)first=error else if(first!==error)first.addSuppressed(error)}
        }
        if(closeFailed)hold(this,requireNotNull(first))
    }
    fun hold(resource: Any,primary: Throwable): Nothing {
        val failure=Failure(resource,Thread.currentThread(),primary);failures.add(failure)
        try{onHold(failure)}catch(error: Throwable){if(error!==primary)primary.addSuppressed(error)}
        wait(failure)
        error("Unconfirmed native read resource cannot return")
    }
    fun <R: Any,T> use(open: ()->R,consume: (R)->T,close: (R)->Unit): T {
        val resource=open() // Register the exact actual returned handle before consuming it.
        var first: Throwable?=null
        try{return consume(resource)}catch(error: Throwable){first=error;throw error}
        finally {
            try{close(resource)}catch(error: Throwable){
                val original=first?:error
                if(original!==error)original.addSuppressed(error)
                hold(resource,original)
            }
        }
    }
}
