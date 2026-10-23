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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Actual session scheduling with synthetic read-only traffic and native lifecycle transitions. */
@RunWith(AndroidJUnit4::class)
class MobileSilentCatchUpTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"catch-up-host","catch-up-workspace","desktop-runtime")
    private val counts=ConcurrentHashMap<String,AtomicInteger>()
    private val eventCount=AtomicInteger()
    private var hold: CompletableDeferred<Unit>?=null
    private var holdNonCancellable=false
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun count(thread: String="catch-a")=counts[thread]?.get() ?: 0
    private fun pause(millis: Long)=runBlocking { delay(millis) }
    private fun page(thread: String,active: Boolean,sequence: Long=0,advancing: Boolean=false): MobileHistory {
        val json=JSONObject().put("hostId",scope.hostId).put("threadId",thread).put("readTarget",CodexCommandCodec.target(scope))
            .put("epoch","catch-up-epoch").put("cursor",sequence)
            .put("messages",JSONArray().put(JSONObject().put("id",thread+"-row").put("role","assistant").put("text",thread+" cached content").put("status","completed")))
        if(active)json.put("synchronization",JSONObject().put("state","history").put("checkedAt",Instant.parse("2026-10-22T00:00:00Z").plusSeconds(if(advancing)sequence else 0).toString()))
        return HostReadCodec.history(json.toString())
    }
    private fun setup(active: Boolean=true,advancing: Boolean=false) {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","synthetic silent catch-up").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("events","commands:target-bound:v1")))
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("catch-a","catch-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                counts.computeIfAbsent(threadId) { AtomicInteger() }.incrementAndGet()
                if(threadId=="catch-a")hold?.let { if(holdNonCancellable)withContext(NonCancellable) { it.await() } else it.await() }
                return page(threadId,active)
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                if(!active)awaitCancellation()
                delay(100);eventCount.incrementAndGet()
                return EventBatch(scope.hostId,"catch-up-epoch",cursor+1,false,true,null,listOf(page(threadId,true,cursor+1,advancing)))
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader,commands) }
        main { session.connect("synthetic catch-up","https://fixture.invalid","") }
        ui.waitUntil(10_000) { !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="catch-up draft" }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.isNotEmpty() }
        assertEquals(1,count())
    }
    @After fun stop() { hold?.complete(Unit);ui.runOnIdle { session.leaveForeground() } }
    @Test fun repeatedCachedEventsCannotPostponeActiveSilentRead() {
        setup();val cached=session.messages
        ui.waitUntil(5_000) { count()>=2 };ui.waitUntil(5_000) { !session.historyLoading }
        assertTrue(eventCount.get()>0);assertEquals(cached,session.messages);assertEquals("catch-up draft",session.text)
        pause(400);assertEquals("A failed useful-progress test must not turn into rapid GET replay",2,count())
    }
    @Test fun idleLongPollHasAnIndependentTenSecondHistoryFallback() {
        setup(active=false);ui.waitUntil(12_500) { count()>=2 };ui.waitUntil(5_000) { !session.historyLoading }
        assertEquals("catch-up draft",session.text);assertEquals("catch-a-row",session.messages.single().id)
    }
    @Test fun strictlyAdvancingHistoryProgressKeepsSilentReadsDeferred() {
        setup(advancing=true);pause(3_200)
        assertTrue(eventCount.get()>=5);assertEquals(1,count());assertEquals("catch-up draft",session.text)
    }
    @Test fun leavingForegroundCancelsIndependentHistoryRecovery() {
        setup();ui.runOnIdle { session.leaveForeground() };val before=count();pause(2_700)
        assertEquals(before,count());assertEquals("catch-up draft",session.text)
    }
    @Test fun activeSilentReadCoalescesWithAnAlreadyRunningHistoryGet() {
        setup();val release=CompletableDeferred<Unit>();hold=release
        try {
            ui.runOnIdle { session.loadHistory() };ui.waitUntil(5_000) { count()==2&&session.historyLoading }
            pause(2_700);assertEquals(2,count());assertEquals("catch-up draft",session.text)
            release.complete(Unit);ui.waitUntil(5_000) { !session.historyLoading }
        } finally { release.complete(Unit) }
    }
    @Test fun changingConversationRejectsLateCatchUpAndStopsOldRecovery() {
        setup();val release=CompletableDeferred<Unit>();hold=release;holdNonCancellable=true
        try {
            ui.waitUntil(5_000) { count()==2&&session.historyLoading }
            ui.runOnIdle { session.selectThread(session.threads.last());session.text="new target draft" }
            ui.waitUntil(5_000) { !session.historyLoading&&session.messages.singleOrNull()?.id=="catch-b-row" }
            release.complete(Unit);pause(2_700)
            assertEquals(2,count());assertTrue(count("catch-b")>=2);assertEquals("catch-b-row",session.messages.single().id);assertEquals("new target draft",session.text)
        } finally { release.complete(Unit) }
    }
}
