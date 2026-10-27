package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** App captions may change language; raw queue/receipt/journal data and authority must not. */
class MobileExecutionCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private var server: CommandFixture?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"execution-copy");session.resetAcceptance()
            session.changeLanguage("zh-Hans");session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light") }
    }
    @After fun finish() {
        try { ui.runOnIdle { session.leaveForeground();owned.verifyDefault() };assertTrue(server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { server?.close() }
    }
    private fun connect(receipts: JSONArray,running: Boolean=false,approvals: JSONArray=JSONArray()) {
        val target=JSONObject().put("agentId","codex").put("hostId","execution-copy-host").put("workspaceId","execution-copy-workspace").put("runtimeId","execution-copy-runtime")
        val fixture=CommandFixture { request->when(request.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","execution-copy-host").put("name","原电脑 100% %@")
                .put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","queue","queue:execute","commands:target-bound:v1"))).put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id","original-thread").put("hostId","execution-copy-host").put("title","原聊天 100% %@").put("state",if(running)"running" else "idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","execution-copy-host").put("threadId","original-thread").put("readTarget",target)
                .put("messages",JSONArray().put(JSONObject().put("id","original-message").put("role","assistant").put("text","原回复 100% %@")))
                .put("runtime",JSONObject().put("state",if(running)"running" else "idle").put("turnId",if(running)"original-run" else JSONObject.NULL)
                    .put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",approvals).put("receipts",receipts).put("canControl",true)).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("原电脑 100% %@",fixture.endpoint,"") };ui.waitUntil(10_000) { !session.loading&&session.threads.size==1 }
        main { session.selectThread(session.threads.single());session.text="原草稿 100% %@" };ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null }
    }
    private fun receipt(id: String,state: String="uncertain")=JSONObject().put("hostId","execution-copy-host").put("threadId","original-thread")
        .put("clientMessageId",id).put("state",state).put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
    private fun proof(name: String)=File(owned.folder,name+"-proof.json").writeText(JSONObject().put("twoLanguages",true).put("rawDataAndDraftPreserved",true)
        .put("writes",server!!.requests.count { it.method!="GET" }).put("newModelInputs",0).toString())
    @Test fun queueCopyTracksLanguageWithoutChangingOriginalOrderTextOrAuthority() {
        connect(JSONArray().put(receipt("raw","queued").put("text","立即追加").put("queueSource","native").put("queuePresent",true).put("canExecuteNow",true))
            .put(receipt("file","queued").put("text"," \n ").put("attachmentIds",JSONArray(listOf("original-file"))).put("queueSource","native").put("queuePresent",true).put("canExecuteNow",true)),running=true)
        val original=session.runtime;val ids=session.queuedReplies.map { it.operationId };val key=session.targetKey
        val authority=session.queuedReplies.map { session.canExecuteQueue(it,key,session.runtime?.turnId) to session.canCancelQueue(it,key) }
        assertTrue(authority.all { it.first&&it.second })
        ui.onNodeWithTag("queued-text-file").assertTextEquals("附件指令")
        main { session.changeLanguage("en") }
        ui.onNodeWithTag("queued-text-raw").assertTextEquals("立即追加")
        ui.onNodeWithTag("queued-text-file").assertTextEquals("Attached instructions")
        ui.onNodeWithTag("execute-queued-raw").assertTextContains("Add immediately").assertContentDescriptionEquals("Run queued instruction 1 now")
        ui.onNodeWithTag("cancel-queued-file").assertContentDescriptionEquals("Cancel queued instruction 2")
        owned.nodeShot(ui.onNodeWithTag("queued-commands"),"english-original-queue")
        main { session.changeLanguage("zh-Hans") }
        ui.onNodeWithTag("execute-queued-raw").assertTextContains("立即追加").assertContentDescriptionEquals("立即执行第 1 条排队指令")
        assertEquals(original,session.runtime);assertEquals(ids,session.queuedReplies.map { it.operationId });assertEquals("原草稿 100% %@",session.text)
        assertEquals(authority,session.queuedReplies.map { session.canExecuteQueue(it,key,session.runtime?.turnId) to session.canCancelQueue(it,key) });assertTrue(session.operationRecords.isEmpty());proof("queue")
    }
    @Test fun receiptDisclosureAndFixedFailureCaptionsDoNotTranslateRawDiagnosticsOrReplay() {
        connect(JSONArray().put(receipt("old").put("noticeContext","historical").put("text","历史原文 100% %@"))
            .put(receipt("failed","failed").put("error","消息提交失败")))
        val original=session.runtime;val toggle=ui.onNodeWithTag("historical-receipt-toggle-old").performScrollTo()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已折叠"))
        main { session.changeLanguage("en") };toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed"))
        toggle.performClick();toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        ui.onNodeWithText("历史原文 100% %@").assertExists();ui.onNodeWithTag("receipt-failed").performScrollTo()
        ui.onNodeWithText("Message submission failed").assertExists();ui.onNodeWithText("消息提交失败").assertExists()
        owned.nodeShot(ui.onNodeWithTag("receipt-failed"),"english-failure-raw-diagnostic")
        main { session.changeLanguage("zh-Hans") };toggle.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        assertEquals(original,session.runtime);assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty());proof("receipts")
    }
    @Test fun savedLocalIntentCaptionDoesNotAlterOrSubmitOriginalOperation() {
        connect(JSONArray());val client=session.selectedClient;val thread=session.selectedThread;val key=session.targetKey
        val journal=NativeOperationJournal(owned.storage);lateinit var original: SavedOperation
        main { original=journal.prepare(client,thread,"reply",ReplyIntent(client.id,thread,"original-local-intent","原提交意图 100% %@",emptyList(),"next-turn").body(),key)
            session.selectThread(session.threads.single()) }
        ui.waitUntil(10_000) { session.activeOperationRecords.any { it.id==original.id } }
        ui.onNodeWithText("本地意图已保存").assertExists();main { session.changeLanguage("en") }
        ui.onNodeWithText("Operation awaiting confirmation").assertExists();ui.onNodeWithText("Local intent saved").assertExists()
        owned.nodeShot(ui.onNodeWithTag("operation-${original.id}"),"english-saved-original-intent")
        main { session.changeLanguage("zh-Hans") };ui.onNodeWithText("本地意图已保存").assertExists()
        val after=runBlocking { journal.records(original.target).single() };assertEquals(original,after);assertFalse(after.submissionAttempted)
        assertEquals("原草稿 100% %@",session.text);proof("local-intent")
    }
    @Test fun questionPagingCopyPreservesOriginalSelectionAcrossLanguageChangesWithoutAnswering() {
        fun question(index: Int)=JSONObject().put("id","approval-$index").put("threadId","original-thread").put("turnId","original-run")
            .put("kind","question").put("title","原问题 100% %@").put("detail","").put("interactionMode","async").put("decisions",JSONArray())
            .put("questions",JSONArray().put(JSONObject().put("id","q$index").put("question","上一题").put("multiple",false).put("custom",true).put("isSecret",false)
                .put("options",JSONArray().put(JSONObject().put("label","One").put("description","原选项 100% %@")))))
        connect(JSONArray(),approvals=JSONArray().put(question(1)).put(question(2)))
        val original=session.runtime
        ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2")
        ui.onNodeWithTag("async-question-next").performClick();ui.onNodeWithTag("question-option-q2-One").performScrollTo().performClick()
        main { session.changeLanguage("en") }
        ui.onNodeWithTag("async-question-prev").assertContentDescriptionEquals("Previous question")
        ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 2")
        ui.onNodeWithTag("question-title-q2").assertTextEquals("上一题")
        ui.onNodeWithTag("question-option-q2-One").assertIsSelected()
        owned.dialogShot("english-question-original-selection")
        ui.onNodeWithTag("close-async-questions").performClick()
        ui.onNodeWithTag("open-async-questions").performScrollTo().assertTextContains("Questions to answer: 2")
        main { session.changeLanguage("zh-Hans") }
        ui.onNodeWithTag("open-async-questions").assertTextContains("2 个问题待回答").performClick()
        ui.onNodeWithTag("async-question-prev").assertContentDescriptionEquals("上一题")
        ui.onNodeWithTag("question-option-q2-One").assertIsSelected()
        ui.onNodeWithTag("close-async-questions").performClick()
        assertEquals(original,session.runtime);assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty());proof("question-paging")
    }
}
