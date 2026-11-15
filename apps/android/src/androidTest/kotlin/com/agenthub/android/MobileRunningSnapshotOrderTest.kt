package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Native session/UI race regression; isolated synthetic Host, never submits a user turn. */
@RunWith(AndroidJUnit4::class)
class MobileRunningSnapshotOrderTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val target=TargetScope("codex",null,null,"ordering-host","ordering-workspace","desktop-runtime")
    private val batches=Channel<EventBatch>(Channel.UNLIMITED)
    private val requested=Channel<Long>(Channel.UNLIMITED)
    private val histories=Channel<CompletableDeferred<MobileHistory>>(Channel.UNLIMITED)
    private val releases=mutableListOf<CompletableDeferred<MobileHistory>>()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun page(cursor: Long,state: String="running",count: Int=120,versioned: Boolean=true)=HostReadCodec.history(
        JSONObject().put("hostId",target.hostId).put("threadId","ordering-thread")
            .put("readTarget",CodexCommandCodec.target(target))
            .apply { if(versioned)put("epoch","ordering-epoch").put("cursor",cursor) }
            .put("runtime",JSONObject().put("state",state).put("canControl",true)
                .apply { if(state=="running")put("turnId","ordering-turn") }
                .put("plan",JSONArray()).put("approvals",JSONArray()).put("receipts",JSONArray()))
            .put("messages",JSONArray((0 until count).map { JSONObject().put("id","row-$it")
                .put("role",if(it==0)"user" else "assistant").put("text","synthetic row $it").put("status","completed") })).toString())
    private fun connect(initial: MobileHistory) {
        var first=true
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(
                JSONObject().put("hostId",target.hostId).put("name","synthetic snapshot ordering")
                    .put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
                    .put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                    .put("capabilities",JSONArray(listOf("events","commands:target-bound:v1","reply","queue")))
                    .put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","ordering-thread")
                        .put("hostId",target.hostId).put("title","synthetic snapshot ordering").put("state","idle"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(first) { first=false;return initial }
                val response=CompletableDeferred<MobileHistory>();releases.add(response);histories.send(response)
                return response.await()
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({""}) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                requested.send(cursor);return batches.receive()
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader,commands) }
        main { session.connect("synthetic ordering","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.single()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.isNotEmpty() }
        runBlocking { withTimeout(10_000) { assertEquals(initial.cursor ?: 0L,requested.receive()) } }
    }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"running-snapshot-order") }
    }
    @After fun cleanup() {
        if(::owned.isInitialized)ui.runOnIdle { session.leaveForeground();releases.forEach { it.cancel() };owned.verifyDefault() }
    }
    private fun beginHistory(): CompletableDeferred<MobileHistory> {
        ui.runOnIdle { session.loadHistory() }
        return runBlocking { withTimeout(10_000) { histories.receive() } }
    }
    private fun deliver(cursor: Long,snapshot: MobileHistory?,ready: Boolean=true) {
        runBlocking {
            batches.send(EventBatch(target.hostId,"ordering-epoch",cursor,false,ready,null,listOfNotNull(snapshot)))
            withTimeout(10_000) { assertEquals(cursor,requested.receive()) }
        }
    }
    @Test fun delayedHistoryCannotReplaceActiveTurnOrRemoveEarlierControl() {
        connect(page(10,"idle",5))
        val response=beginHistory()
        deliver(11,page(11))
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("history-local-more").assertIsEnabled()
        ui.runOnIdle { response.complete(page(10,"idle",5)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertTrue(session.running);assertTrue(session.canStop);assertEquals(120,session.messages.size)
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
        ui.onNodeWithTag("history-local-more").assertIsEnabled()
    }
    @Test fun delayedRunningHistoryCannotRestartCompletedTurn() {
        connect(page(10))
        val response=beginHistory()
        deliver(12,page(12,"idle",121))
        ui.runOnIdle { response.complete(page(10)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertFalse(session.running);assertFalse(session.canStop);assertEquals(121,session.messages.size)
        ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        ui.onNodeWithTag("send-message").assertExists()
    }
    @Test fun delayedEventCannotReplaceNewerHistoryCompletion() {
        connect(page(10))
        val response=beginHistory()
        ui.runOnIdle { response.complete(page(12,"idle",121)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        deliver(11,page(11))
        assertFalse(session.running);assertEquals(121,session.messages.size)
        ui.onNodeWithTag("stop-turn").assertDoesNotExist()
    }
    @Test fun historyNewerThanConcurrentEventStillCompletesTask() {
        connect(page(10))
        val response=beginHistory()
        deliver(11,page(11))
        ui.runOnIdle { response.complete(page(12,"idle",121)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertFalse(session.running);assertEquals(121,session.messages.size)
    }
    @Test fun unversionedConcurrentHistoryCannotUndoActiveSnapshot() {
        connect(page(10,"idle",5,versioned=false))
        val response=beginHistory()
        deliver(11,page(11,versioned=false))
        ui.runOnIdle { response.complete(page(10,"idle",5,versioned=false)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertTrue(session.canStop);assertEquals(120,session.messages.size)
    }
    @Test fun earlierPageOnlyAddsPrefixAndCannotRemoveLiveRuntime() {
        connect(page(10,count=5).copy(nextCursor="older"))
        ui.runOnIdle { session.earlierMessages() }
        val response=runBlocking { withTimeout(10_000) { histories.receive() } }
        deliver(11,page(11,count=6))
        val earlier=HostReadCodec.history(JSONObject().put("hostId",target.hostId).put("threadId","ordering-thread")
            .put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray((0 until 2).map {
                JSONObject().put("id","older-$it").put("role","assistant").put("text","synthetic prefix $it").put("status","completed")
            }).put(JSONObject().put("id","row-5").put("role","assistant").put("text","stale overlap").put("status","completed"))).toString())
        ui.runOnIdle { response.complete(earlier) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertEquals(8,session.messages.size);assertTrue(session.canStop)
        assertEquals("synthetic row 5",session.messages.last().text)
        assertEquals("ordering-turn",session.runtime?.turnId);assertNull(session.historyCursor)
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
    }
    @Test fun staleUnreadyBatchCannotRevokeNewerSnapshotButCurrentLossStillDoes() {
        connect(page(10))
        val response=beginHistory()
        ui.runOnIdle { response.complete(page(12)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        deliver(11,null,ready=false)
        assertTrue(session.canStop)
        deliver(13,null,ready=false)
        assertFalse(session.canStop)
        deliver(14,page(14))
        ui.waitUntil(10_000) { session.canStop }
    }
    @Test fun earlierPageCannotRestoreControlAfterCurrentReadinessLoss() {
        connect(page(10,count=5).copy(nextCursor="older"))
        ui.runOnIdle { session.earlierMessages() }
        val response=runBlocking { withTimeout(10_000) { histories.receive() } }
        deliver(11,null,ready=false)
        assertFalse(session.canStop)
        ui.runOnIdle { response.complete(page(10,count=5)) }
        ui.waitUntil(10_000) { !session.historyLoading }
        // Refresh the catalog so it cannot hide an incorrectly regranted history proof.
        ui.runOnIdle { session.refresh() }
        ui.waitUntil(10_000) { !session.loading }
        assertFalse(session.canStop)
        deliver(12,page(12,count=6))
        ui.waitUntil(10_000) { session.canStop }
    }
}
