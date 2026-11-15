package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Opt-in read-only real persisted transcript -> original-owner transition. No model input. */
class NativeHistoryTransitionLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @After fun stop() { if(::owned.isInitialized)ui.runOnIdle { session.leaveForeground();owned.verifyDefault() } }
    @Test fun persistedRowsRemainOnceThroughRealOwnerEventsAndRefresh() {
        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        val file=File(i.targetContext.filesDir,"acceptance/history-transition-live.json")
        Assume.assumeTrue("Private read-only original-desktop fixture required",file.exists())
        val f=JSONObject(file.readText(Charsets.UTF_8))
        require(java.net.URI(f.getString("endpoint")).host=="127.0.0.1")
        val persisted=HostReadCodec.history(f.getJSONObject("persisted").toString())
        val id=f.getString("threadId");val token=f.getString("token")
        require(persisted.hostId==f.getString("hostId")&&persisted.threadId==id)
        val base=NativeHostReads { token };val commands=NativeCodexCommands { token }
        val release=CompletableDeferred<Unit>();val delivered=CompletableDeferred<Unit>()
        var first=true
        val reader=object: HostReads by base {
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(first&&threadId==id&&cursor==null) { first=false;delivered.complete(Unit);return persisted }
                return base.history(client,threadId,cursor)
            }
        }
        val gated=object: CodexCommands by commands {
            override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
                release.await();return commands.events(client,threadId,epoch,cursor)
            }
        }
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"history-transition-live");session.resetAcceptance(reader,gated);session.changeLanguage("en") }
        main { session.connect("USB isolated original desktop",f.getString("endpoint"),token) }
        ui.waitUntil(30_000) { !session.loading&&session.threads.any { it.id==id } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==id }) }
        ui.waitUntil(30_000) { delivered.isCompleted&&!session.historyLoading&&session.messages.size==persisted.messages.size }
        assertEquals(persisted.messages.map { it.id },session.messages.map { it.id })
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"persisted-before-live")
        ui.runOnIdle { release.complete(Unit) }
        ui.waitUntil(60_000) { session.runtime?.canControl==true&&!session.historyLoading }
        fun assertRows() {
            assertEquals("Exactly the real canonical rows, no repeated historical prefix",persisted.messages.size,session.messages.size)
            assertEquals(persisted.messages.map { it.id },session.messages.map { it.id })
            assertEquals(session.messages.size,session.messages.map { it.id }.toSet().size)
            persisted.messages.forEach { old -> assertEquals(old.text,session.messages.single { it.id==old.id }.text) }
            assertTrue(session.operationRecords.isEmpty())
        }
        assertRows()
        repeat(3) {
            val job=ui.runOnIdle { session.loadHistory(userInitiated=true) }
            runBlocking { job?.join() };ui.waitForIdle();assertRows()
        }
        owned.nodeShot(ui.onNodeWithTag("product-surface"),"live-after-three-refreshes")
        File(i.targetContext.filesDir,"acceptance/history-transition-live-result.json").writeText(JSONObject()
            .put("threadId",id).put("hostId",session.selectedClient.id).put("canonicalRows",persisted.messages.size)
            .put("historicalRowsOnce",true).put("refreshes",3).put("newInputs",0).put("folder",owned.folder.absolutePath).toString(),Charsets.UTF_8)
    }
}
