package com.agenthub.android

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Test ownership only. A completed/cancelled Future is never a physical-thread exit proof. */
internal class NativeReadingEpochWorkers(val fixtureId: String,private val exitMillis: Long=5_000) {
    val owned=mutableListOf<Worker>()
    val allTerminated get()=owned.all {it.terminated}
    fun create(): Worker=Worker("p7-reading-$fixtureId-${owned.size+1}",exitMillis).also {owned.add(it)}

    class Worker(val name: String,private val exitMillis: Long) {
        @Volatile var thread: Thread?=null;private set
        val executor=Executors.newSingleThreadExecutor {task->Thread(task,name).also {thread=it}}
        var future: Future<*>?=null;private set
        var exitAttempted=false;private set
        var terminated=false;private set
        var exitFailure: Throwable?=null;private set
        var exitNanos: Long?=null;private set
        fun submit(action: ()->Unit): Future<*> {
            check(future==null&&!exitAttempted)
            return executor.submit(action).also {future=it}
        }
        fun awaitExit(primary: Throwable?=null) {
            // Never cancel/interrupt the action or replay an unknown exit attempt.
            if(exitAttempted) {exitFailure?.let {if(primary==null)throw it else if(primary!==it)primary.addSuppressed(it)};return}
            exitAttempted=true;val started=System.nanoTime()
            try {
                executor.shutdown()
                val deadline=started+TimeUnit.MILLISECONDS.toNanos(exitMillis)
                check(executor.awaitTermination(exitMillis,TimeUnit.MILLISECONDS)) {"Executor exit unknown: $name"}
                thread?.let {actual->
                    val remaining=deadline-System.nanoTime()
                    if(actual.isAlive&&remaining>0)TimeUnit.NANOSECONDS.timedJoin(actual,remaining)
                    if(actual.isAlive)throw TimeoutException("Physical thread exit unknown: $name")
                }
                terminated=executor.isTerminated&&thread?.isAlive!=true
                check(terminated) {"Physical worker exit unknown: $name"}
            }catch(error: Throwable) {
                exitFailure=error
                if(primary==null)throw error else if(primary!==error)primary.addSuppressed(error)
            }finally {exitNanos=System.nanoTime()-started}
        }
        fun record(): Map<String,Any?> = linkedMapOf(
            "name" to name,"threadId" to thread?.id,"threadIdentity" to thread?.let(System::identityHashCode),
            "threadState" to thread?.state?.name,"threadAlive" to thread?.isAlive,
            "executorIdentity" to System.identityHashCode(executor),"executorTerminated" to executor.isTerminated,
            "futureIdentity" to future?.let(System::identityHashCode),"futureDone" to future?.isDone,
            "futureCancelled" to future?.isCancelled,"exitAttempted" to exitAttempted,"terminated" to terminated,
            "exitBudgetMillis" to exitMillis,"exitNanos" to exitNanos,"exitFailure" to exitFailure?.javaClass?.name)
    }
}

/** Immutable intent precedes each destructive step. Missing completion means unknown outcome.
 * A receipt failure does not retry a deletion, and cannot reclassify Native assertions as passed. */
internal class NativeReadingFixtureCleanup(private val persist: (Int,String,Map<String,String>)->Unit) {
    private var sequence=0
    private val attempted=mutableSetOf<String>()
    private val states=linkedMapOf("cipher" to "not-attempted","directory" to "not-attempted","alias" to "not-attempted","result" to "not-written")
    var phase="not-started";private set
    fun snapshot(): Map<String,String> = states.toMap()
    fun step(resource: String,completed: String,action: ()->Unit) {
        check(resource in states&&attempted.add(resource)) {"Cleanup step cannot be replayed: $resource"}
        phase="$resource-intent"
        persist(++sequence,phase,states+ (resource to "unknown"))
        states[resource]="unknown";phase="$resource-attempted"
        action()
        states[resource]=completed;phase="$resource-completed"
        persist(++sequence,phase,snapshot())
    }
}
