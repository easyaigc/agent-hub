package com.agenthub.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Injected opaque path signals through the native session, never a physical-switch/model claim. */
@RunWith(AndroidJUnit4::class)
class MobileNetworkReadRecoveryTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"network-read-host","network-read-workspace","network-read-runtime")
    private val catalogs=AtomicInteger();private val histories=AtomicInteger();private val events=AtomicInteger();private val eventCancels=AtomicInteger();private val historyCancels=AtomicInteger()
    private var historyHandler: (suspend (Int,MobileClient,String)->MobileHistory)?=null
    private var eventHandler: (suspend (Int,MobileClient,String)->EventBatch)?=null
    private val cleanup=mutableListOf<()->Unit>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun page(thread: String,text: String="network cached content")=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",thread)
        .put("readTarget",CodexCommandCodec.target(scope)).put("epoch","network-epoch").put("cursor",0)
        .put("messages",JSONArray().put(JSONObject().put("id",thread+"-row").put("role","assistant").put("text",text).put("status","completed"))).toString())
    private fun batch(thread: String,text: String)=EventBatch(scope.hostId,"network-epoch",1,true,true,null,listOf(page(thread,text)))
    private fun setup() {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                catalogs.incrementAndGet();return HostReadCodec.workspace(JSONObject().put("hostId",scope.hostId).put("name","synthetic path recovery")
                    .put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                    .put("capabilities",JSONArray(listOf("events","commands:target-bound:v1"))).put("projects",JSONArray())
                    .put("threads",JSONArray().put(JSONObject().put("id","network-a").put("hostId",scope.hostId).put("title","network-a").put("state","idle"))).toString())
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val call=histories.incrementAndGet();return historyHandler?.invoke(call,client,threadId) ?: page(threadId)
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                val call=events.incrementAndGet()
                return eventHandler?.invoke(call,client,threadId) ?: try { awaitCancellation() } finally { eventCancels.incrementAndGet() }
            }
        }
        ui.runOnIdle { check(ui.activity.packageName=="com.easyai.agenthub.android.acceptance");ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reader,commands) }
        main { session.connect("synthetic network","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="network recovery draft" }
        ui.waitUntil(5_000) { !session.loading&&!session.historyLoading&&events.get()==1 }
        ui.runOnIdle { session.observeNetworkReadPath("path-baseline",true) }
    }
    @After fun stop() { cleanup.forEach { it() };ui.runOnIdle { session.leaveForeground() } }
    @Test fun baselineAndRepeatedIdenticalSignalsDoNotRestartStartupReads() {
        setup();main { repeat(4) { session.observeNetworkReadPath("path-baseline") };delay(350) }
        assertEquals(1,catalogs.get());assertEquals(1,histories.get());assertEquals(1,events.get());assertEquals(0,eventCancels.get())
    }
    @Test fun changedPathBurstCancelsOnlyReadJobsAndCoalescesRecovery() {
        setup();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();cleanup.add { release.complete(Unit) }
        historyHandler={call,_,thread -> if(call==2)try { started.complete(Unit);release.await() } finally { if(!currentCoroutineContext().isActive)historyCancels.incrementAndGet() };page(thread) }
        ui.runOnIdle { session.loadHistory() };runBlocking { withTimeout(5_000) { started.await() } }
        val key=session.targetKey
        main { session.observeNetworkReadPath("path-new-a");session.observeNetworkReadPath("path-new-b");session.observeNetworkReadPath("path-new-b");delay(400) }
        ui.waitUntil(5_000) { catalogs.get()==2&&histories.get()>=3&&events.get()==2&&!session.historyLoading }
        assertEquals(1,historyCancels.get());assertEquals(1,eventCancels.get());assertEquals(key,session.targetKey);assertEquals("network recovery draft",session.text)
    }
    private fun obsoleteHistory(fail: Boolean) {
        setup();val started=CompletableDeferred<Unit>();var continuation: Continuation<MobileHistory>?=null;val returned=AtomicInteger()
        historyHandler={call,_,thread ->
            if(call==2) { val value=suspendCoroutine<MobileHistory> { continuation=it;started.complete(Unit) };returned.incrementAndGet();value }
            else page(thread,"new path content")
        }
        cleanup.add { continuation?.let { continuation=null;it.resume(page("network-a","obsolete path content")) } }
        ui.runOnIdle { session.loadHistory() };runBlocking { withTimeout(5_000) { started.await() } }
        ui.runOnIdle { session.observeNetworkReadPath("path-new") };ui.waitUntil(5_000) { histories.get()>=3&&!session.historyLoading }
        main { val old=continuation!!;continuation=null;if(fail)old.resumeWithException(HostReadError("obsolete path authentication")) else old.resume(page("network-a","obsolete path content")) }
        ui.runOnIdle {};assertEquals(if(fail)0 else 1,returned.get());assertEquals("new path content",session.messages.single().text);assertTrue(session.selectedClient.online);assertNull(session.error)
    }
    @Test fun obsoleteHistoryResultCannotOverwriteSameNavigationAfterReadRotation()=obsoleteHistory(false)
    @Test fun obsoleteHistoryFailureCannotFailNewSameNavigationRead()=obsoleteHistory(true)
    private fun obsoleteEvent(fail: Boolean) {
        var continuation: Continuation<EventBatch>?=null;val started=CompletableDeferred<Unit>();val returned=AtomicInteger()
        eventHandler={call,_,thread ->
            if(call==1) { val value=suspendCoroutine<EventBatch> { continuation=it;started.complete(Unit) };returned.incrementAndGet();value }
            else if(call==2)batch(thread,"new event path") else awaitCancellation()
        }
        setup();runBlocking { withTimeout(5_000) { started.await() } }
        cleanup.add { continuation?.let { continuation=null;it.resume(batch("network-a","obsolete event path")) } }
        ui.runOnIdle { session.observeNetworkReadPath("path-new") };ui.waitUntil(5_000) { events.get()>=2&&session.messages.single().text=="new event path" }
        main { val old=continuation!!;continuation=null;if(fail)old.resumeWithException(HostReadError("obsolete event authentication")) else old.resume(batch("network-a","obsolete event path")) }
        ui.runOnIdle {};assertEquals(if(fail)0 else 1,returned.get());assertEquals("new event path",session.messages.single().text);assertTrue(session.selectedClient.online);assertNull(session.error)
    }
    @Test fun obsoleteEventResultCannotReplaceNewStreamOnTheSameTarget()=obsoleteEvent(false)
    @Test fun obsoleteEventFailureCannotFailNewStreamOnTheSameTarget()=obsoleteEvent(true)
    @Test fun backgroundCancelsQueuedNetworkRecoveryWithoutChangingDraft() {
        setup();ui.runOnIdle { session.observeNetworkReadPath("path-new");session.leaveForeground() };main { delay(350) }
        assertEquals(1,catalogs.get());assertEquals(1,histories.get());assertEquals("network recovery draft",session.text)
    }
    @Test fun newClientNavigationCancelsQueuedRecoveryWithoutDuplicateCatalogReads() {
        setup();main {
            session.observeNetworkReadPath("path-new");session.select(session.selectedClient);delay(350)
        }
        ui.waitUntil(5_000) { !session.loading&&!session.historyLoading }
        assertEquals(2,catalogs.get());assertEquals(2,histories.get());assertEquals(2,events.get());assertEquals("network recovery draft",session.text)
    }

}
