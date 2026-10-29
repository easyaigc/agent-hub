package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor

/** Real Composition disposal/Activity pause, Session bridge, coordinator+Keystore write and OkHttp
 * callback/cleanup. Invokes the exact Page-used bridge directly: no real pairing button/peer/model claim.
 */
class NativeMigrationEasyWorkFormLifecycleTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var previous: MainActivity.AcceptanceSessionState
    private lateinit var caller: CoroutineScope
    private lateinit var draft: EasyWorkConnectionDraft
    private val shown=mutableStateOf(true)
    private val originalDraft="Original unsent form-exit draft 中文 %@"
    private val traceLock=Any()
    private val trace=mutableListOf<JSONObject>()
    private fun event(stage: String,job: Job?=null,cause: Throwable?=null): JSONObject {
        val row=JSONObject().put("stage",stage).put("elapsedMs",android.os.SystemClock.elapsedRealtime()).put("thread",Thread.currentThread().name)
            .put("jobActive",job?.isActive?:JSONObject.NULL).put("jobCancelled",job?.isCancelled?:JSONObject.NULL).put("jobCompleted",job?.isCompleted?:JSONObject.NULL)
            .put("causeTypes",JSONArray(generateSequence(cause){it.cause}.take(6).map{it.javaClass.name}.toList()))
            .put("causeFrames",JSONArray(cause?.stackTrace?.take(12)?.map{it.toString()}.orEmpty()))
        synchronized(traceLock){check(trace.size<160);trace.add(row)}
        return row
    }
    private fun observe(stage: String,job: Job?=null,action: EasyWorkConnectionActionLease?=null,latch: CountDownLatch?=null,frames: Int=0) {
        val row=event(stage,job)
        val read={
            row.put("clockIdentity",System.identityHashCode(ui.mainClock)).put("composeClockMs",ui.mainClock.currentTime).put("autoAdvance",ui.mainClock.autoAdvance)
                .put("nativeVsyncCallbacks",frames).put("latchCount",latch?.count?:JSONObject.NULL)
                .put("activityLifecycle",ui.activity.lifecycle.currentState.name).put("windowFocus",ui.activity.window.decorView.hasWindowFocus())
                .put("connectionBusy",owned.session.connectionBusy).put("migrationReady",owned.session.migrationReady).put("migrationPaused",owned.session.migrationPaused)
                .put("restoring",owned.session.restoring).put("localRecoveryRequired",owned.session.localRecoveryRequired)
                .put("migrationOwnerStopping",owned.session.easyWorkMigrationContext[NativeEasyWorkMigrationOwners]?.stopping?:JSONObject.NULL)
                .put("leaseId",action?.id?:JSONObject.NULL).put("leaseCurrent",action?.currentForUi()?:JSONObject.NULL)
                .put("formId",action?.form?.id?:JSONObject.NULL).put("draftFormAttached",draft.formOwner!=null)
                .put("callerDispatcher",caller.coroutineContext[ContinuationInterceptor]?.javaClass?.name?:JSONObject.NULL)
                .put("callerFrameClock",caller.coroutineContext[MonotonicFrameClock]?.javaClass?.name?:JSONObject.NULL)
            val parent=caller.coroutineContext[Job];row.put("callerScopeActive",parent?.isActive?:JSONObject.NULL).put("callerScopeCancelled",parent?.isCancelled?:JSONObject.NULL)
        }
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())read()else androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{read()}
    }
    private fun saveTrace() {
        if(!::owned.isInitialized)return
        val rows=synchronized(traceLock){JSONArray(trace.map{JSONObject(it.toString())})}
        val clocks=(0 until rows.length()).mapNotNull{rows.getJSONObject(it).optInt("clockIdentity").takeIf{value->value!=0}}.distinct()
        val proof=JSONObject().put("version",1).put("entryBudgetMs",5_000).put("observedClockIdentities",JSONArray(clocks)).put("sameRuleClockObserved",if(clocks.isEmpty())JSONObject.NULL else clocks.size==1).put("observationsOnly",true).put("events",rows)
        val bytes=proof.toString().toByteArray(Charsets.UTF_8);check(bytes.size<=256*1024)
        File(owned.folder,"form-action-clock-observations.json").writeBytes(bytes)
    }
    @Before fun setup() {
        ui.waitUntil(30_000){!ui.activity.session.restoring}
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"migration-form-action");owned.session.restore()}
        ui.waitUntil(30_000){!owned.session.restoring&&owned.session.phoneReading.ready}
        ui.runOnIdle {previous=ui.activity.adoptSessionForAcceptance(owned.session);owned.session.text=originalDraft;owned.session.bindStoredVoicePreferencesIfReady()}
        ui.waitUntil(30_000){owned.session.migrationReady&&owned.session.voiceSettings.persisted}
        draft=EasyWorkConnectionDraft("Synthetic form action","https://relay.invalid").apply{method="通过中继连接";code="0123456789ABCDEF"}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {ProductTextProvider("en") {if(shown.value){val scope=rememberCoroutineScope();SideEffect{caller=scope};ConnectionPage(owned.session,null,draft){}}}}}}
        ui.onNodeWithTag("agent-provider-easywork").performScrollTo().performClick()
        ui.waitUntil(5_000){draft.formOwner!=null}
    }
    @After fun finish(){saveTrace();if(::previous.isInitialized)ui.runOnIdle{ui.activity.restoreSessionForAcceptance(previous);owned.verifyDefault()}}
    private fun begin(work: suspend (EasyWorkConnectionActionLease)->Unit): Pair<Job,EasyWorkConnectionActionLease> {
        lateinit var job: Job;lateinit var action: EasyWorkConnectionActionLease
        ui.runOnIdle {
            action=draft.formOwner!!.begin(EasyWorkConnectionInput("code",draft.name,draft.relay,draft.code));owned.session.connectionBusy=true
            observe("before-caller-launch",action=action)
            job=caller.launch {
                event("caller-start",currentCoroutineContext()[Job])
                try {owned.session.runEasyWorkConnectionFormAction(action){
                    event("session-owned-body-start",currentCoroutineContext()[Job]);work(action)
                }}catch(failure: Throwable){event("caller-failure",currentCoroutineContext()[Job],failure);throw failure}
                finally {event("caller-finally",currentCoroutineContext()[Job]);if(action.releaseBusy())owned.session.connectionBusy=false}
            }
            job.invokeOnCompletion{cause->event("caller-completed",job,cause)}
            observe("after-caller-launch",job,action)
        }
        return job to action
    }
    /** runOnIdle drained before begin's launch. v2 queues that Compose caller on this rule's
     * StandardTestDispatcher; a blocking await cannot drain it. Preserve the same real5s budget
     * and real latch while the owning rule runs current tasks/frames. Never force lease validity. */
    private fun CountDownLatch.entered(stage: String,job: Job?=null,action: EasyWorkConnectionActionLease?=null) {
        check(ui.mainClock.autoAdvance)
        val active=AtomicBoolean(true);val frames=AtomicInteger()
        val callback=object: android.view.Choreographer.FrameCallback {
            override fun doFrame(time: Long){frames.incrementAndGet();if(active.get())android.view.Choreographer.getInstance().postFrameCallback(this)}
        }
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync{android.view.Choreographer.getInstance().postFrameCallback(callback)}
        var failure: Throwable?=null
        try {
            observe("$stage-before-rule-wait",job,action,this,frames.get())
            ui.waitUntil(5_000){count==0L||job?.isCompleted==true}
            check(count==0L){"Actual $stage latch was not entered; inspect form-action-clock-observations.json"}
        }catch(error: Throwable){failure=error;event("$stage-wait-failed",job,error);throw error}
        finally {
            active.set(false);instrumentation.runOnMainSync{android.view.Choreographer.getInstance().removeFrameCallback(callback)}
            try {observe("$stage-after-rule-wait",job,action,this,frames.get());saveTrace()}
            catch(error: Throwable){if(failure==null)throw error else failure.addSuppressed(error)}
        }
    }
    private fun CountDownLatch.releaseOnlyWhenSignalled(){while(true)try{await();return}catch(_: InterruptedException){}}
    private suspend fun migration(): Deferred<NativeMigrationOwnerLease> = CoroutineScope(currentCoroutineContext()).async(Dispatchers.Main.immediate){owned.session.quiesceForMigration(UUID.randomUUID().toString())}
    private suspend fun rejectSecond() {
        val outcome=withContext(Dispatchers.Main.immediate){runCatching{owned.session.quiesceForMigration(UUID.randomUUID().toString())}}
        assertTrue(outcome.isFailure);assertNull(owned.storage.migrationPublicationReceipt())
    }
    @Test fun disposedFormKeepsActualCoordinatorInstallAndBlocksSecondMigrationUntilEncryptedWriteExits()=runBlocking {
        val seed=withContext(Dispatchers.IO){NativeMigrationEasyWorkFixtures.seed(ui.activity,owned.storage.child(ui.activity,"form-grant-seed"),"active")}
        val grant=JSONObject(seed.storage.read(seed.grantRef)!!).getJSONObject("grant");val claim=seed.storage.read(seed.claimName)!!
        withContext(Dispatchers.IO){owned.storage.write(NativeMigrationEasyWorkOriginals.IDENTITY,seed.identity.json().toString());owned.storage.write(seed.claimName,claim)}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val writes=AtomicInteger();val activated=AtomicInteger()
        val coordinator=EasyWorkAuthorizationCoordinator(object: EasyWorkAuthorizationRecords {
            override val root get()=owned.storage.coordinationKey
            // Hold at actual install's transaction entry, before acquiring the native storage lock.
            override fun <T> transaction(block: ()->T): T {entered.countDown();release.releaseOnlyWhenSignalled();return owned.storage.operationTransaction(block)}
            override fun read(key: String)=owned.storage.read(key)
            override fun write(key: String,value: String){owned.storage.write(key,value);if(key==seed.grantRef)writes.incrementAndGet()}
            override fun remove(key: String){owned.storage.remove(key)}
        })
        val (job,action)=begin {lease->withContext(Dispatchers.IO){coordinator.install(seed.grantRef,"https://relay.example.invalid",grant)};lease.checkpoint();activated.incrementAndGet()}
        var waiting: Deferred<NativeMigrationOwnerLease>?=null
        try {
            entered.entered("install-entry",job,action);ui.runOnIdle{shown.value=false};ui.waitForIdle();withTimeout(5_000){job.join()}
            assertFalse(action.currentForUi());ui.runOnIdle{assertFalse(owned.session.connectionBusy)}
            waiting=migration();ui.waitUntil(5_000){owned.session.migrationPaused}
            assertNull(withTimeoutOrNull(150){waiting.await();true});assertEquals(0,writes.get());assertNull(owned.storage.migrationPublicationReceipt())
            waiting.cancelAndJoin();ui.runOnIdle{assertFalse(owned.session.migrationPaused);assertFalse(owned.session.migrationReady)};rejectSecond()
            release.countDown();ui.waitUntil(10_000){owned.session.migrationReady}
            assertEquals(1,writes.get());assertEquals(0,activated.get());assertEquals(claim,owned.storage.read(seed.claimName))
            assertEquals(easyCanonical(grant),easyCanonical(JSONObject(owned.storage.read(seed.grantRef)!!).getJSONObject("grant")))
            ui.runOnIdle{assertSame(owned.session,ui.activity.session);assertEquals(originalDraft,owned.session.text);assertTrue(owned.session.clients.none{!it.isDemo})}
        }finally{release.countDown();waiting?.cancel();job.cancel()}
    }
    @Test fun pausedFormStillOwnsActualHttpCallbackAndOuterCleanupAfterWaitCancellation()=runBlocking {
        val body=NativeMigrationHeldResponse();val requests=AtomicInteger()
        val client=EasyWorkHttp.isolatedClient(OkHttpClient.Builder().addInterceptor {chain->requests.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body).build()})
        val (job,action)=begin {try{EasyWorkHttp.request(client,"https://synthetic.invalid/form-cleanup")}finally{EasyWorkHttp.dispose(client)}}
        var waiting: Deferred<NativeMigrationOwnerLease>?=null
        try {
            body.reading.entered("http-read-entry",job,action);ui.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            withContext(Dispatchers.Main.immediate){assertFalse(owned.session.connectionBusy);assertFalse(action.currentForUi())}
            waiting=migration();withTimeout(5_000){while(!withContext(Dispatchers.Main.immediate){owned.session.migrationPaused})delay(20)}
            assertNull(withTimeoutOrNull(150){waiting.await();true});assertFalse(EasyWorkHttp.dispose(client).isCompleted)
            waiting.cancelAndJoin();rejectSecond()
            body.releaseRead.countDown();body.closing.entered("http-close-entry")
            withContext(Dispatchers.Main.immediate){assertFalse(owned.session.migrationReady)};assertFalse(EasyWorkHttp.dispose(client).isCompleted)
            body.releaseClose.countDown();withTimeout(10_000){while(!withContext(Dispatchers.Main.immediate){owned.session.migrationReady})delay(20)}
            withTimeout(5_000){EasyWorkHttp.dispose(client).await()};assertEquals(1,requests.get());assertEquals(1,body.closeCount.get())
            withContext(Dispatchers.Main.immediate){assertEquals(originalDraft,owned.session.text);assertTrue(owned.session.clients.none{!it.isDemo})}
            assertNull(owned.storage.migrationPublicationReceipt())
        }finally{body.releaseRead.countDown();body.releaseClose.countDown();waiting?.cancel();job.cancel();EasyWorkHttp.dispose(client);ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
    }
}
