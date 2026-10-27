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

/** Shared tool captions may change language; raw Provider metadata/detail and disclosure stay original. */
class MobileToolCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private var server: CommandFixture?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"tool-copy");session.resetAcceptance()
            session.changeLanguage("zh-Hans");session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme")?:"light") }
    }
    @After fun finish() {
        try { ui.runOnIdle { session.leaveForeground();owned.verifyDefault() };assertTrue(server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { server?.close() }
    }
    private fun connect(messages: JSONArray,running: Boolean=false) {
        val target=JSONObject().put("agentId","codex").put("hostId","tool-copy-host").put("workspaceId","tool-copy-workspace").put("runtimeId","tool-copy-runtime")
        val fixture=CommandFixture { request->when(request.path) {
            "/v1/workspace"->WireResponse(body=JSONObject().put("hostId","tool-copy-host").put("name","原电脑 100% %@")
                .put("connectionMode","desktop-runtime").put("readTarget",target).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","queue","queue:execute","commands:target-bound:v1"))).put("projects",JSONArray())
                .put("threads",JSONArray().put(JSONObject().put("id","original-thread").put("hostId","tool-copy-host").put("title","原聊天 100% %@").put("state",if(running)"running" else "idle"))).toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId","tool-copy-host").put("threadId","original-thread").put("readTarget",target)
                .put("messages",messages)
                .put("runtime",JSONObject().put("state",if(running)"running" else "idle").put("turnId",if(running)"original-run" else JSONObject.NULL)
                    .put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
            else->WireResponse(404)
        } };server=fixture
        main { session.connect("原电脑 100% %@",fixture.endpoint,"") };ui.waitUntil(10_000) { !session.loading&&session.threads.size==1 }
        main { session.selectThread(session.threads.single());session.text="原草稿 100% %@" };ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null }
    }
    private fun receipt(id: String,state: String="uncertain")=JSONObject().put("hostId","tool-copy-host").put("threadId","original-thread")
        .put("clientMessageId",id).put("state",state).put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
    private fun proof(name: String)=File(owned.folder,name+"-proof.json").writeText(JSONObject().put("twoLanguages",true).put("rawDataAndDraftPreserved",true)
        .put("writes",server!!.requests.count { it.method!="GET" }).put("newModelInputs",0).toString())
    private fun message(id: String,text: String,kind: String,status: String="completed",role: String="tool")=JSONObject().put("id",id).put("role",role)
        .put("text",text).put("kind",kind).put("status",status).put("runId","original-run")
    private fun scroll(tag: String)=ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag))
    @Test fun originalProviderActivityAndDisclosureRemainWhileGroupAndStatusChangeLanguage() {
        connect(JSONArray().put(message("first","运行 · 原工具 100% %@","mcpToolCall"))
            .put(message("second","运行 · 等待工具输出…","dynamicToolCall")))
        val original=session.messages;val runtime=session.runtime
        main { session.changeLanguage("en") }
        val tag="tool-group-toggle-first";scroll(tag)
        ui.onNodeWithTag(tag).assertContentDescriptionEquals("Completed · Use 运行")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed")).performClick()
        scroll("tool-toggle-first")
        ui.onNodeWithTag("tool-toggle-first").assertContentDescriptionEquals("原工具 100% %@").performClick()
        scroll("tool-detail-first")
        ui.onNodeWithText("No detailed output yet").assertExists();ui.onNodeWithText("Tool output").assertExists()
        ui.onNodeWithTag("tool-toggle-first").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Succeeded, Expanded"))
        ui.onNodeWithTag("tool-toggle-second").assertContentDescriptionEquals("等待工具输出…")
        owned.nodeShot(ui.onNodeWithTag("tool-detail-first"),"english-original-tool-detail")
        main { session.changeLanguage("zh-Hans") }
        ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        ui.onNodeWithTag("tool-detail-first").assertExists();ui.onNodeWithTag("tool-toggle-first").assertContentDescriptionEquals("原工具 100% %@")
        assertEquals(original,session.messages);assertEquals(runtime,session.runtime);assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty());proof("tool-group")
    }
    @Test fun singleCommandWaitingAndCompletedCompactionUseCurrentLanguageWithoutEditingSource() {
        connect(JSONArray().put(message("command","成功 100% %@","commandExecution","inProgress"))
            .put(message("compact","已压缩上下文","contextCompaction",role="system")))
        val original=session.messages;val runtime=session.runtime
        main { session.changeLanguage("en") };scroll("tool-toggle-command")
        ui.onNodeWithTag("tool-toggle-command").assertContentDescriptionEquals("In progress · run command").performClick()
        scroll("tool-detail-command")
        ui.onNodeWithText("$ 成功 100% %@").assertExists();ui.onNodeWithText("Waiting for tool output…").assertExists()
        owned.nodeShot(ui.onNodeWithTag("tool-detail-command"),"english-command-original-text")
        scroll("compaction-compact");ui.onNodeWithTag("compaction-compact").assertTextEquals("Context compacted")
        main { session.changeLanguage("zh-Hans") };ui.onNodeWithTag("compaction-compact").assertTextEquals("已压缩上下文")
        scroll("tool-toggle-command");ui.onNodeWithTag("tool-toggle-command").assertContentDescriptionEquals("正在运行命令")
        ui.onNodeWithTag("tool-detail-command").assertExists()
        assertEquals(original,session.messages);assertEquals(runtime,session.runtime);assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty());proof("command-compaction")
    }
}
