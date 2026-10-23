package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic responses over actual HTTP; native dismissal and encrypted restoration are real. */
@RunWith(AndroidJUnit4::class)
class MobileReceiptNoticeTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() { ui.runOnIdle { session.resetAcceptance() } }
    private fun connect(f: NoticeFixture) {
        main { session.connect("失败提示验收",f.server.endpoint,"") }
        ui.waitUntil(15_000) { session.hasClient && !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(15_000) { session.runtime!=null && !session.historyLoading }
    }
    private fun phase(f: NoticeFixture,value: String) {
        f.phase=value; ui.runOnIdle { session.loadHistory() }
        ui.waitUntil(15_000) { !session.historyLoading && session.messages.any { it.id=="phase-$value" } }
    }
    private fun showHistoricalFailure() {
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("task-failure-history-turn-failure-old"))
        ui.onNodeWithTag("task-failure-history-turn-failure-old").assertIsDisplayed()
    }
    private fun shot(name: String) {
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitForIdle(); val i=InstrumentationRegistry.getInstrumentation(); i.uiAutomation.waitForIdle(700,10_000)
        val bitmap=i.uiAutomation.takeScreenshot(); val dir=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-receipts").apply { mkdirs() }
        try { File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
    }
    @Test fun nativeRejectedSubmissionRetainsDraftAndHasNoTurn() {
        // No Activity replacement in this case: do not enter a platform-idle
        // Activity lookup while the sharing sheet is settling.
        val session=this.session
        NoticeFixture("rejected").use { f -> connect(f)
            ui.runOnIdle { session.text="被拒绝的原内容" }; ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(5_000) { session.aiSharingRequest!=null }
            assertEquals(0,f.server.requests.count { it.method=="POST" })
            assertEquals("被拒绝的原内容",session.text)
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
            ui.waitUntil(5_000) { session.aiSharingRequest==null }
            assertEquals("Consent cannot submit the input",0,f.server.requests.count { it.method=="POST" })
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(15_000) { session.runtime?.receipts?.singleOrNull()?.operationId==f.submittedId && session.transferStatus==null }
            ui.onNodeWithText("消息提交失败").assertIsDisplayed(); ui.onNodeWithText("任务执行失败").assertDoesNotExist()
            assertEquals("被拒绝的原内容",session.text); assertNull(session.runtime!!.receipts.single().turnId)
            assertTrue(session.operationRecords.any { it.id==f.submittedId && it.phase=="failed" && it.result!=null && it.submissionAttempted })
            ui.onNodeWithTag("operation-${f.submittedId}").assertDoesNotExist()
            assertEquals(1,f.server.requests.count { it.method=="POST" }); shot("submission-rejected-draft")
        }
    }
    @Test fun historicalFailureAndUnknownStayIndependentOfDismissalAndNewTurn() {
        NoticeFixture("task-failed").use { f -> connect(f)
            ui.onNodeWithText("任务执行失败").assertIsDisplayed(); ui.onNodeWithText("消息提交失败").assertDoesNotExist()
            showHistoricalFailure(); shot("task-failed-current")
            val expected=session.targetKey
            ui.onNodeWithTag("dismiss-receipt-shared-id").performClick(); ui.onNodeWithTag("receipt-shared-id").assertDoesNotExist()
            // A replacement session reads Keystore-backed metadata, not the retained Activity instance.
            val replacement=MobileSession(NativeSessionStorage(ui.activity.applicationContext))
            main { replacement.restore() }
            ui.waitUntil(15_000) { replacement.hasClient && replacement.runtime!=null && !replacement.historyLoading }
            assertTrue(replacement.receiptNotices.isEmpty()); assertEquals(expected,replacement.targetKey)
            assertEquals("failed",replacement.runtime!!.receipts.single().state); assertTrue(replacement.messages.any { it.kind=="turnFailure" })
            ui.runOnIdle { replacement.leaveForeground() }
            ui.runOnIdle { session.selectThread(session.threads.last()) }; ui.waitUntil(15_000) { session.selectedThread=="secondary" && !session.historyLoading }
            ui.onNodeWithTag("receipt-shared-id").assertIsDisplayed(); shot("dismissal-target-isolation")
            ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil(15_000) { session.selectedThread=="primary" && !session.historyLoading }
            phase(f,"continued"); assertTrue(session.receiptNotices.isEmpty()); showHistoricalFailure(); shot("historical-failure-after-new-turn")
            phase(f,"unknown"); ui.onNodeWithText("消息执行结果待确认").assertIsDisplayed(); ui.onNodeWithTag("dismiss-receipt-unknown-id").assertDoesNotExist(); shot("unknown-preserved-after-success")
            phase(f,"new-failed"); ui.onNodeWithTag("receipt-new-id").assertIsDisplayed(); ui.onNodeWithText("任务执行失败").assertIsDisplayed(); shot("new-task-failure-not-hidden")
            assertTrue(f.server.requests.all { it.method=="GET" }); assertEquals("failed",session.runtime!!.receipts.last().state)
        }
    }
}
private class NoticeFixture(@Volatile var phase: String): java.io.Closeable {
    private val runtime="notice-runtime-"+UUID.randomUUID().toString()
    @Volatile var submittedId: String?=null
    val server=CommandFixture(::response)
    private fun target()=JSONObject().put("agentId","codex").put("hostId","notice-host").put("workspaceId","notice-workspace").put("runtimeId",runtime)
    private fun thread(id: String)=JSONObject().put("id",id).put("hostId","notice-host").put("projectId",JSONObject.NULL).put("title",id).put("state","idle")
    private fun receipt(thread: String,id: String,state: String,turn: String?,superseded: String?=null)=JSONObject().put("hostId","notice-host").put("threadId",thread).put("clientMessageId",id).put("state",state).put("turnId",turn ?: JSONObject.NULL).put("error",if(state=="failed") "合成上游连接失败" else JSONObject.NULL).apply { if(superseded!=null) put("supersededByTurnId",superseded) }
    private fun history(id: String): JSONObject {
        val old=receipt(id,submittedId ?: "shared-id","failed",if(phase=="rejected") null else "old-turn",if(phase in listOf("continued","unknown","new-failed")) "later-turn" else null)
        val receipts=JSONArray().put(old)
        if(phase=="unknown") receipts.put(receipt(id,"unknown-id","uncertain",null)).put(receipt(id,"successful-id","completed","later-turn"))
        if(phase=="new-failed") receipts.put(receipt(id,"new-id","failed","new-turn"))
        val messages=JSONArray().put(JSONObject().put("id","old-user").put("role","user").put("text","历史任务"))
        if(phase!="rejected") messages.put(JSONObject().put("id","turn-failure-old").put("role","assistant").put("kind","turnFailure").put("text","合成上游连接失败").put("runId","old-turn"))
        messages.put(JSONObject().put("id","phase-$phase").put("role","assistant").put("text","状态：$phase"))
        return JSONObject().put("hostId","notice-host").put("threadId",id).put("readTarget",target()).put("messages",messages).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",receipts).put("canControl",true))
    }
    private fun response(r: WireRequest): WireResponse {
        val body=when(r.path) {
            "/v1/workspace"->JSONObject().put("hostId","notice-host").put("name","失败合成电脑").put("connectionMode","desktop-runtime").put("readTarget",target()).put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray()).put("threads",JSONArray().put(thread("primary")).put(thread("secondary"))).put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1")))
            "/v1/history"->history(android.net.Uri.parse(r.pathWithQuery).getQueryParameter("threadId")!!)
            "/v1/replies"->{ val data=JSONObject(String(r.body,Charsets.UTF_8));submittedId=data.getString("clientMessageId");receipt("primary",submittedId!!,"failed",null) }
            else->return WireResponse(status=404)
        }
        return WireResponse(body=body.toString())
    }
    override fun close() { server.close() }
}
