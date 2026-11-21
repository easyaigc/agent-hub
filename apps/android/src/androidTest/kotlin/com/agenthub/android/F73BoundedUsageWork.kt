package com.agenthub.android

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Test-only Main dispatch. A returned timeout is never a cancellation of a running callback. */
internal class F73BoundedMain(
    private val now: ()->Long,
    private val deadline: Long,
    private val queue: Queue
) {
    interface Queue { fun post(task: Runnable): Boolean; fun remove(task: Runnable); fun isMain(): Boolean }
    data class State(val label: String,val phase: String,val waitExpired: Boolean,val errorClass: String?)
    class Unknown(val observation: State): IllegalStateException("Bounded Main result unknown: ${observation.label}/${observation.phase}")
    private val closed=AtomicBoolean()
    private val pending=ConcurrentHashMap.newKeySet<Call<*>>()
    private val history=ConcurrentLinkedQueue<State>()
    private val expiredRunning=AtomicInteger()
    private inner class Call<T>(val label: String,val cutoff: Long,val action: ()->T): Runnable {
        val phase=AtomicReference("QUEUED")
        val expired=AtomicBoolean()
        val done=CountDownLatch(1)
        val value=AtomicReference<T>()
        val error=AtomicReference<Throwable>()
        fun state()=State(label,phase.get(),expired.get(),error.get()?.javaClass?.name)
        override fun run() {
            if(!phase.compareAndSet("QUEUED","RUNNING"))return
            if(closed.get()||expired.get()||now()>=cutoff) {
                phase.set("SKIPPED");finish();return
            }
            try {value.set(action());phase.set("RETURNED")}
            catch(failure: Throwable){error.set(failure);phase.set("FAILED")}
            finally {finish()}
        }
        fun finish() {
            history.add(state());while(history.size>32)history.poll()
            pending.remove(this);done.countDown()
        }
        fun expire() {
            expired.set(true)
            if(phase.compareAndSet("QUEUED","SKIPPED")) {
                try {queue.remove(this)}finally {finish()}
            }else if(phase.get()=="RUNNING")expiredRunning.incrementAndGet()
        }
    }
    fun <T> call(label: String,maximumMillis: Long=1500,action: ()->T): T {
        check(!queue.isMain()) {"No bounded wait may run on Main"}
        val wait=minOf(maximumMillis,(deadline-now()).coerceAtLeast(0))
        check(maximumMillis>0&&wait>0&&!closed.get()) {"Main admission closed: $label"}
        val call=Call(label,minOf(deadline,now()+wait),action);pending.add(call)
        val posted=try {queue.post(call)}catch(failure: Throwable){call.expire();throw failure}
        if(!posted){call.expire();throw Unknown(call.state())}
        val completed=try {call.done.await(wait,TimeUnit.MILLISECONDS)}catch(failure: InterruptedException){
            call.expire();Thread.currentThread().interrupt();throw failure
        }
        if(!completed){call.expire();throw Unknown(call.state())}
        if(call.expired.get()||call.phase.get()=="SKIPPED")throw Unknown(call.state())
        call.error.get()?.let {throw it}
        return call.value.get()
    }
    fun closePending() {closed.set(true);pending.toList().forEach {if(it.phase.get()=="QUEUED")it.expire()}}
    fun states(): List<State> = history.toList()+pending.map {it.state()}
    val inFlight get()=pending.size
    val runningTimeouts get()=expiredRunning.get()
}

/** Only this one public permission can be delegated. No shell, setting or full-set API. */
internal class F73StartPermissionLease(private val now: ()->Long,private val cutoff: Long,private val port: Port) {
    companion object {const val NAME="android.permission.START_ACTIVITIES_FROM_BACKGROUND"}
    data class Observation(val shellGranted: Boolean,val selfGranted: Boolean)
    interface Port {
        fun connect()
        fun observe(): Observation
        fun adoptSpecifiedPermission()
        fun dropIdentity()
    }
    data class State(val entered: Boolean,val connectionReturned: Boolean,val adoptionEntered: Boolean,val adoptionEnded: Boolean,
        val adoptionReturned: Boolean,val startEntered: Boolean,val startReturned: Boolean,val workerEnded: Boolean,
        val gateClosed: Boolean,val dropClaimed: Boolean,val dropReturned: Boolean,val dropEnded: Boolean,val specifiedRestored: Boolean,
        val before: Observation?,val during: Observation?,val after: Observation?,val dropErrorClass: String?)
    private val closed=AtomicBoolean();private val entered=AtomicBoolean();private val connected=AtomicBoolean()
    private val adoptionEntered=AtomicBoolean();private val adoptionEnded=AtomicBoolean();private val adoptionReturned=AtomicBoolean()
    private val startEntered=AtomicBoolean();private val startReturned=AtomicBoolean();private val workerEnded=AtomicBoolean()
    private val dropClaimed=AtomicBoolean();private val dropReturned=AtomicBoolean();private val dropEnded=AtomicBoolean();private val restored=AtomicBoolean()
    private val before=AtomicReference<Observation>();private val during=AtomicReference<Observation>();private val after=AtomicReference<Observation>()
    private val dropError=AtomicReference<String>();private val cleanupThread=AtomicReference<Thread>()
    private val gate=Any()
    private fun requireOpen(){check(!closed.get()&&now()<cutoff) {"Original start gate closed; do not replay"}}
    private fun requireState(value: Observation,self: Boolean){check(value.shellGranted&&value.selfGranted==self)}
    fun runStart(start: ()->Unit) {
        check(entered.compareAndSet(false,true)) {"Original start attempt already consumed"}
        var primary: Throwable?=null
        try {
            requireOpen();port.connect();connected.set(true);requireOpen()
            port.observe().also {before.set(it);requireState(it,false)}
            synchronized(gate){requireOpen();adoptionEntered.set(true)}
            try {port.adoptSpecifiedPermission();adoptionReturned.set(true)}finally {adoptionEnded.set(true)}
            port.observe().also {during.set(it);requireState(it,true)}
            synchronized(gate){requireOpen();check(startEntered.compareAndSet(false,true))}
            // Admission is atomic with gate closure. An admitted call may still enter Binder late;
            // closing the gate cannot withdraw it, and no blocking API runs under the gate lock.
            start();startReturned.set(true)
        }catch(failure: Throwable){primary=failure;throw failure}
        finally {
            try {release()}catch(cleanup: Throwable){if(primary==null)throw cleanup else if(primary!==cleanup)primary.addSuppressed(cleanup)}
            finally {workerEnded.set(true)}
        }
    }
    private fun release() {
        // Never race a blocked adoption. Its original worker drops the identity when it returns.
        if(!adoptionEntered.get()||!adoptionEnded.get()||!dropClaimed.compareAndSet(false,true))return
        try {
            port.dropIdentity();dropReturned.set(true)
            port.observe().also {after.set(it);requireState(it,false)};restored.set(true)
        }catch(failure: Throwable){dropError.set(failure.javaClass.name);throw failure}
        finally {dropEnded.set(true)}
    }
    fun stopAndRelease(): Thread? {
        synchronized(gate){closed.set(true)}
        if(!adoptionEntered.get()||!adoptionEnded.get()||dropClaimed.get())return cleanupThread.get()
        val task=Thread({runCatching {release()}},"f73-start-permission-release").apply {isDaemon=true}
        if(!cleanupThread.compareAndSet(null,task))return cleanupThread.get()
        task.start();return task
    }
    fun state()=State(entered.get(),connected.get(),adoptionEntered.get(),adoptionEnded.get(),adoptionReturned.get(),startEntered.get(),
        startReturned.get(),workerEnded.get(),closed.get()||now()>=cutoff,dropClaimed.get(),dropReturned.get(),dropEnded.get(),restored.get(),before.get(),during.get(),after.get(),dropError.get())
}
