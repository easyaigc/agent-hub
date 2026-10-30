package com.agenthub.android

import kotlinx.coroutines.*
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class PrivateClaimFixturePhase {
    REGISTERED,CREATING,CREATED,RESTORING,RESTORED,ACQUIRING,ACQUIRED,BODY,
    ABANDON_QUEUED,ABANDONING,ABANDONED,RETIRE_QUEUED,RETIRING,RETIRED,
    RELEASE_QUEUED,RELEASING,RELEASED,NO_SESSION,UNCONFIRMED
}

/** Strong references are the evidence. Text diagnostics are supplementary, never ownership. */
internal class PrivateClaimFixtureRecord<S: Any,L: Any,R: Any>(
    val token: PrivateClaimFixtureToken,val storage: R,val label: String,val resourceKey: String?=null,
    private val report: (PrivateClaimFixtureRecord<S,L,R>)->Unit={}
) {
    val sequence=next.incrementAndGet()
    @Volatile var session: S?=null;private set
    @Volatile var lease: L?=null;private set
    @Volatile var coordinator: Job?=null;private set
    @Volatile var phase=PrivateClaimFixturePhase.REGISTERED;private set
    @Volatile var coordinatorEntered=false;private set
    @Volatile var creationEntered=false;private set
    @Volatile var acquisitionEntered=false;private set
    @Volatile var retirementConfirmed=false;private set
    @Volatile var releaseConfirmed=false;private set
    @Volatile var cleanupConfirmed=false;private set
    @Volatile var coordinatorCompletionConfirmed=false;private set
    @Volatile var outcomeDelivered=false;private set
    @Volatile var primary: Throwable?=null;private set
    @Volatile var interruptions=0L;private set
    private var firstInterrupt: Throwable?=null
    private var latestInterrupt: Throwable?=null
    private var reportFailed=false
    private var retireEntered=false
    private var releaseEntered=false
    private val failures=mutableListOf<Pair<String,Throwable>>()
    private val completed=CountDownLatch(1)
    private val coordinatorCompleted=CountDownLatch(1)
    val coordinatorWaitEntered=CountDownLatch(1)
    companion object {private val next=AtomicLong()}

    fun attach(job: Job){check(coordinator==null);coordinator=job}
    fun coordinatorFinished() {
        check(coordinator?.isCompleted==true)
        coordinatorCompletionConfirmed=true
        if(cleanupConfirmed)PrivateClaimFixtureOwners.confirmed(this)
        coordinatorCompleted.countDown()
    }
    fun entered(){coordinatorEntered=true}
    fun creating(){creationEntered=true;phase=PrivateClaimFixturePhase.CREATING}
    fun created(value: S){check(session==null);session=value;phase=PrivateClaimFixturePhase.CREATED}
    fun restoring(){phase=PrivateClaimFixturePhase.RESTORING}
    fun restored(){phase=PrivateClaimFixturePhase.RESTORED}
    fun acquiring(){acquisitionEntered=true;phase=PrivateClaimFixturePhase.ACQUIRING}
    fun acquired(value: L){check(lease==null);lease=value;phase=PrivateClaimFixturePhase.ACQUIRED}
    fun body(){phase=PrivateClaimFixturePhase.BODY}
    fun queued(value: PrivateClaimFixturePhase){phase=value}
    fun retiring(){check(!retireEntered);retireEntered=true;phase=PrivateClaimFixturePhase.RETIRING}
    fun retired(){check(retireEntered);retirementConfirmed=true;phase=PrivateClaimFixturePhase.RETIRED}
    fun releasing(){check(retirementConfirmed&&!releaseEntered);releaseEntered=true;phase=PrivateClaimFixturePhase.RELEASING}
    fun released(){check(releaseEntered&&retirementConfirmed);releaseConfirmed=true;phase=PrivateClaimFixturePhase.RELEASED;complete()}
    fun abandoned(){check(lease==null&&!acquisitionEntered);phase=PrivateClaimFixturePhase.ABANDONED;complete()}
    fun noSession(){check(session==null&&!creationEntered);phase=PrivateClaimFixturePhase.NO_SESSION;complete()}
    private fun complete(){cleanupConfirmed=true;PrivateClaimFixtureOwners.confirmed(this);completed.countDown()}
    fun delivered(){outcomeDelivered=true}
    @Synchronized fun failure(origin: String,error: Throwable) {
        if(failures.none {it.second===error})failures.add(origin to error)
        val original=primary
        if(original==null)primary=error
        else if(original!==error&&original.suppressed.none {it===error})original.addSuppressed(error)
    }
    @Synchronized fun interrupted(error: InterruptedException) {
        interruptions++;latestInterrupt=error
        if(firstInterrupt==null){firstInterrupt=error;failure("ignored interruption",error)}
    }
    fun signal() {
        synchronized(this){if(reportFailed)return}
        try{report(this)}catch(error: Throwable){synchronized(this){reportFailed=true};failure("diagnostic failure",error)}
    }
    /** No timeout, polling, retry, release or manufactured terminal. An actual completion may wake it. */
    fun awaitConfirmed() {
        signal()
        while(!cleanupConfirmed)try{completed.await()}catch(error: InterruptedException){interrupted(error);signal()}
        signal()
    }
    /** Wait on the exact attached Job's completion, including its last synchronous IO. */
    fun awaitCoordinatorCompletion(onInterrupt: (InterruptedException)->Unit) {
        val job=coordinator?:return
        coordinatorWaitEntered.countDown()
        while(!coordinatorCompletionConfirmed||!job.isCompleted)
            try{coordinatorCompleted.await()}catch(error: InterruptedException){onInterrupt(error)}
    }
    @Synchronized fun diagnostic(): String=buildString {
        appendLine("private-claim-fixture-owner-v1 label=$label sequence=$sequence")
        appendLine("phase=$phase cleanupConfirmed=$cleanupConfirmed retirementConfirmed=$retirementConfirmed releaseConfirmed=$releaseConfirmed")
        appendLine("storageIdentity=${System.identityHashCode(storage)} sessionIdentity=${session?.let(System::identityHashCode)} leaseIdentity=${lease?.let(System::identityHashCode)}")
        appendLine("coordinatorEntered=$coordinatorEntered coordinatorCompleted=${coordinator?.isCompleted} coordinatorCompletionConfirmed=$coordinatorCompletionConfirmed outcomeDelivered=$outcomeDelivered ignoredInterruptions=$interruptions")
        appendLine("junitThreadAlive=${token.junitThread.isAlive} bodyThreadAlive=${token.bodyThread?.isAlive} guardInterruptions=${token.interruptions}")
        failures.forEach {(origin,error)->appendLine("origin=$origin");appendLine(error.stackTraceToString())}
        latestInterrupt?.takeIf {it!==firstInterrupt}?.let {appendLine("latestIgnoredInterruption");appendLine(it.stackTraceToString())}
    }
}

internal class PrivateClaimFixtureToken(val description: String,val junitThread: Thread) {
    private var closing=false
    @Volatile var bodyThread: Thread?=null;private set
    @Volatile var interruptions=0L;private set
    @Volatile var firstInterruption: InterruptedException?=null;private set
    @Volatile var failureBeforeInterruption: Throwable?=null;private set
    val records=CopyOnWriteArrayList<PrivateClaimFixtureRecord<*,*,*>>()
    @Synchronized fun attach(thread: Thread){check(bodyThread==null);bodyThread=thread}
    @Synchronized fun enroll(record: PrivateClaimFixtureRecord<*,*,*>) {check(!closing){"JUnit owner is closing"};PrivateClaimFixtureOwners.retain(record);records.add(record)}
    @Synchronized fun closeAdmission(){closing=true}
    fun interrupted(error: InterruptedException,alreadyObservedBodyFailure: Throwable?=null) {
        val first=synchronized(this){
            closing=true;interruptions++
            if(firstInterruption==null){failureBeforeInterruption=records.filter {!it.outcomeDelivered}.firstNotNullOfOrNull {it.primary}?:alreadyObservedBodyFailure;firstInterruption=error;true}else false
        }
        records.forEach {it.interrupted(error);it.signal()}
        // Request local cancellation only once; no repeat retire/release or new test body.
        if(first)bodyThread?.interrupt()
    }
    fun pending()=records.filter {!it.cleanupConfirmed}
}

/** Test-only real-object retention. No production IO/operation ledger or clearing/retry API. */
internal object PrivateClaimFixtureOwners {
    // Only the explicitly supervised JUnit body enrolls fixtures; pool threads inherit no token.
    private val current=ThreadLocal<PrivateClaimFixtureToken?>()
    private val retained=CopyOnWriteArrayList<PrivateClaimFixtureRecord<*,*,*>>()
    fun current()=requireNotNull(current.get()){"Private claim fixtures require their JUnit owner rule"}
    fun install(token: PrivateClaimFixtureToken): PrivateClaimFixtureToken?=current.get().also {current.set(token)}
    fun restore(previous: PrivateClaimFixtureToken?){if(previous==null)current.remove()else current.set(previous)}
    @Synchronized fun retain(record: PrivateClaimFixtureRecord<*,*,*>) {
        check(record.resourceKey==null||retained.none {it.resourceKey==record.resourceKey}) {"The original fixture storage owner is still retained"}
        retained.add(record)
    }
    @Synchronized fun confirmed(record: PrivateClaimFixtureRecord<*,*,*>) {
        check(record.cleanupConfirmed)
        if(record.coordinator==null||record.coordinatorCompletionConfirmed)retained.remove(record)
    }
    fun retainedRecords()=retained.toList()
}

/** Keeps the actual JUnit context and one exact body thread, including around runBlocking. */
internal class NativePrivateClaimFixtureOwnerRule: TestRule {
    override fun apply(base: Statement,description: Description)=object: Statement() {
        override fun evaluate() {
            val token=PrivateClaimFixtureToken(description.displayName,Thread.currentThread())
            val done=CountDownLatch(1)
            val bodyFailure=AtomicReference<Throwable?>()
            val body=Thread({
                val previous=PrivateClaimFixtureOwners.install(token)
                try{base.evaluate()}catch(error: Throwable){bodyFailure.set(error)}
                finally{token.closeAdmission();PrivateClaimFixtureOwners.restore(previous);done.countDown()}
            },"private-claim-fixture-body")
            token.attach(body)
            body.start()
            // No timeout/polling. This exact thread remains the instrumentation/JUnit boundary.
            while(done.count!=0L)try{done.await()}catch(error: InterruptedException){token.interrupted(error,bodyFailure.get())}
            while(body.isAlive)try{body.join()}catch(error: InterruptedException){token.interrupted(error,bodyFailure.get())}
            token.closeAdmission()
            val boundaryFailure=bodyFailure.get()
            var failure=boundaryFailure?:token.firstInterruption
            for(record in token.records) {
                if(!record.cleanupConfirmed||!record.outcomeDelivered)failure?.let {record.failure("JUnit boundary",it)}
            }
            // Existing coordinators use Default/NonCancellable, not either blocked event loop.
            token.pending().forEach {it.awaitConfirmed()}
            // A released owner can still be doing its final synchronous diagnostic IO.
            // Keep this real JUnit boundary outside Main until every exact Job actually completes.
            token.records.forEach {it.awaitCoordinatorCompletion {error->token.interrupted(error,bodyFailure.get())}}
            val undelivered=token.records.firstOrNull {!it.outcomeDelivered&&it.primary!=null}
            failure=token.failureBeforeInterruption?:token.firstInterruption?:undelivered?.primary?:failure
            // A body failure remains primary even if an interrupt also reached this boundary.
            val original=failure
            if(original!=null)for(error in listOfNotNull(boundaryFailure,token.firstInterruption))
                if(original!==error&&original.suppressed.none {it===error})original.addSuppressed(error)
            original?.let {throw it}
        }
    }
}

/** Portable coordinator for the actual fixture. Defaults preserve a live context on failure.
 * The explicit wait override is used only by fake-resource JVM tests to observe retained state.
 */
internal class PrivateClaimFixtureLifecycle<S: Any,L: Any,R: Any>(
    val record: PrivateClaimFixtureRecord<S,L,R>,private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher=Dispatchers.IO,private val work: CoroutineDispatcher=Dispatchers.Default,
    private val create: ()->S,private val restore: (S)->Unit,private val awaitRestore: suspend (S)->Unit,
    private val acquire: suspend (S)->L,private val retire: (S,L)->Unit,
    private val abandon: suspend (S)->Unit,private val release: (L)->Unit,
    private val verify: ()->Unit={},
    private val waitForConfirmation: (PrivateClaimFixtureRecord<S,L,R>)->Unit={it.awaitConfirmed()}
) {
    suspend fun <T> run(consume: suspend (L)->T): T {
        try{record.token.enroll(record)}catch(error: Throwable){record.failure("admission",error);record.noSession();record.delivered();throw error}
        val running=try {CoroutineScope(currentCoroutineContext()+work).async(start=CoroutineStart.LAZY) {
            record.entered()
            execute(consume)
        }}catch(error: Throwable){record.failure("coordinator creation",error);record.noSession();record.delivered();throw error}
        record.attach(running)
        running.invokeOnCompletion {error->
            try {
                if(error!=null)record.failure("coordinator completion",error)
                if(!record.creationEntered&&record.session==null&&!record.cleanupConfirmed)record.noSession()
            }finally{record.coordinatorFinished()}
        }
        running.start()
        return try {running.await().also {record.delivered()}}
        catch(error: Throwable) {
            if(record.cleanupConfirmed) {
                val original=record.primary
                if(original!=null){record.failure("caller return",error);record.delivered();throw original}
                record.delivered()
            }
            throw error
        }finally{running.cancel()}
    }
    private suspend fun <T> execute(consume: suspend (L)->T): T {
        var result: Result<T>?=null
        try {
            try {
                withContext(main) {
                    record.creating()
                    val session=create();record.created(session) // Exact object before a cancellable return.
                    record.restoring();restore(session)
                }
                val session=requireNotNull(record.session)
                withTimeout(30_000){withContext(main){awaitRestore(session);record.restored()}}
                withTimeout(30_000){withContext(main){
                    record.acquiring()
                    val lease=acquire(session);record.acquired(lease) // Same Main block as the actual return.
                }}
                record.body()
                result=Result.success(withContext(io){consume(requireNotNull(record.lease))})
            }catch(error: Throwable){record.failure("create/restore/acquire/body",error)}
            try{verify()}catch(error: Throwable){record.failure("post-body assertion",error)}
        }finally {
            // Creation, restore and acquire are all inside this actual finally.
            // Keep cleanup off the JUnit event loop, including after its interruption.
            withContext(NonCancellable+work) {
                try{cleanup()}catch(error: Throwable){record.failure("cleanup orchestration",error)}
                if(!record.cleanupConfirmed)withContext(io){waitForConfirmation(record)}
                check(record.cleanupConfirmed){"Unconfirmed fixture ownership cannot return"}
                withContext(io){record.signal()}
            }
        }
        record.primary?.let {throw it}
        return requireNotNull(result).getOrThrow()
    }
    private suspend fun cleanup() {
        val session=record.session
        if(session==null) {
            if(!record.creationEntered)record.noSession()
            return // Constructor entered but no exact object returned: unknown, retain storage/context.
        }
        val lease=record.lease
        if(lease!=null) {
            record.queued(PrivateClaimFixturePhase.RETIRE_QUEUED)
            try {withContext(main){record.retiring();retire(session,lease);record.retired()}}
            catch(error: Throwable){record.failure("retirement",error)}
            if(record.retirementConfirmed) {
                record.queued(PrivateClaimFixturePhase.RELEASE_QUEUED)
                try{withContext(io){record.releasing();release(lease);record.released()}}
                catch(error: Throwable){record.failure("release (not retried)",error)}
            }
        }else if(!record.acquisitionEntered) {
            record.queued(PrivateClaimFixturePhase.ABANDON_QUEUED)
            try {withContext(main){record.queued(PrivateClaimFixturePhase.ABANDONING);abandon(session);record.abandoned()}}
            catch(error: Throwable){record.failure("pre-acquisition abandonment",error)}
        }
        // acquire threw without returning a registered lease: no proof of raw owner exit; do not retry.
    }
}
