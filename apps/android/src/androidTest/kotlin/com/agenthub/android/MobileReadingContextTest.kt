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

/** Same presentation target is distinct from a fresh network navigation or connection proof. */
@RunWith(AndroidJUnit4::class)
class MobileReadingContextTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private var runtimeId="context-runtime-one"
    private val scope get()=TargetScope("codex",null,null,"context-host","context-workspace",runtimeId)
    private val reads=AtomicInteger()
    private var windowed=false
    private var handler: (suspend (Int,MobileClient,String,String?)->MobileHistory)?=null
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    private fun gate()=CompletableDeferred<Unit>().also { releases.add(it) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun page(client: MobileClient,thread: String,cursor: String?,call: Int): MobileHistory {
        val range=if(windowed)if(cursor==null)60 until 120 else 0 until 60 else 0 until 120
        val json=JSONObject().put("hostId",scope.hostId).put("threadId",thread).put("readTarget",CodexCommandCodec.target(client.scope!!))
            .put("messages",JSONArray(range.map { JSONObject().put("id","$thread-$it").put("role","assistant").put("text","read-$call row-$it").put("status","completed") }))
        if(windowed)json.put("historyWindow",JSONObject().put("version",1).put("kind",if(cursor==null)"latest" else "earlier"))
            .put("historyRevision",1).put("nextCursor",if(cursor==null)"earlier" else JSONObject.NULL)
        return HostReadCodec.history(json.toString())
    }
    private fun setup() {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","synthetic reading context").put("connectionMode","history-only").put("readTarget",CodexCommandCodec.target(scope))
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("context-a","context-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val call=reads.incrementAndGet();return handler?.invoke(call,client,threadId,cursor) ?: page(client,threadId,cursor,call)
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader) }
        main { session.connect("synthetic context","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.isNotEmpty() }
    }
    private fun expand() {
        main { session.earlierMessages()?.join();if(windowed)session.earlierMessages()?.join();session.text="expanded context draft" }
        ui.waitUntil(5_000) { !session.historyLoading&&session.messages.size==120 }
        assertTrue(session.timeline.rows.size>60)
    }
    @After fun stop() { releases.forEach { it.complete(Unit) };ui.runOnIdle { session.leaveForeground() } }
    @Test fun sameClientReselectKeepsExpandedPresentationAndDraft() {
        setup();expand();val limit=session.visibleMessageLimit;val ids=session.timeline.rows.map { it.id }
        ui.runOnIdle { session.select(session.selectedClient) };ui.waitUntil(5_000) { reads.get()>=2&&!session.historyLoading }
        assertEquals(limit,session.visibleMessageLimit);assertEquals(ids,session.timeline.rows.map { it.id });assertTrue(session.revealedEarlier)
        assertEquals("context-a",session.selectedThread);assertEquals("expanded context draft",session.text)
    }
    @Test fun verifiedReconnectKeepsSameTargetExpandedPresentation() {
        setup();expand();val limit=session.visibleMessageLimit;val key=session.targetKey
        main { session.connect("reconnected context","https://fixture.invalid","",session.selectedClient) }
        ui.waitUntil(5_000) { reads.get()>=2&&!session.historyLoading }
        assertEquals(key,session.targetKey);assertEquals(limit,session.visibleMessageLimit);assertTrue(session.revealedEarlier)
        assertEquals("expanded context draft",session.text);assertEquals("context-a",session.selectedThread)
    }
    @Test fun newNavigationStartsFreshHistoryBeforeCancelledOldReadReturns() {
        setup();val started=gate();val release=gate()
        handler={call,client,thread,cursor ->
            if(call==2)withContext(NonCancellable) { started.complete(Unit);release.await() }
            page(client,thread,cursor,call)
        }
        ui.runOnIdle { session.text="new navigation draft";session.loadHistory() }
        runBlocking { withTimeout(5_000) { started.await() } }
        ui.runOnIdle { session.select(session.selectedClient) }
        ui.waitUntil(5_000) { reads.get()==3&&!session.historyLoading }
        release.complete(Unit);ui.waitForIdle()
        assertTrue(session.messages.all { it.text.startsWith("read-3 ") });assertEquals("new navigation draft",session.text)
    }
    @Test fun oldReadFinallyCannotReleaseNewNavigationReadSlot() {
        setup();val oldStarted=gate();val oldRelease=gate();val oldExited=gate();val newStarted=gate();val newRelease=gate()
        handler={call,client,thread,cursor ->
            if(call==2)try { withContext(NonCancellable) { oldStarted.complete(Unit);oldRelease.await() } } finally { oldExited.complete(Unit) }
            if(call==3) { newStarted.complete(Unit);newRelease.await() }
            page(client,thread,cursor,call)
        }
        ui.runOnIdle { session.loadHistory() };runBlocking { withTimeout(5_000) { oldStarted.await() } }
        ui.runOnIdle { session.select(session.selectedClient) };runBlocking { withTimeout(5_000) { newStarted.await() } }
        oldRelease.complete(Unit);runBlocking { withTimeout(5_000) { oldExited.await() } };ui.runOnIdle {}
        assertTrue("Old finally must not clear another read owner",session.historyLoading)
        ui.runOnIdle { session.loadHistory() };assertEquals("Current navigation still coalesces its original GET",3,reads.get())
        newRelease.complete(Unit);ui.waitUntil(5_000) { !session.historyLoading };assertTrue(session.messages.all { it.text.startsWith("read-3 ") })
    }
    @Test fun verifiedReconnectLatestWindowDoesNotBorrowOldPagedPrefixOrCursor() {
        windowed=true;setup();expand();assertNull(session.historyCursor)
        main { session.connect("windowed reconnect","https://fixture.invalid","",session.selectedClient) }
        ui.waitUntil(5_000) { !session.historyLoading&&reads.get()>=3 }
        assertEquals((60 until 120).map { "context-a-$it" },session.messages.map { it.id });assertEquals("earlier",session.historyCursor)
        assertEquals("expanded context draft",session.text)
    }
    @Test fun changedRuntimeTargetGetsSeparateDraftAndPresentation() {
        setup();val initial=session.visibleMessageLimit;expand();val firstKey=session.targetKey
        runtimeId="context-runtime-two";main { session.connect("other runtime","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first()) };ui.waitUntil(5_000) { !session.historyLoading }
        assertNotEquals(firstKey,session.targetKey);assertEquals(initial,session.visibleMessageLimit);assertFalse(session.revealedEarlier);assertEquals("",session.text)
        runtimeId="context-runtime-one";main { session.connect("original runtime","https://fixture.invalid","") }
        ui.waitUntil(5_000) { !session.historyLoading };assertEquals(firstKey,session.targetKey);assertEquals("expanded context draft",session.text)
    }
}
