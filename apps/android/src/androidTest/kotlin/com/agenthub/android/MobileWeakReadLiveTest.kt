package com.agenthub.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Explicit opt-in original desktop/model examiner; warm must never replay an existing namespace. */
@RunWith(AndroidJUnit4::class)
class MobileWeakReadLiveTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(instrumentation.targetContext.filesDir,"weak-read-live.json").readText()) }
    private val phase get()=config.getString("phase")
    private val folder by lazy { File(instrumentation.targetContext.filesDir,"acceptance/P2-live-reads/"+config.getString("run")).apply { mkdirs() } }
    @get:Rule(order=0) val foreground=object: ExternalResource() {
        override fun before() {
            require(BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&BuildConfig.DEBUG)
            require(InstrumentationRegistry.getArguments().getString("nativeLiveRead")=="1")
            val target=instrumentation.targetContext.packageName;require(target==BuildConfig.APPLICATION_ID)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n $target/androidx.activity.ComponentActivity")).bufferedReader().use { assertTrue(it.readText().contains("Status: ok")) }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    @get:Rule(order=2) val limit=org.junit.rules.Timeout.seconds(900)
    private lateinit var session: MobileSession
    private lateinit var storage: NativeSessionStorage
    private val moments=JSONArray()
    private fun mark(name: String) { moments.put(JSONObject().put("event",name).put("uptime",SystemClock.elapsedRealtimeNanos()/1_000_000_000.0)) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject=JSONObject()): JSONObject {
        require(route in setOf("assert-idle","configure","stats","observe","phase"))
        val base=URI(config.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=5_000;http.readTimeout=180_000
            http.setRequestProperty("Authorization","Bearer "+config.getString("controlKey"));http.setRequestProperty("Content-Type","application/json");http.doOutput=true
            val bytes=value.put("nonce",config.getString("nonce")).toString().toByteArray();http.setFixedLengthStreamingMode(bytes.size);http.outputStream.use { it.write(bytes) }
            assertEquals("Exact owned original-runtime examiner",200,http.responseCode)
            return http.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { http.disconnect() }
    }
    private fun start(restoring: Boolean) {
        require(config.getString("run").matches(Regex("[a-f0-9-]{36}")))
        val namespace=config.getString("namespace");require(namespace.matches(Regex("weak-live-[a-f0-9-]{36}")))
        storage=NativeSessionStorage(instrumentation.targetContext,namespace)
        if(restoring)assertTrue("Original native metadata or verified credential must exist",storage.metadata()!=null||CodexConnectionVault(storage).clients().any { it.id==config.getString("hostId") }) else assertNull("Warm examiner cannot replay an existing binding or input",storage.metadata())
        NativeReadAcceptanceTiming.configure(instrumentation.targetContext,Intent().putExtra(NativeReadAcceptanceTiming.ENABLED,true).putExtra(NativeReadAcceptanceTiming.HOST,config.getString("hostId")).putExtra(NativeReadAcceptanceTiming.RUN,config.getString("run")))
        assertNotNull(NativeReadAcceptanceTiming.capture());session=MobileSession(storage)
        ui.setContent { AgentHubApp(session) };mark("native-session-created")
        if(restoring)main { session.restore() } else main { session.resetAcceptance() }
    }
    private fun shot(name: String) {
        ui.waitForIdle();val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$phase-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun dismissError() { if(ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("dismiss-error").performClick() }
    private fun send(text: String) {
        assertEquals(config.getString("hostId"),session.selectedClient.id)
        assertTrue(text==config.getString("firstText")||text==config.getString("replyText"))
        main { session.configure(GenerationSettings(model=config.getString("model"),effort="xhigh",permissionMode="read-only",collaborationMode="default")) }
        assertEquals("read-only",session.generationSettings.permissionMode)
        assertTrue("Existing intent must be inspected, never constructed again",session.operationRecords.none { JSONObject(it.intent).optString("text")==text })
        ui.waitUntil(30_000) { session.canCompose&&session.transferStatus==null }
        ui.onNodeWithTag("message-input").performTextReplacement(text);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest!=null||session.transferStatus!=null||session.operationRecords.any { JSONObject(it.intent).optString("text")==text } }
        if(session.aiSharingRequest!=null) {
            assertEquals(config.getString("hostId"),session.aiSharingRequest!!.scope.target.hostId)
            assertTrue(session.operationRecords.none { JSONObject(it.intent).optString("text")==text })
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            assertEquals(text,session.text);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        }
        mark("explicit-native-send")
    }
    private fun completed(text: String,marker: String,kind: String): SavedOperation {
        ui.waitUntil(180_000) { session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==marker }&&!session.running&&session.transferStatus==null }
        val expectedPhase=if(kind=="creation") "confirmed" else "completed"
        ui.waitUntil(30_000) { session.operationRecords.any { it.kind==kind&&JSONObject(it.intent).optString("text")==text&&it.phase==expectedPhase } }
        val record=session.operationRecords.single { it.kind==kind&&JSONObject(it.intent).optString("text")==text };assertEquals(expectedPhase,record.phase)
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker));ui.onAllNodesWithText(marker).onLast().assertIsDisplayed();mark("native-final-visible")
        control("observe",JSONObject().put("kind",kind).put("operationId",record.id).put("threadId",session.selectedThread).put("intentSha256",record.intentSha256).put("marker",marker));return record
    }
    @After fun preserve() {
        if(!::session.isInitialized)return
        runCatching { File(folder,"$phase-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString().take(262144)) }
        runCatching { if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==instrumentation.targetContext.packageName) { val image=instrumentation.uiAutomation.takeScreenshot();try { File(folder,"$phase-final-state.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() } } }
        runCatching { File(folder,"$phase-current.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("moments",moments).put("threadId",session.selectedThread).put("canCompose",session.canCompose).put("historyLoading",session.historyLoading).put("error",session.error).put("restoring",session.restoring).put("operations",JSONArray(session.operationRecords.map { JSONObject().put("id",it.id).put("kind",it.kind).put("phase",it.phase).put("intentSha256",it.intentSha256) })).toString(2)) }
        main { session.leaveForeground() };NativeReadAcceptanceTiming.configure(instrumentation.targetContext,Intent())
    }
    @Test fun warmRealHistoryTimeoutEventsSendAndRecovery() {
        assertEquals("warm",phase)
        val resumeBeforeInput=config.optBoolean("resumeBeforeInput",false)
        if(resumeBeforeInput) {
            assertEquals(0,control("stats").getInt("realModelTasks"))
            val kept=NativeSessionStorage(instrumentation.targetContext,config.getString("namespace"))
            val canRestore=kept.metadata()!=null||CodexConnectionVault(kept).clients().any { it.id==config.getString("hostId") }
            if(canRestore) { start(true);ui.waitUntil(30_000) { !session.restoring } }
            else { assertEquals("Same previously verified legacy credential only",CodexDescriptor.parsePairing(config.getString("pairing")).token,kept.token(config.getString("hostId")));start(false);main { session.connectCodexQR(config.getString("pairing"),"独立原桌面弱网验收") } }
            assertTrue("Existing submitted input must never be replayed",session.operationRecords.isEmpty());assertEquals(config.getString("hostId"),session.selectedClient.id)
        }
        else { control("assert-idle");start(false);main { session.connectCodexQR(config.getString("pairing"),"独立原桌面弱网验收") } }
        ui.waitUntil(45_000) { !session.loading&&session.canCreateThread&&session.models.any { it.id==config.getString("model")&&"xhigh" in it.supportedEfforts } }
        assertTrue(session.projects.any { it.id==config.getString("projectId") })
        main { session.newChat(config.getString("projectId"));session.configure(GenerationSettings(model=config.getString("model"),effort="xhigh",permissionMode="read-only",collaborationMode="default")) }
        send(config.getString("firstText"));completed(config.getString("firstText"),config.getString("firstMarker"),"creation")
        val thread=session.selectedThread;control("phase",JSONObject().put("action","enable-read-proxy").put("threadId",thread))
        ui.waitUntil(20_000) { !session.historyLoading };val prior=control("stats");control("configure",JSONObject().put("action","stall"));main { session.loadHistory() }
        ui.waitUntil(12_000) { session.historyLoading };mark("history-stall-observed")
        Thread.sleep(2_000);assertTrue(session.canCompose);ui.onNodeWithTag("message-input").assertIsEnabled();mark("input-ready-during-history")
        ui.waitUntil(60_000) { !session.historyLoading };val after=control("stats");assertTrue(after.getInt("stalls")>prior.getInt("stalls"));assertTrue(after.getInt("completedEvents")>prior.getInt("completedEvents"));assertNull(session.error);assertTrue(session.selectedClient.online);assertTrue(session.canCompose)
        assertTrue(session.messages.any { it.text.trim()==config.getString("firstMarker") });shot("history-timeout-events-healthy")
        control("configure",JSONObject().put("action","stall"));main { session.loadHistory() };ui.waitUntil(12_000) { session.historyLoading }
        runBlocking { withTimeout(12_000) { while(control("stats").getInt("activeHistoryStalls")==0)delay(100) } }
        mark("actual-held-history-before-single-reply")
        send(config.getString("replyText"));val reply=completed(config.getString("replyText"),config.getString("replyMarker"),"reply");assertEquals(thread,session.selectedThread)
        val original=reply.intentSha256;val draft="弱网后保留的本机草稿 "+config.getString("nonce");ui.onNodeWithTag("message-input").performTextReplacement(draft)
        for(profile in listOf("normal","constrained","severe")) {
            control("configure",JSONObject().put("action",profile));ui.waitUntil(60_000) { !session.historyLoading };main { session.loadHistory() };ui.waitUntil(60_000) { !session.historyLoading };mark("profile-$profile-history-complete")
            assertEquals(draft,session.text);assertEquals(original,session.operationRecords.single { it.id==reply.id }.intentSha256);assertTrue(session.messages.any { it.text.trim()==config.getString("replyMarker") })
        }
        val priorDrops=control("stats").getInt("drops");control("configure",JSONObject().put("action","drop"));main { session.loadHistory() };ui.waitUntil(60_000) { !session.historyLoading };assertTrue(control("stats").getInt("drops")>priorDrops);assertEquals(draft,session.text);assertTrue(session.messages.isNotEmpty());dismissError()
        control("configure",JSONObject().put("action","all-read-offline"));main { session.refresh(userInitiated=true);session.loadHistory() };ui.waitUntil(45_000) { !session.loading&&!session.historyLoading };assertEquals(thread,session.selectedThread);assertEquals(draft,session.text);assertTrue(session.messages.any { it.text.trim()==config.getString("replyMarker") });shot("all-read-outage-cache-and-draft");dismissError()
        control("configure",JSONObject().put("action","recover"));main { session.refresh();session.loadHistory() };ui.waitUntil(60_000) { !session.loading&&!session.historyLoading&&session.selectedClient.online&&session.canCompose };assertEquals(draft,session.text)
        ui.waitUntil(10_000) { storage.metadata()?.optJSONObject("drafts")?.toString()?.contains(draft)==true }
        val cacheTarget=session.targetKey;runBlocking(Dispatchers.IO) { withTimeout(10_000) { while(NativeHistoryCache(storage).read(cacheTarget)?.messages?.any { it.text.trim()==config.getString("replyMarker") }!=true)delay(100) } }
        control("phase",JSONObject().put("action","warm-complete").put("threadId",thread).put("draft",draft));shot("recovered-original-target")
    }
    private fun requireOriginalCompletedInputs() {
        val expected=config.getJSONArray("expectedOperations").objects();assertEquals(2,expected.size)
        ui.waitUntil(45_000) { expected.all { e -> session.operationRecords.any { it.id==e.getString("id")&&it.kind==e.getString("kind")&&it.phase==e.getString("phase")&&it.intentSha256==e.getString("intentSha256") } } }
        assertTrue(session.activeOperationRecords.isEmpty());assertEquals(config.getString("threadId"),session.selectedThread)
    }
    @Test fun readonlyProfilesDropOutageAndOriginalFinalVisibleWithoutNewInputs() {
        assertEquals("readonly",phase);assertTrue(config.getBoolean("readOnlySupplement"));start(true)
        ui.waitUntil(60_000) { !session.restoring&&!session.loading&&!session.historyLoading&&session.selectedClient.id==config.getString("hostId")&&session.messages.any { it.text.trim()==config.getString("replyMarker") } }
        requireOriginalCompletedInputs()
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(config.getString("replyMarker")));ui.onAllNodesWithText(config.getString("replyMarker")).onLast().assertIsDisplayed();mark("original-second-final-visible-without-new-input")
        val draft=config.getString("expectedDraft");ui.onNodeWithTag("message-input").performTextReplacement(draft)
        for(profile in listOf("normal","constrained","severe")) {
            control("configure",JSONObject().put("action",profile));ui.waitUntil(60_000) { !session.historyLoading };main { session.loadHistory() };ui.waitUntil(60_000) { !session.historyLoading }
            assertEquals(draft,session.text);assertEquals(config.getString("threadId"),session.selectedThread);assertTrue(session.messages.any { it.text.trim()==config.getString("replyMarker") });requireOriginalCompletedInputs();mark("readonly-$profile-history-complete")
        }
        val oldDrops=control("stats").getInt("drops");control("configure",JSONObject().put("action","drop"));main { session.loadHistory() };ui.waitUntil(60_000) { !session.historyLoading };assertTrue(control("stats").getInt("drops")>oldDrops);assertEquals(draft,session.text);assertTrue(session.messages.isNotEmpty());mark("actual-truncation-retains-native-read");dismissError()
        control("configure",JSONObject().put("action","all-read-offline"));main { session.refresh(userInitiated=true);session.loadHistory() };ui.waitUntil(45_000) { !session.loading&&!session.historyLoading };assertEquals(draft,session.text);assertEquals(config.getString("threadId"),session.selectedThread);assertTrue(session.messages.any { it.text.trim()==config.getString("replyMarker") });shot("readonly-all-read-outage");mark("all-read503-preserves-context");dismissError()
        control("configure",JSONObject().put("action","recover"));main { session.refresh();session.loadHistory() };ui.waitUntil(60_000) { !session.loading&&!session.historyLoading&&session.selectedClient.online };assertEquals(draft,session.text);requireOriginalCompletedInputs()
        ui.waitUntil(10_000) { storage.metadata()?.optJSONObject("drafts")?.toString()?.contains(draft)==true }
        val cacheTarget=session.targetKey;runBlocking(Dispatchers.IO) { withTimeout(10_000) { while(NativeHistoryCache(storage).read(cacheTarget)?.messages?.any { it.text.trim()==config.getString("replyMarker") }!=true)delay(100) } }
        File(folder,"readonly-pass.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("moments",moments).put("draftPersisted",true).put("actualDiskCacheContainsOriginalFinal",true).put("expectedOperations",config.getJSONArray("expectedOperations")).toString(2));shot("readonly-recovered-final")
    }
    @Test fun separateProcessRestoresActualCacheBeforeAnySuccessfulRead() {
        assertEquals("cold",phase);start(true)
        ui.waitUntil(45_000) { !session.restoring&&session.selectedClient.id==config.getString("hostId")&&session.messages.any { it.text.trim()==config.getString("replyMarker") } }
        assertEquals(config.getString("threadId"),session.selectedThread);assertEquals(config.getString("expectedDraft"),session.text)
        dismissError();ui.onNodeWithTag("message-list").performScrollToNode(hasText(config.getString("replyMarker")));ui.onAllNodesWithText(config.getString("replyMarker")).onLast().assertIsDisplayed();ui.onNodeWithTag("message-input").assertTextEquals(config.getString("expectedDraft"));mark("native-cached-final-visible-with-reads-blocked")
        if(config.optBoolean("readOnlySupplement",false)) {
            requireOriginalCompletedInputs()
            val timing=File(instrumentation.targetContext.cacheDir,"native-read-"+config.getString("run")+".jsonl")
            ui.waitUntil(10_000) { runCatching { timing.readLines().filter(String::isNotEmpty).map(::JSONObject).any { it.getString("event")=="cache-applying" } }.getOrDefault(false) }
            val captured=timing.readLines().filter(String::isNotEmpty).map(::JSONObject);assertFalse("Blocked reads cannot supply a fresh HTTP history",captured.any { it.getString("event")=="history-response" })
            // The parent independently checks every named GET/POST after the captured request index;
            // the earlier combined warm/policy gate stays failed and its cold counters are not reused.
        } else { val stats=control("stats");assertEquals(0,stats.getInt("coldSuccessfulReads"));assertEquals(0,stats.getInt("coldPosts")) }
        shot("cold-native-cache-before-network")
        control("configure",JSONObject().put("action","recover"));main { session.refresh();session.loadHistory() };ui.waitUntil(60_000) { !session.loading&&!session.historyLoading&&session.selectedClient.online };assertEquals(config.getString("expectedDraft"),session.text)
        if(config.optBoolean("readOnlySupplement",false)) { requireOriginalCompletedInputs();File(folder,"cold-pass.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("moments",moments).put("originalOperationsRetained",true).put("draftRetained",true).toString(2)) }
        else control("phase",JSONObject().put("action","cold-complete").put("threadId",session.selectedThread))
    }
}
