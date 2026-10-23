package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileQueueMetadataUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() { ui.waitUntil(10_000) { !session.restoring };ui.runOnIdle { session.resetAcceptance() } }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val frames=CountDownLatch(1);ui.runOnUiThread { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS));i.uiAutomation.waitForIdle(300,5_000)
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=InstrumentationRegistry.getArguments().getString("managementRunId") ?: UUID.randomUUID().toString();require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(i.targetContext.filesDir,"acceptance/P4-queue-metadata/$run").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @Test fun originalClientQueuePresenceAndAcceptedSyncErrorsStaySeparateFromDraftAndDismissal() {
        QueueMetadataFixture().use { f ->
            try {
                main { session.connect("queue metadata UI",f.server.endpoint,"") }
                ui.waitUntil(10_000) { session.hasClient&&!session.loading }
                main { session.selectThread(session.threads.single()) }
                ui.waitUntil(10_000) { session.runtime!=null&&!session.historyLoading }
                main { session.text="independent current unsent draft" }
                assertEquals(listOf("native-original","old-compatible"),session.queuedReplies.map { it.operationId })
                assertEquals(listOf("accepted-sync","failed-sync"),session.receiptNotices.map { it.operationId })
                ui.onNodeWithTag("queued-native-original").performScrollTo().assertIsDisplayed()
                ui.onNodeWithTag("queued-old-compatible").assertExists()
                for(id in listOf("legacy-host","legacy-disposition","removed-native"))ui.onNodeWithTag("queued-$id").assertDoesNotExist()
                shot("native-present-only-no-host-fifo")
                ui.onNodeWithTag("receipt-accepted-sync").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("accepted original client sync failure").assertExists();shot("accepted-sync-failure-visible")
                ui.onNodeWithTag("dismiss-receipt-failed-sync").performScrollTo().performClick()
                // A local failure dismissal cannot hide unresolved client synchronization evidence.
                ui.onNodeWithTag("receipt-failed-sync").assertExists()
                assertEquals("independent current unsent draft",session.text)
                f.syncErrors=false;main { session.loadHistory() }
                ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.id=="phase-clear" } }
                ui.onNodeWithTag("receipt-accepted-sync").assertDoesNotExist();ui.onNodeWithTag("receipt-failed-sync").assertDoesNotExist()
                assertEquals(listOf("native-original","old-compatible"),session.queuedReplies.map { it.operationId })
                assertEquals("independent current unsent draft",session.text);assertTrue(session.operationRecords.isEmpty())
                assertTrue("Metadata reads and local dismissal must never submit queue/input commands",f.server.requests.all { it.method=="GET" })
            } finally { main { session.leaveForeground() } }
        }
    }
}
private class QueueMetadataFixture: java.io.Closeable {
    @Volatile var syncErrors=true
    private val runtime="queue-ui-runtime-"+UUID.randomUUID()
    val server=CommandFixture(::response)
    private fun target()=JSONObject().put("agentId","codex").put("hostId","queue-ui-host").put("workspaceId","queue-ui-workspace").put("runtimeId",runtime)
    private fun receipt(id: String,state: String="queued")=JSONObject().put("hostId","queue-ui-host").put("threadId","queue-ui-thread").put("clientMessageId",id)
        .put("state",state).put("turnId",if(state=="queued")JSONObject.NULL else "original-turn").put("error",JSONObject.NULL).put("text",id)
    private fun response(r: WireRequest): WireResponse {
        val body=when(r.path) {
            "/v1/workspace"->JSONObject().put("hostId","queue-ui-host").put("name","queue metadata fixture").put("connectionMode","desktop-runtime").put("readTarget",target())
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject()
                    .put("hostId","queue-ui-host").put("id","queue-ui-thread").put("title","queue fixture").put("state","idle")))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1","queue","queue:execute")))
            "/v1/history"-> {
                val receipts=JSONArray().put(receipt("native-original").put("queueSource","native").put("queuePresent",true).put("canExecuteNow",false))
                    .put(receipt("old-compatible")).put(receipt("legacy-host").put("queueSource","host"))
                    .put(receipt("legacy-disposition").put("queueSource","native").put("queueDisposition","legacy-review"))
                    .put(receipt("removed-native").put("queueSource","native").put("queuePresent",false))
                for((id,state) in listOf("accepted-sync" to "submitted","failed-sync" to "failed"))receipts.put(receipt(id,state).put("queueSource","native")
                    .put("supersededByTurnId","later-turn").apply { if(syncErrors)put("queueSyncError",if(id=="accepted-sync")"accepted original client sync failure" else "failed original client sync failure") })
                receipts.put(receipt("later-success","completed"))
                JSONObject().put("hostId","queue-ui-host").put("threadId","queue-ui-thread").put("readTarget",target())
                    .put("messages",JSONArray().put(JSONObject().put("id",if(syncErrors)"phase-errors" else "phase-clear").put("role","assistant").put("text","queue metadata phase")))
                    .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                        .put("approvals",JSONArray()).put("receipts",receipts).put("canControl",true))
            }
            else->return WireResponse(status=404)
        }
        return WireResponse(body=body.toString())
    }
    override fun close() { server.close() }
}
