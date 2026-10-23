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
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MobileReadSchedulingTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun workspace()=HostReadCodec.workspace(JSONObject().put("hostId","scheduling-host").put("name","synthetic read scheduling").put("connectionMode","history-only")
        .put("projects",JSONArray()).put("threads",JSONArray(listOf("old","new").map { JSONObject().put("id",it).put("hostId","scheduling-host").put("title",it).put("state","idle") })).toString())
    private fun history(thread: String,count: Int=1)=HostReadCodec.history(JSONObject().put("hostId","scheduling-host").put("threadId",thread)
        .put("messages",JSONArray((0 until count).map { JSONObject().put("id","$thread-$it").put("role","assistant").put("text","synthetic $thread row $it").put("status","completed") })).toString())
    private fun connect(reader: HostReads) {
        ui.runOnIdle { session.resetAcceptance(reader) }
        main { session.connect("synthetic scheduling","https://fixture.invalid","") }
        ui.waitUntil(10_000) { session.hasClient && !session.loading }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun reselectSameThreadKeepsItsInflightHistoryAndDraft() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val requests=AtomicInteger();val cancelled=AtomicInteger()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                requests.incrementAndGet();started.complete(Unit)
                try { release.await() } finally { if(!currentCoroutineContext().isActive)cancelled.incrementAndGet() }
                return history(threadId)
            }
        }
        connect(reader)
        try {
            ui.runOnIdle { session.selectThread(session.threads.first());session.text="kept while reading" }
            runBlocking { withTimeout(10_000) { started.await() } }
            ui.runOnIdle { session.selectThread(session.threads.first());session.selectThread(session.threads.first()) }
            assertEquals("One original GET remains in flight",1,requests.get())
            assertEquals("Presentation navigation must not cancel history",0,cancelled.get())
            release.complete(Unit)
            ui.waitUntil(10_000) { !session.historyLoading && session.messages.singleOrNull()?.id=="old-0" }
            assertEquals("kept while reading",session.text);assertEquals(1,requests.get())
        } finally { release.complete(Unit) }
    }
    @Test fun reselectSameThreadPreservesExpandedReadingWindow() {
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId,120)
        })
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && session.messages.size==120 }
        ui.runOnIdle { session.earlierMessages();session.text="expanded window draft" }
        val visible=session.timeline.rows.map { it.id };assertTrue(visible.size>60)
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertEquals("Retap does not collapse earlier rows",visible,session.timeline.rows.map { it.id })
        assertEquals("expanded window draft",session.text)
    }
    @Test fun differentThreadStillRejectsLateHistoryFromOldSelection() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(threadId=="old")withContext(NonCancellable) { started.complete(Unit);release.await() }
                return history(threadId)
            }
        })
        try {
            ui.runOnIdle { session.selectThread(session.threads.first()) }
            runBlocking { withTimeout(10_000) { started.await() } }
            ui.runOnIdle { session.selectThread(session.threads.last());session.text="new target draft" }
            ui.waitUntil(10_000) { session.messages.singleOrNull()?.id=="new-0" }
            release.complete(Unit);ui.waitForIdle()
            assertEquals("new",session.selectedThread);assertEquals("new-0",session.messages.single().id)
            assertEquals("new target draft",session.text)
        } finally { release.complete(Unit) }
    }
    private fun lateHistoryFailureAfterWorkspaceSuccess(transient: Boolean) {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var fail=false
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=workspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(fail) { started.complete(Unit);release.await();throw HostReadError(if(transient) "synthetic timeout" else "synthetic authentication rejected",transient=transient) }
                return history(threadId)
            }
        })
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && session.messages.isNotEmpty() }
        val cached=session.messages
        try {
            ui.runOnIdle { fail=true;session.text="kept after late failure";session.loadHistory(userInitiated=true) }
            runBlocking { withTimeout(10_000) { started.await() } }
            ui.runOnIdle { session.refresh() };ui.waitUntil(10_000) { !session.loading }
            assertTrue(session.selectedClient.online)
            release.complete(Unit);ui.waitUntil(10_000) { !session.historyLoading }
            assertEquals("Only a transient inference is disproved by newer success",transient,session.selectedClient.online)
            assertEquals(cached,session.messages);assertEquals("kept after late failure",session.text)
            assertTrue("Explicit history failure stays actionable",session.error?.startsWith("加载历史失败，请重试。") == true)
        } finally { release.complete(Unit) }
    }
    @Test fun newerWorkspaceSuccessDisprovesOlderHistoryTimeout()=lateHistoryFailureAfterWorkspaceSuccess(true)
    @Test fun newerWorkspaceSuccessNeverOverridesAuthenticationFailure()=lateHistoryFailureAfterWorkspaceSuccess(false)
    @Test fun newerHistorySuccessDisprovesOlderWorkspaceTimeout() {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var fail=false
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                if(fail) { started.complete(Unit);release.await();throw HostReadError("synthetic old catalog timeout",transient=true) }
                return workspace()
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId)
        })
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && session.messages.isNotEmpty() }
        try {
            ui.runOnIdle { fail=true;session.text="catalog wait draft";session.refresh(userInitiated=true) }
            runBlocking { withTimeout(10_000) { started.await() } }
            main { session.loadHistory()?.join() }
            release.complete(Unit);ui.waitUntil(10_000) { !session.loading }
            assertTrue("A completed authenticated history response disproves stale catalog timeout",session.selectedClient.online)
            assertEquals("catalog wait draft",session.text);assertEquals("old-0",session.messages.single().id)
            assertTrue(session.error?.startsWith("刷新失败，请重试。") == true)
        } finally { release.complete(Unit) }
    }
    @Test fun verifiedConnectionPublishesItsWorkspaceWithoutASecondCatalogRead() {
        val requests=AtomicInteger()
        connect(object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace { requests.incrementAndGet();return workspace() }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=history(threadId)
        })
        assertEquals("The authenticated connection already supplied this catalog",1,requests.get())
        assertEquals("scheduling-host",session.workspace!!.hostId);assertEquals(2,session.threads.size)
        assertTrue(session.selectedClient.online)
    }
    private val eventScope get()=TargetScope("codex",null,null,"scheduling-host","scheduling-workspace","desktop-runtime")
    private fun eventWorkspace()=HostReadCodec.workspace(JSONObject(workspace().wire).put("connectionMode","desktop-runtime")
        .put("readTarget",CodexCommandCodec.target(eventScope)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
        .put("capabilities",JSONArray(listOf("events","commands:target-bound:v1"))).toString())
    private fun eventHistory(thread: String)=HostReadCodec.history(JSONObject().put("hostId","scheduling-host").put("threadId",thread)
        .put("readTarget",CodexCommandCodec.target(eventScope)).put("messages",JSONArray().put(JSONObject().put("id","$thread-0")
            .put("role","assistant").put("text","synthetic event history").put("status","completed"))).toString())
    @Test fun verifiedReconnectUsesResetSnapshotWithoutRedundantHistory() {
        val catalogs=AtomicInteger();val histories=AtomicInteger();val events=AtomicInteger()
        val release=CompletableDeferred<Unit>();val delivered=AtomicBoolean()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace { catalogs.incrementAndGet();return eventWorkspace() }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory { histories.incrementAndGet();return eventHistory(threadId) }
        }
        val command=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                events.incrementAndGet();release.await()
                if(!delivered.compareAndSet(false,true))awaitCancellation()
                val page=eventHistory(threadId).copy(messages=listOf(MobileMessage("event-first","assistant","validated first snapshot")))
                return EventBatch(client.id,"synthetic-epoch",1,true,true,null,listOf(page))
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader,command) }
        try {
            main { session.connect("event-first","https://fixture.invalid","") }
            ui.runOnIdle { session.selectThread(session.threads.first()) }
            ui.waitUntil(10_000) { !session.historyLoading && events.get()>0 }
            val before=histories.get();val beforeEvents=events.get()
            main { session.connect("event-first","https://fixture.invalid","",session.selectedClient) }
            ui.waitUntil(10_000) { events.get()>beforeEvents }
            assertEquals("Reconnect gives events the first catch-up opportunity",before,histories.get())
            release.complete(Unit)
            ui.waitUntil(10_000) { delivered.get() && !session.historyLoading }
            assertEquals("Reset already contains the authoritative snapshot",before,histories.get())
            assertEquals("event-first",session.messages.single().id);assertEquals(2,catalogs.get())
        } finally { release.complete(Unit);ui.runOnIdle { session.leaveForeground() } }
    }
    @Test fun verifiedReconnectFallsBackAfterBoundedEventGrace() {
        val histories=AtomicInteger();val catalogs=AtomicInteger()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace { catalogs.incrementAndGet();return eventWorkspace() }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory { histories.incrementAndGet();return eventHistory(threadId) }
        }
        val command=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch=awaitCancellation()
        }
        ui.runOnIdle { session.resetAcceptance(reader,command) }
        main { session.connect("bounded events","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading && session.messages.isNotEmpty() }
        val before=histories.get();val started=System.nanoTime()
        main { session.connect("bounded events","https://fixture.invalid","",session.selectedClient) }
        ui.waitUntil(20_000) { histories.get()>before && !session.historyLoading }
        val elapsed=(System.nanoTime()-started)/1_000_000
        assertTrue("Catch-up grace cannot become an immediate duplicate GET: $elapsed ms",elapsed>=7_500)
        assertTrue("Absent event snapshot must not starve history: $elapsed ms",elapsed<=20_000)
        assertEquals(before+1,histories.get());assertEquals(2,catalogs.get())
    }
}
