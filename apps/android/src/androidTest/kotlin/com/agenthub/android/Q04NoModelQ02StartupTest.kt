package com.agenthub.android

import android.app.UiAutomation
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** No-model, no-pairing startup observation. A new runner only; the actual App is unchanged.
 * Root must independently seal this one diagnostic and verify fresh UID/process/old-data protection.
 * It does not read a Host key, use a provider port, touch d5/b725, or prove business controls.
 */
@RunWith(AndroidJUnit4::class)
class Q04NoModelQ02StartupTest {
    @get:Rule val limit = Timeout.seconds(40)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private fun remaining(maximum: Long) = minOf(maximum, (deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
    private fun await(latch: CountDownLatch, maximum: Long): Boolean = latch.await(remaining(maximum), TimeUnit.MILLISECONDS)
    private fun joinIfTime(thread: Thread?, maximum: Long) {
        val wait=remaining(maximum);if(thread!=null && wait>0)thread.join(wait) // join(0) would wait forever.
    }
    private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString()==it) }
    private fun hex(value: String) = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A timeout stops waiting; it cannot cancel an already executing Main callback. No Main waits on test. */
    private fun onMainBounded(maximum: Long=1000, action: ()->JSONObject): JSONObject {
        val done=CountDownLatch(1);val result=AtomicReference<JSONObject>();val admissible=AtomicBoolean(true)
        val task=Runnable {
            if(!admissible.get() || SystemClock.elapsedRealtime()>=deadline)return@Runnable
            try {result.set(action())}catch(failure: Throwable) {result.set(JSONObject().put("callbackErrorClass",failure.javaClass.name))}
            finally {done.countDown()}
        }
        if(!main.post(task))return JSONObject().put("mainResponded",false).put("reason","handler-post-rejected")
        val completed=await(done,maximum)
        if(!completed){admissible.set(false);main.removeCallbacks(task)}
        return JSONObject().put("mainResponded",completed).put("callbackMayStillBeInFlight",!completed)
            .put("observedAtElapsedMs",SystemClock.elapsedRealtime()).put("value",if(completed)result.get() else JSONObject.NULL)
    }
    private fun frames(thread: Thread?) = JSONObject().put("threadId",thread?.id ?: JSONObject.NULL)
        .put("alive",thread?.isAlive ?: JSONObject.NULL).put("state",thread?.state?.name ?: JSONObject.NULL)
        .put("frames",JSONArray(thread?.stackTrace.orEmpty().take(96).map {
            JSONObject().put("class",it.className.take(256)).put("method",it.methodName.take(256))
                .put("file",it.fileName?.take(128) ?: JSONObject.NULL).put("line",it.lineNumber)
        }))
    private fun environment(): JSONObject {
        val result=JSONObject().put("observedAtElapsedMs",SystemClock.elapsedRealtime())
        try {val keyguard=context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            result.put("keyguardLocked",keyguard.isKeyguardLocked).put("deviceLocked",keyguard.isDeviceLocked)
                .put("keyguardSecure",keyguard.isKeyguardSecure).put("deviceSecure",keyguard.isDeviceSecure)
        }catch(failure: Throwable){result.put("keyguardUnknown",true).put("keyguardErrorClass",failure.javaClass.name)}
        try {result.put("interactive",(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)}
        catch(failure: Throwable){result.put("interactive",JSONObject.NULL).put("powerErrorClass",failure.javaClass.name)}
        return result // No wake/unlock/settings, other Apps, notifications, screen or screenshot reads.
    }
    private fun intentFilter(intent: Intent, expected: Intent) = JSONObject().put("filterEqualsExpected",intent.filterEquals(expected))
        .put("action",intent.action ?: JSONObject.NULL).put("component",intent.component?.flattenToString() ?: JSONObject.NULL)
        .put("package",intent.`package` ?: JSONObject.NULL).put("type",intent.type ?: JSONObject.NULL)
        .put("categories",JSONArray(intent.categories.orEmpty().sorted())).put("flags",intent.flags)
        .put("dataIsNull",intent.data==null).put("dataSha256",intent.dataString?.let {sha(it.toByteArray(Charsets.UTF_8))} ?: JSONObject.NULL)
        .put("extrasPresent",intent.extras!=null) // No arbitrary extras values or URI body is exported.
    private fun noHostMainSnapshot(snapshot: JSONObject): Boolean {
        if(snapshot.opt("mainResponded")!=true)return false
        val value=snapshot.optJSONObject("value") ?: return false
        val profile=value.optJSONObject("profile") ?: return false
        return profile.opt("pairingAttempted")==false && value.opt("realClientCount")==0 && value.opt("operationCount")==0
    }
    private fun profileFlags(profile: Q02RealReadInstallation.Profile) = JSONObject().put("run",profile.permit.run)
        .put("created",profile.created).put("destroyed",profile.destroyed).put("closed",profile.closed)
        .put("claimed",profile.claimed).put("pairingAttempted",profile.pairingAttempted)
        .put("storageConstructed",profile.storage!=null).put("sessionConstructed",profile.session!=null)
        .put("existingOwnedResume",profile.existing!=null)

    /** One public named-permission lease. No hidden getter/reflection or persistent settings. */
    private inner class StartPermissionLease(private val startCutoff: Long) {
        private val automation=AtomicReference<UiAutomation>()
        private val stopRequested=AtomicBoolean(false);private val entered=AtomicBoolean(false)
        private val adoptionEntered=AtomicBoolean(false);private val adoptionEnded=AtomicBoolean(false)
        private val adoptionReturned=AtomicBoolean(false);private val dropClaimed=AtomicBoolean(false)
        private val dropReturned=AtomicBoolean(false);val restored=AtomicBoolean(false)
        private val before=AtomicReference<JSONObject>();private val during=AtomicReference<JSONObject>();private val after=AtomicReference<JSONObject>()
        private val dropError=AtomicReference<String>();private val dropDone=CountDownLatch(1)
        private fun open()=!stopRequested.get()&&SystemClock.elapsedRealtime()<minOf(startCutoff,deadline)
        private fun specifiedPermission()=JSONObject().put("observedAtElapsedMs",SystemClock.elapsedRealtime())
            .put("shellPermission",context.packageManager.checkPermission(START_PERMISSION,"com.android.shell"))
            .put("selfPermission",context.checkSelfPermission(START_PERMISSION))
        private fun requireState(value: JSONObject,self: Int) {
            require(value.getInt("shellPermission")==PackageManager.PERMISSION_GRANTED&&value.getInt("selfPermission")==self)
        }
        fun runOriginalStart(start: ()->Unit) {
            check(entered.compareAndSet(false,true));var original: Throwable?=null
            try {
                check(open()) {"Original startup permission deadline closed"}
                val actual=checkNotNull(InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY))
                automation.set(actual);check(open()) {"Late permission connection must not start an Activity"}
                specifiedPermission().also {before.set(it);requireState(it,PackageManager.PERMISSION_DENIED)}
                check(open());adoptionEntered.set(true)
                try {actual.adoptShellPermissionIdentity(START_PERMISSION);adoptionReturned.set(true)}
                finally {adoptionEnded.set(true)}
                specifiedPermission().also {during.set(it);requireState(it,PackageManager.PERMISSION_GRANTED)}
                check(open()) {"No late Activity start after permission preparation"}
                start() // Exactly the existing explicit same-UID Q02 Context.startActivity boundary.
            }catch(failure: Throwable){original=failure;throw failure}
            finally {
                try {release()}catch(cleanup: Throwable) {
                    val failure=original
                    if(failure!=null) {if(failure!==cleanup)runCatching {failure.addSuppressed(cleanup)}} else throw cleanup
                }
            }
        }
        private fun release() {
            // An in-flight adoption has not ended: its original worker must release when it returns.
            if(!adoptionEntered.get()||!adoptionEnded.get()||!dropClaimed.compareAndSet(false,true))return
            try {
                checkNotNull(automation.get()).dropShellPermissionIdentity();dropReturned.set(true)
                specifiedPermission().also {after.set(it);requireState(it,PackageManager.PERMISSION_DENIED)}
                restored.set(true)
            }catch(failure: Throwable){dropError.set(failure.javaClass.name);throw failure}
            finally {dropDone.countDown()}
        }
        fun stopAndRelease(): Thread? {
            stopRequested.set(true)
            if(!adoptionEntered.get()||!adoptionEnded.get()||dropClaimed.get())return null
            return Thread({runCatching {release()}},"q04-no-model-permission-release").apply {isDaemon=true;start()}
        }
        fun snapshot()=JSONObject().put("run",RUN).put("leaseId",LEASE).put("operationId",OPERATION).put("pid",android.os.Process.myPid())
            .put("permission",START_PERMISSION).put("readbackScope","specified-permission-only-public-api")
            .put("fullAdoptedPermissionSetReadback","unavailable-in-public-sdk").put("fullAdoptedPermissionSet",JSONObject.NULL)
            .put("adoptCallArguments",JSONArray(listOf(START_PERMISSION))).put("adoptCallEntered",adoptionEntered.get()).put("adoptCallReturned",adoptionReturned.get())
            .put("adoptCallEnded",adoptionEnded.get()).put("dropCallClaimed",dropClaimed.get()).put("dropCallReturned",dropReturned.get())
            .put("dropCallFinished",dropDone.count==0L).put("specifiedPermissionRestored",restored.get()).put("dropErrorClass",dropError.get() ?: JSONObject.NULL)
            .put("before",before.get() ?: JSONObject.NULL).put("during",during.get() ?: JSONObject.NULL).put("after",after.get() ?: JSONObject.NULL)
            .put("newStartGateClosed",stopRequested.get()||SystemClock.elapsedRealtime()>=minOf(startCutoff,deadline))
            .put("persistentPermissionOrAppOpsSettingsMutation",false)
    }

    /** Same actual Main/Session lifecycle, but never Instrumentation.startActivitySync/queueIdle. */
    private inner class LifecycleLaunch(private val profile: Q02RealReadInstallation.Profile, val expected: Intent) {
        val resumed=CountDownLatch(1);val destroyed=CountDownLatch(1)
        val activity=AtomicReference<Q02RealReadActivity>();val stage=AtomicReference<String>("not-observed")
        val events=ConcurrentLinkedQueue<String>();val requestReturned=AtomicBoolean(false)
        val requestEntered=AtomicBoolean(false);val requestError=AtomicReference<String>()
        val allowRequest=AtomicBoolean(true);val retired=AtomicBoolean(false);val registered=AtomicBoolean(false)
        var caller: Thread?=null;private set
        private val callback=ActivityLifecycleCallback { value, observed ->
            if(retired.get() || value.javaClass!=Q02RealReadActivity::class.java)return@ActivityLifecycleCallback
            val own=runCatching {(value as Q02RealReadActivity).profile===profile}.getOrDefault(false)
            if(!own)return@ActivityLifecycleCallback // No foreign profile content, ID, intent or Session read.
            val actual=value as Q02RealReadActivity
            activity.compareAndSet(null,actual);stage.set(observed.name)
            if(events.size<32)events.add(JSONObject().put("stage",observed.name).put("elapsedMs",SystemClock.elapsedRealtime()).toString())
            if(observed==Stage.RESUMED)resumed.countDown()
            if(observed==Stage.DESTROYED)destroyed.countDown()
        }
        fun register()=onMainBounded {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);registered.set(true);JSONObject().put("registered",true)}
        var permission: StartPermissionLease?=null;private set
        fun start() {
            check(registered.get() && caller==null)
            val lease=StartPermissionLease(minOf(deadline,SystemClock.elapsedRealtime()+20000));permission=lease
            caller=Thread({
                if(!allowRequest.compareAndSet(true,false) || SystemClock.elapsedRealtime()>=deadline)return@Thread
                try {lease.runOriginalStart {requestEntered.set(true);context.startActivity(expected);requestReturned.set(true)}}
                catch(failure: Throwable){requestError.set(failure.javaClass.name)}
            },"q04-no-model-start-request").apply {isDaemon=true;start()}
        }
        fun snapshot()=onMainBounded {
            val actual=activity.get()
            val value=JSONObject().put("profile",profileFlags(profile)).put("lastObservedStage",stage.get())
                .put("activityObserved",actual!=null).put("events",JSONArray(events.toList().map(::JSONObject)))
            if(actual!=null && actual.profile===profile) {
                value.put("lifecycleStage",ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(actual).name)
                    .put("isFinishing",actual.isFinishing).put("isDestroyed",actual.isDestroyed)
                    .put("intentFilter",intentFilter(actual.intent,expected))
                val state=runCatching {actual.session}.getOrNull()
                value.put("sessionAvailable",state!=null).put("restoring",state?.restoring ?: JSONObject.NULL)
                    .put("realClientCount",state?.clients?.count {!it.isDemo} ?: JSONObject.NULL)
                    .put("operationCount",state?.operationRecords?.size ?: JSONObject.NULL)
            }
            value
        }
        fun requestFinish()=onMainBounded {
            val actual=checkNotNull(activity.get());check(actual.profile===profile)
            if(!actual.isDestroyed && !actual.isFinishing)actual.finish()
            JSONObject().put("finishRequested",true).put("destroyedNow",actual.isDestroyed)
        }
        fun retire(): JSONObject {
            allowRequest.set(false) // Does not withdraw a startActivity request already inside Binder.
            val result=onMainBounded {
                if(registered.getAndSet(false))ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                JSONObject().put("callbackUnregistered",true)
            }
            retired.set(true)
            return result
        }
    }

    @Test fun boundedRealQ02StartupOnly() {
        val began=SystemClock.elapsedRealtime();deadline=began+36000 // Soft aggregate, plus the outer 40s JUnit bound.
        check(Looper.myLooper()!==Looper.getMainLooper())
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("q04StartupDiagReady")=="1" && args.getString("q04StartupDiagRun")==RUN)
        require(args.keySet().none {it.startsWith("w3")})
        val evidence=Evidence(context)
        val prepared=AtomicReference<Q02RealReadInstallation.Profile>();val closeError=AtomicReference<String>()
        var profile: Q02RealReadInstallation.Profile?=null;var launch: LifecycleLaunch?=null
        var closeWorker: Thread?=null;var primary: Throwable?=null
        var admitted=false;var resumed=false;var destroyed=false;var writerClosed=false;var startupNoHostConfirmed=false
        try {
            val review=evidence.readReview(hex(checkNotNull(args.getString("q04StartupDiagReviewSha256"))))
            closed(review,REVIEW_KEYS)
            require(review.getInt("version")==2 && review.getString("kind")=="root-q04-no-model-q02-startup-review-v2")
            require(review.getBoolean("enabled")&&review.getBoolean("rootConfirmed")&&review.getBoolean("executionAuthorized")&&!review.getBoolean("preparedOnly"))
            for(flag in listOf("noModels","noHostOperations","noPairing","noProviderInjection","noScreenshots","rootDevicePreflightReviewed"))require(review.getBoolean(flag))
            require(review.getBoolean("temporarySinglePermissionDelegationApproved")&&review.getString("temporaryPermissionName")==START_PERMISSION)
            require(review.getString("diagnosticRun")==RUN&&review.getString("leaseId")==LEASE&&review.getString("operationId")==OPERATION)
            require(review.getString("acceptancePackage")==PACKAGE&&review.getString("appSha256")==APP)
            require(review.getInt("api")==33&&android.os.Build.VERSION.SDK_INT==33)
            val now=SystemClock.elapsedRealtime();require(review.getLong("notBeforeElapsedMs")<=now&&review.getLong("expiresAtElapsedMs") in now+60000..now+300000)
            closed(review.getJSONObject("target"),setOf("agentId","hostId","workspaceId","runtimeId"))
            val target=checkNotNull(HostReadCodec.target(review.getJSONObject("target")))
            require(target.agentId=="codex"&&target.hostId==HOST&&target.workspaceId==HOST&&target.runtimeId=="desktop-runtime")
            require(review.getString("endpoint")==ENDPOINT&&review.getString("certificateSha256")==CERTIFICATE)
            require(review.getString("localMappingSha256")==sha(("no-host-operations:"+RUN).toByteArray(Charsets.UTF_8)))
            val permit=Q02RealReadInstallation.Permit(uuid(RUN),uuid(LEASE),hex(review.getString("installationAndroidIdSha256")),APP,
                hex(review.getString("runnerSha256")),hex(review.getString("signerSha256")),33,review.getLong("expiresAtElapsedMs"),
                uuid(review.getString("controlHostId")),review.getString("localMappingSha256"),target,ENDPOINT,listOf(ENDPOINT),CERTIFICATE,
                sha(ByteArray(0)),null) // Required Permit plumbing; no pairing input or Host key exists in this method.
            admitted=true
            evidence.publish("probe-once.json",JSONObject().put("kind","q04-no-model-startup-attempt-v1").put("run",RUN).put("operationId",OPERATION)
                .put("pid",android.os.Process.myPid()).put("startedAtElapsedMs",began).put("noModels",true).put("noHostOperations",true))
            evidence.publish("environment-before.json",environment())
            val prepareResult=onMainBounded(3000) {
                Q02RealReadInstallation.prepare(context,permit).also {prepared.set(it)}
                profileFlags(checkNotNull(prepared.get()))
            }
            evidence.publish("prepare-result.json",prepareResult)
            profile=prepared.get();require(prepareResult.getBoolean("mainResponded")&&profile!=null) {"Owned preparation did not return within the soft bound"}
            val own=checkNotNull(profile)
            val expected=Intent(context,Q02RealReadActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            evidence.publish("launch-intent.json",intentFilter(expected,expected))
            val active=LifecycleLaunch(own,expected);launch=active
            val registered=active.register();evidence.publish("registration-result.json",registered)
            require(registered.getBoolean("mainResponded")&&active.registered.get())
            active.start();resumed=await(active.resumed,20000)
            val startupMain=active.snapshot();startupNoHostConfirmed=noHostMainSnapshot(startupMain)
            evidence.publish("startup-observation.json",JSONObject().put("resumedObserved",resumed).put("elapsedMs",SystemClock.elapsedRealtime()-began)
                .put("mainStack",frames(Looper.getMainLooper().thread)).put("startWorkerStack",frames(active.caller))
                .put("mainSnapshot",startupMain).put("noHostMainSnapshotConfirmed",startupNoHostConfirmed).put("environment",environment()).put("startRequestReturned",active.requestReturned.get())
                .put("startRequestEntered",active.requestEntered.get()).put("startErrorClass",active.requestError.get() ?: JSONObject.NULL)
                .put("timeoutIsNotCancellation",true))
            if(resumed) {
                evidence.publish("finish-request.json",active.requestFinish())
                destroyed=await(active.destroyed,10000)
                if(destroyed) {
                    val finished=CountDownLatch(1)
                    closeWorker=Thread({
                        try {runBlocking {withTimeout(2500) {withContext(Dispatchers.Main) {Q02RealReadInstallation.close(own)}}}}
                        catch(failure: Throwable){closeError.set(failure.javaClass.name)}
                        finally {finished.countDown()}
                    },"q04-no-model-owned-close").apply {isDaemon=true;start()}
                    writerClosed=await(finished,3000)&&closeError.get()==null
                }
            }
            joinIfTime(active.caller,100);joinIfTime(closeWorker,100)
            require(resumed&&destroyed&&writerClosed&&startupNoHostConfirmed&&active.requestReturned.get()&&active.caller?.isAlive!=true&&closeWorker?.isAlive!=true&&active.permission?.restored?.get()==true) {"Bounded startup or owned cleanup incomplete; retain original profile"}
        } catch(failure: Throwable) {primary=failure;throw failure}
        finally {
            // No synchronous Main wait, worker interrupt, force-stop or profile deletion.
            var cleanupFailure: Throwable?=null
            fun remember(failure: Throwable) {
                val previous=cleanupFailure
                if(previous==null)cleanupFailure=failure else if(previous!==failure)runCatching {previous.addSuppressed(failure)}
            }
            if(admitted) {
            val current=launch;var finalValue: JSONObject?=null;var permissionCleanup: Thread?=null
            try {permissionCleanup=current?.permission?.stopAndRelease();joinIfTime(permissionCleanup,1000)}catch(failure: Throwable){remember(failure)}
            val permissionState=current?.permission?.snapshot() ?: JSONObject().put("specifiedPermissionRestored",false).put("permissionAttemptObserved",false)
            try {evidence.publish("startup-permission-state.json",permissionState)}catch(failure: Throwable){remember(failure)}
            try {
                finalValue=JSONObject().put("run",RUN).put("elapsedMs",SystemClock.elapsedRealtime()-began)
                    .put("resumedObserved",resumed).put("destroyedObserved",destroyed).put("writerCloseReturned",writerClosed)
                    .put("noHostMainSnapshotConfirmed",startupNoHostConfirmed).put("startRequestReturned",current?.requestReturned?.get() ?: JSONObject.NULL)
                    .put("mainStack",frames(Looper.getMainLooper().thread)).put("startWorkerStack",frames(current?.caller))
                    .put("closeWorkerStack",frames(closeWorker)).put("mainSnapshot",current?.snapshot() ?: onMainBounded {(profile ?: prepared.get())?.let(::profileFlags) ?: JSONObject().put("profile","unknown")})
                    .put("environment",environment()).put("lateStartWorkerStillLive",current?.caller?.isAlive ?: false)
                    .put("lateCloseWorkerStillLive",closeWorker?.isAlive ?: false).put("originalFailureClass",primary?.javaClass?.name ?: JSONObject.NULL).put("closeErrorClass",closeError.get() ?: JSONObject.NULL)
                    .put("profilePreparedObserved",profile!=null||prepared.get()!=null)
                    .put("profileRetentionRequired",true).put("profileDeletionAttempted",false).put("startRequestCancelled",false)
                    .put("independentUidProcessReadStillRequired",true).put("businessControlsAccepted",false)
                    .put("noModels",true).put("noHostOperations",true)
                    .put("temporaryPermission",permissionState).put("latePermissionCleanupWorkerStillLive",permissionCleanup?.isAlive ?: false)
            }catch(failure: Throwable){remember(failure)}
            var removed: JSONObject?=null
            try {removed=current?.retire()}catch(failure: Throwable){remember(failure)}
            try {finalValue?.let {it.put("callbackRemoval",removed ?: JSONObject.NULL);evidence.publish("probe-final.json",it)}}
            catch(failure: Throwable){remember(failure)}
            if(primary==null) {
                val unregistered=removed?.opt("mainResponded")==true &&
                    removed?.optJSONObject("value")?.opt("callbackUnregistered")==true
                if(!unregistered)remember(IllegalStateException("Lifecycle callback removal not confirmed; retain original profile"))
                if(current?.permission?.restored?.get()!=true||permissionCleanup?.isAlive==true)remember(IllegalStateException("Temporary specified permission restoration not confirmed"))
            }
            }
            try {evidence.close()}catch(failure: Throwable){remember(failure)}
            val original=primary;val cleanup=cleanupFailure
            if(cleanup!=null) {
                if(original!=null && original!==cleanup)runCatching {original.addSuppressed(cleanup)} else if(original==null)throw cleanup
            }
        }
    }

    /** The Root-created fixed stage is pinned. Only fixed redacted evidence leaves are O_EXCL. */
    private class Evidence(context: Context) {
        private val folder=File(context.filesDir,"acceptance/q04-no-model-q02-startup-v1/"+RUN)
        private val held: ParcelFileDescriptor
        private val device: Long;private val inode: Long
        init {
            require(BuildConfig.DEBUG&&context.packageName==PACKAGE&&context.applicationInfo.uid==android.os.Process.myUid())
            var part=context.filesDir.canonicalFile
            for(name in listOf("acceptance","q04-no-model-q02-startup-v1",RUN)) {
                part=File(part,name);require(part.canonicalFile==part.absoluteFile)
                val s=Os.lstat(part.path);require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==android.os.Process.myUid()&&!java.nio.file.Files.isSymbolicLink(part.toPath()))
            }
            val fd=Os.open(folder.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            val handle=try {ParcelFileDescriptor.dup(fd)}finally {Os.close(fd)}
            val s=try {Os.fstat(handle.fileDescriptor).also {require(OsConstants.S_ISDIR(it.st_mode)&&it.st_mode and 0xfff==0x1c0)}}
                catch(failure: Throwable){runCatching {handle.close()};throw failure}
            held=handle;device=s.st_dev;inode=s.st_ino
        }
        private fun pinned() {
            val a=Os.fstat(held.fileDescriptor);val b=Os.lstat(folder.path)
            require(a.st_dev==device&&a.st_ino==inode&&b.st_dev==device&&b.st_ino==inode&&a.st_uid==android.os.Process.myUid()&&b.st_uid==a.st_uid)
            require(a.st_mode and 0xfff==0x1c0&&b.st_mode and 0xfff==0x1c0&&OsConstants.S_ISDIR(b.st_mode))
        }
        fun readReview(expected: String): JSONObject {
            pinned();val name="/proc/self/fd/${held.fd}/root-review.json"
            val fd=Os.open(name,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0)
            val bytes=try {
                val a=Os.fstat(fd);require(OsConstants.S_ISREG(a.st_mode)&&a.st_uid==android.os.Process.myUid()&&a.st_nlink==1L&&a.st_mode and 0xfff==0x180&&a.st_size in 1..65536)
                val out=ByteArray(a.st_size.toInt());var offset=0;while(offset<out.size){val n=Os.read(fd,out,offset,out.size-offset);require(n>0);offset+=n}
                require(Os.read(fd,ByteArray(1),0,1)==0)
                val b=Os.fstat(fd);val c=Os.lstat(name)
                require(a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_ctime==b.st_ctime&&c.st_dev==a.st_dev&&c.st_ino==a.st_ino&&c.st_size==a.st_size)
                out
            }finally {Os.close(fd)}
            pinned();require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}==expected)
            return JSONObject(bytes.toString(Charsets.UTF_8))
        }
        fun publish(name: String,value: JSONObject) {
            require(name in setOf("probe-once.json","environment-before.json","prepare-result.json","launch-intent.json","registration-result.json","startup-observation.json","finish-request.json","probe-final.json","startup-permission-state.json"))
            pinned();val bytes=value.toString().toByteArray(Charsets.UTF_8);require(bytes.size in 1..65536)
            val fd=Os.open("/proc/self/fd/${held.fd}/$name",OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
            try {
                val st=Os.fstat(fd);require(OsConstants.S_ISREG(st.st_mode)&&st.st_uid==android.os.Process.myUid()&&st.st_nlink==1L&&st.st_mode and 0xfff==0x180)
                var offset=0;while(offset<bytes.size){val n=Os.write(fd,bytes,offset,bytes.size-offset);require(n>0);offset+=n};Os.fsync(fd)
            }finally {Os.close(fd)}
            Os.fsync(held.fileDescriptor);pinned()
        }
        fun close()=held.close()
    }
    companion object {
        private const val RUN="1a587a56-f862-45ee-bd74-042d84f80a37"
        private const val LEASE="4c2c1a17-47ad-4c2a-bc56-e4c6cd9dd541"
        private const val OPERATION="a9933edb-4e71-48fb-a2ce-e4e4bb01ae41"
        private const val START_PERMISSION="android.permission.START_ACTIVITIES_FROM_BACKGROUND"
        private const val PACKAGE="com.easyai.agenthub.android.acceptance"
        private const val APP="32d51a1135134deda3fbefb2bf259ac21f20a344259430b7fde2a21fc219b289"
        private const val HOST="13c81542-48e3-4df2-ab46-d45357b631a2"
        private const val ENDPOINT="https://192.168.2.172:56319"
        private const val CERTIFICATE="b473b0d99f01d5b75e36dc5242379777ca0f39af0f5d772c52ac5a3d74e3616e"
        private val REVIEW_KEYS=setOf("version","kind","enabled","rootConfirmed","executionAuthorized","preparedOnly","diagnosticRun","leaseId","operationId","noModels","noHostOperations","noPairing","noProviderInjection","noScreenshots","rootDevicePreflightReviewed","acceptancePackage","appSha256","runnerSha256","signerSha256","api","installationAndroidIdSha256","controlHostId","target","endpoint","certificateSha256","localMappingSha256","notBeforeElapsedMs","expiresAtElapsedMs","temporarySinglePermissionDelegationApproved","temporaryPermissionName")
        private fun closed(value: JSONObject,keys: Set<String>)=require(value.keys().asSequence().toSet()==keys)
    }
}
