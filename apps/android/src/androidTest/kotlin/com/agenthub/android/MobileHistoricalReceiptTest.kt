package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Historical uncertainty is a retained original receipt, never a current wait or replay action. */
@RunWith(AndroidJUnit4::class)
class MobileHistoricalReceiptTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun original(thread: String)="synthetic original input for $thread"
    private fun setup(context: String?="historical",state: String="uncertain",error: String?=null) {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId","historical-host").put("name","synthetic historical receipts").put("connectionMode","history-only")
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("notice-a","notice-b").map { JSONObject().put("id",it).put("hostId","historical-host").put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val receipt=JSONObject().put("hostId",client.id).put("threadId",threadId).put("clientMessageId","original-id").put("state",state)
                    .put("turnId",JSONObject.NULL).put("error",error ?: JSONObject.NULL).put("text",original(threadId)).apply { if(context!=null)put("noticeContext",context) }
                return HostReadCodec.history(JSONObject().put("hostId",client.id).put("threadId",threadId)
                    .put("messages",JSONArray().put(JSONObject().put("id","current-body").put("role","assistant").put("text","later task completed").put("status","completed")))
                    .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                        .put("approvals",JSONArray()).put("receipts",JSONArray().put(receipt)).put("canControl",false)).toString())
            }
        }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reader) }
        main { session.connect("synthetic historical receipts","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="unsubmitted draft" }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!=null }
    }
    private fun toggle(expanded: Boolean) {
        val control=ui.onNodeWithTag("historical-receipt-toggle-original-id").performScrollTo().assertIsDisplayed()
        control.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(5_000) { control.fetchSemanticsNode().config[SemanticsProperties.StateDescription]==if(expanded) "已展开" else "已折叠" }
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun historicalUnknownStartsCollapsedAndRevealsOnlyOriginalBody() {
        setup();ui.onNodeWithText("历史消息结果待核对").assertIsDisplayed();ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        toggle(true)
        ui.onNodeWithText(original("notice-a")).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("电脑已继续后续任务。这条旧消息是否执行仍缺少确认，不代表当前任务在等待；不会自动重发。").assertExists()
        toggle(false);ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        assertEquals("unsubmitted draft",session.text);assertNull(session.transferStatus);assertTrue(session.operationRecords.isEmpty());assertEquals("idle",session.runtime?.state)
    }
    @Test fun currentUnknownKeepsCurrentNoticeWithoutHistoricalBodyOrReplay() {
        setup(context=null);ui.onNodeWithText("消息执行结果待确认").assertIsDisplayed()
        ui.onNodeWithTag("historical-receipt-toggle-original-id").assertDoesNotExist();ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        ui.onNodeWithTag("dismiss-receipt-original-id").assertDoesNotExist();assertEquals("unsubmitted draft",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun sameOperationIdOnAnotherTargetCannotInheritDisclosureOrOriginalText() {
        setup();toggle(true);ui.onNodeWithText(original("notice-a")).performScrollTo().assertIsDisplayed()
        ui.runOnIdle { session.selectThread(session.threads.last()) };ui.waitUntil(5_000) { !session.historyLoading }
        ui.onNodeWithText("历史消息结果待核对").assertIsDisplayed();ui.onNodeWithText(original("notice-a")).assertDoesNotExist();ui.onNodeWithText(original("notice-b")).assertDoesNotExist()
        toggle(true);ui.onNodeWithText(original("notice-b")).performScrollTo().assertIsDisplayed()
        assertEquals("notice-b",session.selectedThread);assertEquals("",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun historicalUnknownPreservesErrorOutsideDisclosureAndCannotBeDismissedAsFailure() {
        setup(error="synthetic original transport diagnostic");ui.onNodeWithText("历史消息结果待核对").assertIsDisplayed()
        ui.onNodeWithText("synthetic original transport diagnostic").assertIsDisplayed();ui.onNodeWithText(original("notice-a")).assertDoesNotExist()
        ui.onNodeWithTag("dismiss-receipt-original-id").assertDoesNotExist();assertEquals("uncertain",session.runtime!!.receipts.single().state)
    }
    @Test fun rejectedReceiptRetainsSubmissionFailureAndExplicitDismissal() {
        setup(context=null,state="failed",error="synthetic rejection");ui.onNodeWithText("消息提交失败").assertIsDisplayed()
        ui.onNodeWithTag("dismiss-receipt-original-id").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("receipt-original-id").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("receipt-original-id").assertDoesNotExist()
        assertEquals("failed",session.runtime!!.receipts.single().state);assertEquals("unsubmitted draft",session.text)
    }
    @Test fun malformedReceiptContextCannotBeCoercedIntoPresentationMetadata() {
        val base=JSONObject().put("hostId","historical-host").put("threadId","notice-a").put("clientMessageId","original-id")
            .put("state","uncertain").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
        for(value in listOf<Any>(42,true,JSONArray().put("historical"),JSONObject().put("value","historical"))) {
            try { CodexCommandCodec.receipt(JSONObject(base.toString()).put("noticeContext",value));fail("Malformed context must be rejected without a receipt") }
            catch(expected: IllegalArgumentException) { /* no operation or presentation produced */ }
        }
    }

}
