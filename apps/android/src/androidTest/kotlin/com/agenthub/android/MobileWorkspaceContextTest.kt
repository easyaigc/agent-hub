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

/** Client navigation owns catalogs; changing just its selected chat must share the catalog. */
@RunWith(AndroidJUnit4::class)
class MobileWorkspaceContextTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"catalog-context-host","catalog-context-workspace","catalog-context-runtime")
    private val catalogs=AtomicInteger()
    private var handler: (suspend (Int)->Unit)?=null
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    private fun gate()=CompletableDeferred<Unit>().also { releases.add(it) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup() {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                val call=catalogs.incrementAndGet();handler?.invoke(call)
                return HostReadCodec.workspace(JSONObject().put("hostId",scope.hostId).put("name","catalog-$call").put("connectionMode","history-only")
                    .put("readTarget",CodexCommandCodec.target(scope)).put("projects",JSONArray()).put("threads",JSONArray(listOf("catalog-a","catalog-b").map {
                        JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") })).toString())
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
                .put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                .put("messages",JSONArray().put(JSONObject().put("id",threadId+"-row").put("role","assistant").put("text",threadId+" content").put("status","completed"))).toString())
        }
        ui.runOnIdle { session.resetAcceptance(reader) }
        main { session.connect("synthetic catalog context","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="catalog context draft" }
        ui.waitUntil(5_000) { !session.loading&&!session.historyLoading&&session.messages.isNotEmpty() }
    }
    @After fun stop() { releases.forEach { it.complete(Unit) };ui.runOnIdle { session.leaveForeground() } }
    @Test fun clientReselectionStartsFreshCatalogBeforeCancelledOldReadReturns() {
        setup();val started=gate();val release=gate()
        handler={call -> if(call==2)withContext(NonCancellable) { started.complete(Unit);release.await() } }
        ui.runOnIdle { session.refresh() };runBlocking { withTimeout(5_000) { started.await() } }
        ui.runOnIdle { session.select(session.selectedClient) }
        ui.waitUntil(5_000) { catalogs.get()==3&&!session.loading&&session.workspace?.name=="catalog-3" }
        release.complete(Unit);ui.runOnIdle {};assertEquals("catalog-3",session.workspace?.name);assertEquals("catalog context draft",session.text)
    }
    @Test fun oldCatalogFinallyCannotReleaseNewClientNavigationSlot() {
        setup();val oldStarted=gate();val oldRelease=gate();val oldExited=gate();val newStarted=gate();val newRelease=gate()
        handler={call ->
            if(call==2)try { withContext(NonCancellable) { oldStarted.complete(Unit);oldRelease.await() } } finally { oldExited.complete(Unit) }
            if(call==3) { newStarted.complete(Unit);newRelease.await() }
        }
        ui.runOnIdle { session.refresh() };runBlocking { withTimeout(5_000) { oldStarted.await() } }
        ui.runOnIdle { session.select(session.selectedClient) };runBlocking { withTimeout(5_000) { newStarted.await() } }
        oldRelease.complete(Unit);runBlocking { withTimeout(5_000) { oldExited.await() } };ui.runOnIdle {}
        assertTrue("Old catalog completion cannot release the current slot",session.loading)
        ui.runOnIdle { session.refresh() };assertEquals(3,catalogs.get())
        newRelease.complete(Unit);ui.waitUntil(5_000) { !session.loading };assertEquals("catalog-3",session.workspace?.name)
    }
    @Test fun changingOnlyThreadKeepsTheOriginalCatalogReadAndConnectionProof() {
        setup();val started=gate();val release=gate()
        handler={call -> if(call==2) { started.complete(Unit);release.await() } }
        ui.runOnIdle { session.refresh() };runBlocking { withTimeout(5_000) { started.await() } }
        ui.runOnIdle { session.selectThread(session.threads.last());session.text="other chat draft" }
        assertEquals(2,catalogs.get());assertTrue(session.loading)
        release.complete(Unit);ui.waitUntil(5_000) { !session.loading&&!session.historyLoading }
        assertEquals("catalog-2",session.workspace?.name);assertEquals("catalog-b",session.selectedThread);assertTrue(session.selectedClient.online);assertEquals("other chat draft",session.text)
    }
    @Test fun verifiedReconnectKeepsNewCatalogReadOwnedAfterOldCompletion() {
        setup();val oldStarted=gate();val oldRelease=gate();val oldExited=gate();val newStarted=gate();val newRelease=gate()
        handler={call ->
            if(call==2)try { withContext(NonCancellable) { oldStarted.complete(Unit);oldRelease.await() } } finally { oldExited.complete(Unit) }
            if(call==4) { newStarted.complete(Unit);newRelease.await() }
        }
        ui.runOnIdle { session.refresh() };runBlocking { withTimeout(5_000) { oldStarted.await() } }
        main { session.connect("revalidated catalog","https://fixture.invalid","",session.selectedClient) }
        assertEquals("catalog-3",session.workspace?.name)
        ui.runOnIdle { session.refresh() };runBlocking { withTimeout(5_000) { newStarted.await() } }
        oldRelease.complete(Unit);runBlocking { withTimeout(5_000) { oldExited.await() } };ui.runOnIdle {}
        assertTrue(session.loading);assertEquals("catalog-3",session.workspace?.name)
        ui.runOnIdle { session.refresh() };assertEquals(4,catalogs.get())
        newRelease.complete(Unit);ui.waitUntil(5_000) { !session.loading };assertEquals("catalog-4",session.workspace?.name);assertEquals("catalog context draft",session.text)
    }
}
