package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real Compose/session/journal with a synthetic provider; never a desktop/model claim. */
class NativeNewChatDestinationTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"new-chat-fixture-host","new-chat-workspace","new-chat-runtime")
    private var available=true
    private var empty=false
    private var pageOneOnly=false
    private val intents=mutableListOf<CreationIntent>()
    private val submissions=mutableListOf<String>()
    private var created: MobileThread?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup() {
        ui.waitUntil(10_000) { !session.restoring }
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                val ids=if(empty)emptyList()else if(pageOneOnly && projectCursor==null)listOf("one")else listOf("one","two")
                return HostReadCodec.workspace(JSONObject().put("hostId",scope.hostId).put("name","New chat fixture").put("connectionMode","desktop-runtime")
                    .put("readTarget",CodexCommandCodec.target(scope)).put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                    .put("projects",JSONArray(ids.map { JSONObject().put("id",it).put("hostId",scope.hostId).put("name","Project $it").put("roots",JSONArray().put("/workspace/$it")) }))
                    .put("nextProjectCursor",if(pageOneOnly && projectCursor==null)"next"else JSONObject.NULL)
                    .put("threads",JSONArray(listOfNotNull(created).map(::encodeThread)))
                    .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1")+if(available)listOf("desktop:creations","desktop:creations:projectless")else emptyList())).toString())
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                .put("messages",JSONArray().put(JSONObject().put("id","input").put("role","user").put("text",intents.single().text)).put(JSONObject().put("id","output").put("role","assistant").put("text","Fixture reply").put("status","completed")))
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation {
                assertEquals(scope.hostId,client.id);assertTrue(intents.isEmpty());intents.add(intent)
                return DesktopCreation(scope.hostId,intent.operationId,intent.projectId,"prepared",null,null,emptyList(),null,false)
            }
            override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation {
                assertEquals(intents.single().operationId,operationId);assertTrue(submissions.isEmpty());submissions.add(operationId)
                val project=intents.single().projectId
                created=MobileThread("created-in-"+(project ?: "projectless"),scope.hostId,"Created fixture","idle",project)
                return DesktopCreation(scope.hostId,operationId,project,"confirmed",created!!.id,null,emptyList(),created,false)
            }
        }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reads,commands);session.changeLanguage("zh-Hans") }
        main { session.connect("New chat fixture","https://fixture.invalid","") }
        ui.waitUntil(5_000) { session.hasClient&&!session.loading }
    }
    private fun picker(sidebar: Boolean=false) {
        if(sidebar) { ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("sidebar-new").performClick() }
        else ui.onNodeWithTag("new-chat").performClick()
        ui.onNodeWithTag("sheet-projects").assertIsDisplayed()
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun sidebarChoiceClosesDrawerAndShowsExactProjectAndFolderBeforeOneSend() {
        setup();picker(sidebar=true)
        ui.onNodeWithText("/workspace/two").assertIsDisplayed()
        ui.onNodeWithTag("choose-project-two").performClick()
        ui.onNodeWithTag("sheet-projects").assertDoesNotExist()
        ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
        ui.onNodeWithTag("new-chat-destination-name").assertTextEquals("在 Project two 中开始新对话")
        ui.onNodeWithTag("new-chat-destination-root").assertTextEquals("/workspace/two")
        ui.runOnIdle { assertTrue(session.canCompose);assertTrue(session.selectedThread.isEmpty());assertTrue(intents.isEmpty()) }
        main { allowSyntheticRegressionSharing(session) }
        ui.onNodeWithTag("message-input").performTextReplacement("First project input")
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(5_000) { session.decision.enabled }
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(10_000) { session.selectedThread=="created-in-two" && session.messages.any { it.text=="Fixture reply" } }
        assertEquals("two",intents.single().projectId);assertEquals(1,submissions.size)
    }
    @Test fun cancelKeepsDraftAndProjectlessHasAnExplicitDestination() {
        setup();picker();ui.onNodeWithTag("choose-project-one").performClick()
        ui.runOnIdle { session.text="Keep my draft" }
        picker();ui.onNodeWithTag("sheet-done").performClick()
        ui.runOnIdle { assertEquals("one",session.draftProject?.id);assertEquals("Keep my draft",session.text) }
        picker();ui.onNodeWithTag("projectless-new").performClick()
        ui.onNodeWithTag("new-chat-destination-name").assertTextEquals("开始一个无项目对话")
        ui.runOnIdle { assertTrue(session.isProjectlessDraft);assertTrue(session.canCompose);assertTrue(intents.isEmpty()) }
    }
    @Test fun unsupportedCreationKeepsAnActionablePickerAndDisablesInertChoices() {
        available=false;setup();picker()
        ui.onNodeWithTag("new-chat-unavailable").assertTextEquals("电脑尚未启用新建对话，请更新 Host 后刷新。")
        ui.onNodeWithTag("choose-project-one").assertIsNotEnabled()
        ui.onNodeWithTag("new-chat-refresh").assertIsEnabled()
        available=true;ui.onNodeWithTag("new-chat-refresh").performClick()
        ui.waitUntil(5_000) { session.canCreateThread&&!session.loading }
        ui.onNodeWithTag("choose-project-one").assertIsEnabled().performClick()
        ui.onNodeWithTag("new-chat-destination-root").assertTextEquals("/workspace/one")
    }
    @Test fun emptyProjectsRecoverByRefreshingAndAllProjectPagesRemainReachable() {
        empty=true;setup();picker()
        ui.onNodeWithTag("new-chat-empty-projects").assertIsDisplayed()
        empty=false;pageOneOnly=true;ui.onNodeWithTag("new-chat-refresh").performClick()
        ui.waitUntil(5_000) { session.projects.size==1&&!session.loading }
        ui.onNodeWithTag("choose-project-two").assertDoesNotExist()
        ui.onNodeWithTag("new-chat-more-projects").performScrollTo().performClick()
        ui.waitUntil(5_000) { session.projects.size==2&&!session.loading }
        ui.onNodeWithTag("choose-project-two").performScrollTo().performClick()
        ui.runOnIdle { assertEquals("two",session.draftProject?.id);assertTrue(intents.isEmpty()) }
    }
    @Test fun oldNavigationTargetAndRevokedCapabilityCannotReplaceTheCurrentDraft() {
        setup();lateinit var old: NewChatTarget
        ui.runOnIdle { assertTrue(session.newChat("one"));session.text="Original";old=session.captureNewChatTarget();session.select(session.selectedClient) }
        ui.waitUntil(5_000) { !session.loading }
        ui.runOnIdle { assertFalse(session.newChat("two",old));assertEquals("one",session.draftProject?.id);assertEquals("Original",session.text) }
        available=false;ui.runOnIdle { session.refresh() };ui.waitUntil(5_000) { !session.loading&&!session.canCreateThread }
        ui.runOnIdle { assertFalse(session.newChat("two"));assertEquals("one",session.draftProject?.id);assertEquals("Original",session.text) }
    }
}
