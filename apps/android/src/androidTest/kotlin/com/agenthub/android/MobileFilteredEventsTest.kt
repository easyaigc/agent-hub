package com.agenthub.android

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real session/control projection with a synthetic, thread-filtered Host; no input POSTs. */
@RunWith(AndroidJUnit4::class)
class MobileFilteredEventsTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val scope=TargetScope("codex",null,null,"filtered-host","filtered-workspace","desktop-runtime")
    private val batches=Channel<EventBatch>(Channel.UNLIMITED)
    private val requestedCursors=Channel<Long>(Channel.UNLIMITED)
    private val histories=AtomicInteger();private val catalogs=AtomicInteger()
    private val releaseHistory=CompletableDeferred<Unit>()
    private var observer: Job?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun workspace()=HostReadCodec.workspace(JSONObject().put("hostId",scope.hostId).put("name","synthetic filtered events")
        .put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
        .put("agentConnectionState",JSONObject().put("runtimeConnected",true))
        .put("capabilities",JSONArray(listOf("events","commands:target-bound:v1","reply","queue")))
        .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","filtered-thread").put("hostId",scope.hostId).put("title","synthetic filtered events").put("state","running"))).toString())
    private fun history(cursor: Long=0)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId","filtered-thread")
        .put("readTarget",CodexCommandCodec.target(scope)).put("epoch","filtered-epoch").put("cursor",cursor)
        .put("runtime",JSONObject().put("state","running").put("turnId","synthetic-turn").put("canControl",true)
            .put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray()))
        .put("messages",JSONArray((0 until 120).map { JSONObject().put("id","filtered-$it").put("role","assistant").put("text","synthetic row $it").put("status","completed") })).toString())
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"filtered-event-controls") }
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace { catalogs.incrementAndGet();return workspace() }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(histories.incrementAndGet()>1)releaseHistory.await()
                return history()
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                assertEquals("filtered-thread",threadId);requestedCursors.send(cursor);return batches.receive()
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader,commands) }
        main { session.connect("synthetic filtered events","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.single());session.text="original draft" }
        ui.waitUntil(10_000) { !session.historyLoading&&session.canStop&&session.isWritable }
        runBlocking { withTimeout(10_000) { assertEquals(0L,requestedCursors.receive()) } }
    }
    @After fun stop() {
        observer?.cancel();releaseHistory.complete(Unit)
        if(::owned.isInitialized)ui.runOnIdle { session.leaveForeground();owned.verifyDefault() }
    }
    private fun deliver(cursor: Long,ready: Boolean=true,reset: Boolean=false,snapshots: List<MobileHistory> = emptyList()) {
        runBlocking {
            batches.send(EventBatch(scope.hostId,"filtered-epoch",cursor,reset,ready,null,snapshots))
            withTimeout(10_000) { assertEquals(cursor,requestedCursors.receive()) }
        }
    }
    @Test fun globalCursorHeartbeatsKeepControlsReadyAndDoNotStartReloadLoop() {
        val invalidations=AtomicInteger();val original=session.messages.map { it.id }
        main { observer=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).launch {
            snapshotFlow { session.isWritable&&session.canStop }.collect { if(!it)invalidations.incrementAndGet() }
        } }
        repeat(28) { deliver((it+1)*7L);assertTrue(session.isWritable);assertTrue(session.canStop) }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("history-local-more").assertIsEnabled()
        assertEquals(0,invalidations.get());assertEquals(1,catalogs.get())
        assertTrue("Only the independent bounded catch-up may read, not each cursor batch",histories.get()<=2)
        assertEquals(original,session.messages.map { it.id });assertEquals("original draft",session.text)
    }
    @Test fun unreadyHeartbeatRevokesControlUntilValidatedSnapshot() {
        deliver(1,ready=false);assertFalse(session.isWritable);assertFalse(session.canStop)
        deliver(2);assertFalse("An empty heartbeat cannot grant control",session.isWritable)
        deliver(3,snapshots=listOf(history(3)));assertTrue(session.isWritable);assertTrue(session.canStop)
        assertEquals("original draft",session.text)
    }
    @Test fun emptyResetStillInvalidatesAndRepairsHistory() {
        deliver(1,reset=true)
        ui.waitUntil(10_000) { session.historyLoading&&histories.get()==2 }
        assertFalse(session.isWritable);releaseHistory.complete(Unit)
        ui.waitUntil(10_000) { !session.historyLoading&&session.isWritable&&session.canStop }
        assertEquals("original draft",session.text);assertEquals(120,session.messages.size)
    }
}
