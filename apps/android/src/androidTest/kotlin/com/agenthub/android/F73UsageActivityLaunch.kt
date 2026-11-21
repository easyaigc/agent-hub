package com.agenthub.android

import android.app.UiAutomation
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** F73's original admitted Permit only. No Q04 case, new permit, shell launch or profile deletion. */
internal class F73UsageActivityLaunch(private val context: Context,private val permit: Q02RealReadInstallation.Permit) {
    private val began=SystemClock.elapsedRealtime()
    private val workDeadline=minOf(permit.expiresAtElapsed,began+210_000)
    private val cleanupDeadline=minOf(began+235_000,workDeadline+20_000)
    private val handler=Handler(Looper.getMainLooper())
    private val queue=object: F73BoundedMain.Queue {
        override fun post(task: Runnable)=handler.post(task)
        override fun remove(task: Runnable){handler.removeCallbacks(task)}
        override fun isMain()=Looper.myLooper()===Looper.getMainLooper()
    }
    private val work=F73BoundedMain(SystemClock::elapsedRealtime,workDeadline,queue)
    private val cleanupMain=F73BoundedMain(SystemClock::elapsedRealtime,cleanupDeadline,queue)
    private val profile=AtomicReference<Q02RealReadInstallation.Profile>()
    private val activity=AtomicReference<Q02RealReadActivity>()
    private val registered=AtomicBoolean();private val prepared=AtomicBoolean();private val started=AtomicBoolean()
    private val cleanupStarted=AtomicBoolean();private val finishRequested=AtomicBoolean();private val ambiguousActivity=AtomicBoolean()
    private val resumed=CountDownLatch(1);private val destroyed=CountDownLatch(1)
    private val stage=AtomicReference("NOT_OBSERVED");private val startError=AtomicReference<Throwable>()
    private val closeError=AtomicReference<String>();private val closeReturned=AtomicBoolean();private val closeFinished=CountDownLatch(1)
    private val lastProfileProof=AtomicReference<JSONObject>()
    @Volatile private var caller: Thread?=null
    @Volatile private var permission: F73StartPermissionLease?=null
    @Volatile private var closeJob: Job?=null
    private val expected=Intent(context,Q02RealReadActivity::class.java).setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Application Context; never clear somebody else's task.
    private val callback=ActivityLifecycleCallback { value,observed ->
        if(value.javaClass==Q02RealReadActivity::class.java) {
            val own=profile.get()
            if(own!=null&&runCatching {(value as Q02RealReadActivity).profile===own}.getOrDefault(false)) {
                val actual=value as Q02RealReadActivity
                if(!activity.compareAndSet(null,actual)&&activity.get()!==actual)ambiguousActivity.set(true)
                if(activity.get()===actual) {
                    stage.set(observed.name)
                    if(observed==Stage.RESUMED)resumed.countDown()
                    if(observed==Stage.DESTROYED)destroyed.countDown()
                }
            }
        }
    }
    fun <T> onMain(label: String,action: ()->T): T = work.call(label,action=action)
    fun preparedProfile(): Q02RealReadInstallation.Profile?=profile.get()
    private fun remaining(deadline: Long,maximum: Long)=minOf(maximum,(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
    private fun await(latch: CountDownLatch,deadline: Long,maximum: Long): Boolean =
        latch.await(remaining(deadline,maximum),TimeUnit.MILLISECONDS)
    private fun join(thread: Thread?,deadline: Long,maximum: Long) {
        val wait=remaining(deadline,maximum)
        if(thread!=null&&wait>0)thread.join(wait) // join(0) is forbidden: it waits forever.
    }
    fun prepare(): Q02RealReadInstallation.Profile {
        check(prepared.compareAndSet(false,true)) {"Preparation cannot be replayed"}
        return work.call("prepare-original-profile",3000) {
            val monitor=ActivityLifecycleMonitorRegistry.getInstance()
            require(Stage.values().filter {it!=Stage.DESTROYED}.all {s->monitor.getActivitiesInStage(s).none {it is MainActivity}})
            monitor.addLifecycleCallback(callback);registered.set(true)
            Q02RealReadInstallation.prepare(context,permit).also {profile.set(it);lastProfileProof.set(it.proof())}
        }
    }
    private fun assertIntent() {
        check(expected.action==Intent.ACTION_MAIN&&expected.component==ComponentName(context,Q02RealReadActivity::class.java))
        check(expected.data==null&&expected.clipData==null&&expected.selector==null&&expected.extras==null)
    }
    fun startAndAwait() {
        check(started.compareAndSet(false,true)&&profile.get()!=null&&registered.get()&&!cleanupStarted.get())
        assertIntent()
        val lease=F73StartPermissionLease(SystemClock::elapsedRealtime,minOf(workDeadline,SystemClock.elapsedRealtime()+20_000),
            object: F73StartPermissionLease.Port {
                private var automation: UiAutomation?=null
                override fun connect(){automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)}
                override fun observe()=F73StartPermissionLease.Observation(
                    context.packageManager.checkPermission(F73StartPermissionLease.NAME,"com.android.shell")==PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(F73StartPermissionLease.NAME)==PackageManager.PERMISSION_GRANTED)
                override fun adoptSpecifiedPermission(){checkNotNull(automation).adoptShellPermissionIdentity(F73StartPermissionLease.NAME)}
                override fun dropIdentity(){checkNotNull(automation).dropShellPermissionIdentity()}
            })
        permission=lease
        caller=Thread({try {lease.runStart {assertIntent();context.startActivity(expected)}}catch(failure: Throwable){startError.set(failure)}},
            "f73-original-start-request").apply {isDaemon=true;start()}
        val observed=await(resumed,workDeadline,20_000)
        if(!observed)lease.stopAndRelease()
        join(caller,workDeadline,1000)
        startError.get()?.let {throw it}
        check(observed&&caller?.isAlive==false&&lease.state().specifiedRestored&&!ambiguousActivity.get()) {
            "Original start result UNKNOWN or permission restoration incomplete; never replay"
        }
        onMain("confirm-original-resumed-profile") {
            val own=checkNotNull(profile.get());val actual=checkNotNull(activity.get())
            check(actual.profile===own&&own.created&&!own.destroyed&&!actual.isDestroyed)
            check(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(actual)==Stage.RESUMED)
        }
    }
    private fun readProfile(gate: F73BoundedMain): JSONObject? = gate.call("capture-owned-profile",1000) {
        profile.get()?.proof()?.also {lastProfileProof.set(it)}
    }
    private fun mainStates(gate: F73BoundedMain)=JSONArray(gate.states().map {
        JSONObject().put("label",it.label).put("phase",it.phase).put("waitExpired",it.waitExpired).put("errorClass",it.errorClass)
    })
    private fun permissionProof(): JSONObject {
        val s=permission?.state()?:return JSONObject().put("permissionAttempted",false)
        fun observation(v: F73StartPermissionLease.Observation?)=v?.let {
            JSONObject().put("shellGranted",it.shellGranted).put("selfGranted",it.selfGranted)
        }?:JSONObject.NULL
        return JSONObject().put("permission",F73StartPermissionLease.NAME).put("adoptCallArguments",JSONArray(listOf(F73StartPermissionLease.NAME)))
            .put("readbackScope","specified-permission-only-public-api").put("fullAdoptedPermissionSetReadback","unavailable-in-public-sdk")
            .put("fullAdoptedPermissionSet",JSONObject.NULL).put("connected",s.connectionReturned).put("adoptEntered",s.adoptionEntered)
            .put("adoptEnded",s.adoptionEnded).put("adoptReturned",s.adoptionReturned).put("startEntered",s.startEntered)
            .put("startEnteredMeaning","atomic admission before Context.startActivity, not Binder acknowledgement")
            .put("startReturned",s.startReturned).put("workerEnded",s.workerEnded).put("newStartGateClosed",s.gateClosed)
            .put("dropClaimed",s.dropClaimed).put("dropReturned",s.dropReturned).put("dropEnded",s.dropEnded)
            .put("specifiedPermissionRestored",s.specifiedRestored).put("dropErrorClass",s.dropErrorClass)
            .put("before",observation(s.before)).put("during",observation(s.during)).put("after",observation(s.after))
    }
    fun snapshot(): JSONObject {
        var error: String?=null
        try {readProfile(if(cleanupStarted.get())cleanupMain else work)}catch(failure: Throwable){error=failure.javaClass.name}
        return JSONObject().put("kind","f73-owned-launch-state-v1").put("run",permit.run).put("leaseId",permit.leaseId)
            .put("observedElapsed",SystemClock.elapsedRealtime()).put("controllerBeganElapsed",began)
            .put("workDeadlineElapsed",workDeadline).put("cleanupDeadlineElapsed",cleanupDeadline)
            .put("profile",lastProfileProof.get()?:JSONObject.NULL).put("profilePreparedObserved",profile.get()!=null)
            .put("ownedActivityObserved",activity.get()!=null).put("finishRequested",finishRequested.get())
            .put("registered",registered.get()).put("lastOwnedStage",stage.get()).put("ambiguousOwnedActivity",ambiguousActivity.get())
            .put("startWorkerAlive",caller?.isAlive?:false).put("startErrorClass",startError.get()?.javaClass?.name)
            .put("startRequestCancelled",false).put("timeoutIsNotCancellation",true).put("mainObservationComplete",error==null).put("mainReadErrorClass",error)
            .put("workMain",mainStates(work)).put("cleanupMain",mainStates(cleanupMain)).put("temporaryPermission",permissionProof())
    }
    fun cleanup(): JSONObject {
        check(cleanupStarted.compareAndSet(false,true)) {"Cleanup must not be replayed"}
        val interrupted=Thread.interrupted() // Permit bounded cleanup, then restore the original interrupt.
        val errors=JSONArray()
        fun attempt(phase: String,block: ()->Unit) {try {block()}catch(failure: Throwable){errors.put(JSONObject().put("phase",phase).put("class",failure.javaClass.name))}}
        var permissionCleanup: Thread?=null
        try {
            attempt("close-start-gate") {permissionCleanup=permission?.stopAndRelease()}
            attempt("withdraw-queued-work-callbacks") {work.closePending()}
            attempt("join-permission-cleanup") {join(permissionCleanup,cleanupDeadline,1000)}
            attempt("join-original-start") {join(caller,cleanupDeadline,1000)}
            attempt("finish-only-owned-activity") {
                cleanupMain.call("finish-only-owned-activity",1500) {
                    val own=profile.get();val actual=activity.get()
                    if(own!=null&&actual!=null) {
                        check(actual.profile===own&&!ambiguousActivity.get())
                        if(!actual.isDestroyed&&!actual.isFinishing&&finishRequested.compareAndSet(false,true))actual.finish()
                    }
                }
            }
            if(activity.get()!=null)attempt("await-owned-destroyed") {check(await(destroyed,cleanupDeadline,8000))}
            attempt("close-original-writer-after-destroyed") {
                val own=profile.get()
                if(own!=null&&destroyed.count==0L) {
                    cleanupMain.call("schedule-original-profile-close",1500) {
                        val actual=checkNotNull(activity.get());check(actual.profile===own&&own.destroyed&&actual.isDestroyed)
                        check(closeJob==null)
                        val parent=SupervisorJob()
                        val job=CoroutineScope(parent+Dispatchers.Main.immediate).launch(start=CoroutineStart.LAZY) {
                            Q02RealReadInstallation.close(own);closeReturned.set(true);lastProfileProof.set(own.proof())
                        }
                        closeJob=job
                        job.invokeOnCompletion {cause->if(cause!=null)closeError.set(cause.javaClass.name);closeFinished.countDown();parent.cancel()}
                        job.start()
                    }
                    // No cancel/interrupt of an entered close: writer drain must actually finish.
                    check(await(closeFinished,cleanupDeadline,6000)) {"Original writer close is UNKNOWN"}
                }
            }
            attempt("final-owned-profile-proof") {readProfile(cleanupMain)}
        }finally {
            attempt("remove-lifecycle-callback") {cleanupMain.call("unregister-owned-callback",1000) {
                if(registered.get()){ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);registered.set(false)}
            }}
            cleanupMain.closePending()
            if(interrupted)Thread.currentThread().interrupt()
        }
        val permissionState=permission?.state()
        val permissionClosed=permissionState==null||permissionState.workerEnded&&
            (!permissionState.adoptionEntered||permissionState.specifiedRestored)&&permissionCleanup?.isAlive!=true
        val proof=lastProfileProof.get()
        val physical=profile.get()!=null&&activity.get()!=null&&destroyed.count==0L&&closeReturned.get()&&closeJob?.isCompleted==true&&
            closeError.get()==null&&proof?.opt("destroyed")==true&&proof?.opt("closed")==true&&!ambiguousActivity.get()
        val complete=physical&&permissionClosed&&caller?.isAlive!=true&&!registered.get()&&work.inFlight==0&&cleanupMain.inFlight==0&&errors.length()==0
        return (proof?.let {JSONObject(it.toString())}?:JSONObject().put("profileProofUnavailable",true))
            .put("observedElapsed",SystemClock.elapsedRealtime()).put("controllerBeganElapsed",began)
            .put("workDeadlineElapsed",workDeadline).put("cleanupDeadlineElapsed",cleanupDeadline)
            .put("profilePreparedObserved",profile.get()!=null).put("ownedActivityObserved",activity.get()!=null)
            .put("lastOwnedStage",stage.get()).put("ambiguousOwnedActivity",ambiguousActivity.get()).put("finishRequested",finishRequested.get())
            .put("ownedResourcesClosed",complete).put("destroyedObserved",destroyed.count==0L).put("writerCloseReturned",closeReturned.get())
            .put("closeJobCompleted",closeJob?.isCompleted?:false).put("closeErrorClass",closeError.get())
            .put("lifecycleCallbackRemoved",!registered.get()).put("startWorkerAlive",caller?.isAlive?:false)
            .put("startErrorClass",startError.get()?.javaClass?.name)
            .put("temporaryPermission",permissionProof()).put("cleanupErrors",errors).put("workMain",mainStates(work)).put("cleanupMain",mainStates(cleanupMain))
            .put("timeoutIsNotCancellation",true).put("startRequestCancelled",false).put("profileDeletionAttempted",false)
            .put("retentionReason","Controller preserves private evidence and reconciles the exact binding/profile; no automatic replay or cleanup")
    }
}
