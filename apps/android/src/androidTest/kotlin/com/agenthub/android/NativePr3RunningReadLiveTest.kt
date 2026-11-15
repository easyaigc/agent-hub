package com.agenthub.android

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in read-only observation of an already running original desktop task. */
class NativePr3RunningReadLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private var observer: Job?=null
    private var scope: CoroutineScope?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @After fun stop() {
        observer?.cancel();scope?.cancel()
        if(::owned.isInitialized)ui.runOnIdle { session.leaveForeground();owned.verifyDefault() }
    }
    @Test fun realRunningTaskKeepsStopAndEarlierControlsThroughConcurrentReads() {
        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val file=File(i.targetContext.filesDir,"acceptance/pr3-running-live.json")
        Assume.assumeTrue("Private read-only running fixture required",file.exists())
        val f=JSONObject(file.readText(Charsets.UTF_8))
        require(java.net.URI(f.getString("endpoint")).host=="127.0.0.1")
        val id=f.getString("threadId");val turn=f.getString("turnId");val token=f.getString("token")
        val historyReads=AtomicInteger();val eventReads=AtomicInteger();val snapshotReads=AtomicInteger()
        val wire=CopyOnWriteArrayList<JSONObject>();val changes=CopyOnWriteArrayList<JSONObject>()
        val base=NativeHostReads { token };val native=NativeCodexCommands { token }
        val reader=object: HostReads by base {
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val page=base.history(client,threadId,cursor);historyReads.incrementAndGet()
                wire.add(JSONObject().put("kind","history").put("cursor",page.cursor).put("state",page.state).put("turnId",page.turnId).put("rows",page.messages.size))
                return page
            }
        }
        val commands=object: CodexCommands by native {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                val batch=native.events(client,threadId,epoch,cursor);eventReads.incrementAndGet();snapshotReads.addAndGet(batch.snapshots.size)
                wire.add(JSONObject().put("kind","events").put("cursor",batch.cursor).put("ready",batch.ready).put("snapshots",batch.snapshots.size))
                return batch
            }
        }
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"pr3-running-read");session.resetAcceptance(reader,commands);session.changeLanguage("en") }
        main { session.connect("USB isolated running desktop",f.getString("endpoint"),token) }
        ui.waitUntil(30_000) { !session.loading&&session.threads.any { it.id==id } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==id }) }
        ui.waitUntil(60_000) { !session.historyLoading&&session.canStop&&session.timeline.hasEarlierMessages }
        assertEquals(turn,session.runtime?.turnId);assertTrue(session.operationRecords.isEmpty())
        val invalidations=AtomicInteger()
        main {
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            observer=scope!!.launch { snapshotFlow { Triple(session.canStop,session.isWritable,session.timeline.hasEarlierMessages) }.collect { state ->
                changes.add(JSONObject().put("ms",android.os.SystemClock.elapsedRealtime()).put("canStop",state.first).put("writable",state.second).put("earlier",state.third))
                if(!state.first||!state.second||!state.third)invalidations.incrementAndGet()
            } }
        }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("history-local-more").assertIsEnabled()
        ui.onNodeWithTag("stop-turn").assertIsEnabled()
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"running-controls-before")
        val started=android.os.SystemClock.elapsedRealtime()
        var requested=0
        repeat(30) { step ->
            if(step%8==0) { ui.runOnIdle { if(!session.historyLoading) { session.loadHistory(userInitiated=true);requested++ } } }
            runBlocking { delay(1_000) }
            ui.runOnIdle {
                assertEquals(id,session.selectedThread);assertEquals(turn,session.runtime?.turnId)
                assertTrue(session.canStop);assertTrue(session.isWritable);assertTrue(session.timeline.hasEarlierMessages)
                assertTrue(session.operationRecords.isEmpty());assertNull(session.error)
            }
            ui.onNodeWithTag("stop-turn").assertIsEnabled()
            ui.onNodeWithTag("history-local-more").assertIsEnabled()
        }
        ui.waitUntil(30_000) { !session.historyLoading }
        assertEquals(0,invalidations.get());assertTrue(historyReads.get()>=3);assertTrue(eventReads.get()>=2);assertTrue(requested>=2)
        val limit=session.visibleMessageLimit
        ui.onNodeWithTag("history-local-more").performClick()
        ui.waitUntil(5_000) { session.visibleMessageLimit>limit }
        assertTrue(session.canStop);assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("history-local-more").assertIsEnabled();ui.onNodeWithTag("stop-turn").assertIsEnabled()
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"running-controls-after")
        File(i.targetContext.filesDir,"acceptance/pr3-running-live-result.json").writeText(JSONObject()
            .put("threadId",id).put("turnId",turn).put("hostId",session.selectedClient.id)
            .put("observedMs",android.os.SystemClock.elapsedRealtime()-started).put("historyReads",historyReads.get())
            .put("eventReads",eventReads.get()).put("eventSnapshots",snapshotReads.get()).put("controlInvalidations",invalidations.get())
            .put("newOperations",session.operationRecords.size).put("localEarlierExpanded",true)
            .put("states",JSONArray(changes)).put("wire",JSONArray(wire)).put("folder",owned.folder.absolutePath).toString(),Charsets.UTF_8)
    }
}
