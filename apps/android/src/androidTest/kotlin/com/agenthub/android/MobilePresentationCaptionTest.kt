package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** App-owned captions only; local controls never submit a management or model operation. */
class MobilePresentationCaptionTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private var fixture: ChatManagementFixture?=null
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring };activity=ui.activity
        ui.runOnIdle { owned=OwnedControlAcceptance(activity,"presentation-caption");session.resetAcceptance();session.changeLanguage("zh-Hans") }
    }
    @After fun finish() {
        try { ui.runOnIdle { session.leaveForeground();owned.verifyDefault() };assertTrue(fixture?.server?.requests.orEmpty().all { it.method=="GET" }) }
        finally { fixture?.close() }
    }
    private fun mount(scroll: Boolean=true,content: @Composable ()->Unit) {
        ui.runOnIdle { activity.setContent {
            ProductTextProvider(session.language) {
                val dark=InstrumentationRegistry.getArguments().getString("viewportTheme")=="dark"
                MaterialTheme(colorScheme=if(dark)darkColorScheme()else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize().testTag("caption-surface")) {
                        Column(if(scroll)Modifier.fillMaxSize().verticalScroll(rememberScrollState())else Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        } }
    }
    private fun language(value: String)=main { session.changeLanguage(value) }
    private fun proof(name: String) {
        assertEquals("原草稿 100% %@",session.text);assertTrue(session.operationRecords.isEmpty())
        File(owned.folder,"$name-proof.json").writeText(JSONObject().put("twoLanguages",true).put("newModelInputs",0).put("remoteWrites",0)
            .put("rawRuntimeValuesPreserved",true).put("isolatedMaterialComponents",true).toString())
    }
    private fun connect() {
        val f=ChatManagementFixture().also { it.batchSupported=true;it.state="unknown";it.firstTitle="展开 100% %@";fixture=it }
        main { session.connect("展开 100% %@",f.server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first });session.text="原草稿 100% %@" }
        ui.waitUntil(10_000) { !session.loading&&!session.historyLoading&&session.currentChat!=null }
    }
    @Test fun immutablePendingBodyFileNamesAndSendingStateFollowLanguageWithoutRetry() {
        main { session.text="原草稿 100% %@" }
        val message=MobileMessage("original-pending","user","展开 100% %@",files=listOf("发送中… 100% %@.txt"))
        var phase by mutableStateOf("outcome-unknown")
        mount { PendingCreationMessageBubble(PendingCreationMessage(message,phase),"original-target",false) }
        ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("发送中…")
        ui.onNodeWithTag("desktop-creation-message-loading").assertContentDescriptionEquals("消息正在发送")
        language("en");ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("Sending…")
        ui.onNodeWithTag("desktop-creation-message-loading").assertContentDescriptionEquals("Sending message")
        ui.onNodeWithText("File · 发送中… 100% %@.txt").assertExists();ui.onNodeWithTag("user-message-text-original-pending").assertTextEquals(message.text)
        ui.runOnIdle { phase="rejected" };ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("Not sent")
        ui.onNodeWithTag("desktop-creation-message-loading").assertDoesNotExist();language("zh-Hans")
        ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("未发送");proof("pending")
    }
    @Test fun expansionAndFullSearchCaptionsPreserveOriginalBodyAndDisclosureState() {
        connect();val body=(1..14).joinToString("\n") { "展开 原消息 $it 100% %@ "+"原正文 ".repeat(14) };val message=MobileMessage("original-long","user",body)
        mount { UserMessageBubble(message,"original-target",false) {} }
        val toggle=ui.onNodeWithTag("user-message-toggle-original-long").performScrollTo()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已折叠"));language("en")
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed")).assertTextContains("Show more")
        toggle.performClick();toggle.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        language("zh-Hans");toggle.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        ui.onNodeWithTag("user-message-text-original-long").assertTextEquals(body)
        val selection=MobileChatSearchSelection(MobileChatSearchContext(session.targetKey,session.selectedClient,session.selectedThread,0,0),message,"原消息",listOf(message),true)
        mount { LocatedChatMessage(selection) };ui.onNodeWithTag("chat-search-full-message").performScrollTo().performClick()
        language("en");ui.onNodeWithTag("chat-search-full-message").assertTextContains("Collapse full message")
        ui.onNodeWithTag("chat-search-full-text").assertTextEquals(body);language("zh-Hans")
        ui.onNodeWithTag("chat-search-full-message").performClick().assertTextContains("完整消息");ui.onNodeWithTag("chat-search-full-text").assertDoesNotExist();proof("expansion-search")
    }
    @Test fun planSummaryDisclosureAndStepAccessibilityTranslateOnlyFixedState() {
        main { session.text="原草稿 100% %@" }
        val steps=listOf(MobilePlanStep("已完成 100% %@","completed"),MobilePlanStep("进行中 100% %@","inProgress"),MobilePlanStep("未完成 100% %@","pending"))
        val plan=MobileMessage("original-plan","assistant","计划 原文 100% %@",status="inProgress",kind="plan")
        mount { RuntimePlanProgress(steps,"original-target");ProposedPlanMessage(plan,"original-target") }
        ui.onNodeWithText("执行计划 · 1/3").assertExists();ui.onNodeWithTag("runtime-plan-toggle").performClick()
        ui.onNodeWithTag("proposed-plan-toggle-original-plan").performClick();language("en")
        ui.onNodeWithText("Execution plan · 1/3").assertExists();ui.onNodeWithText("Preparing a plan…").assertExists()
        ui.onNodeWithTag("runtime-plan-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded"))
        for((index,label)in listOf("Completed","In progress","Incomplete").withIndex()) {
            ui.onNodeWithTag("runtime-plan-status-$index",useUnmergedTree=true).assertContentDescriptionEquals(label)
            ui.onNodeWithTag("runtime-plan-step-$index").assertTextEquals(steps[index].step)
        }
        language("zh-Hans");ui.onNodeWithTag("runtime-plan-toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        ui.onNodeWithTag("runtime-plan-status-2",useUnmergedTree=true).assertContentDescriptionEquals("未完成")
        owned.nodeShot(ui.onNodeWithTag("caption-surface"),"plan-original-steps");proof("plan")
    }
    @Test fun forkReceiptStatesAndEnvironmentTranslateButOriginalErrorAndIntentDoNot() {
        main { session.text="原草稿 100% %@" }
        val intent=JSONObject().put("environment","same-directory").put("text","原意图 100% %@").toString()
        val original=SavedOperation("original-fork","fork","original-target",intent,"original-sha","uncertain",error="查询原回执")
        var record by mutableStateOf(original)
        mount { ThreadForkReceipt(session,record) }
        ui.onNodeWithText("分支结果待确认").assertExists();language("en")
        ui.onNodeWithText("Branch result unconfirmed").assertExists();ui.onNodeWithText("Branch in the current working directory").assertExists()
        ui.onNodeWithTag("query-fork-original-fork").assertTextContains("Query original receipt");ui.onNodeWithText("查询原回执").assertExists()
        ui.runOnIdle { record=original.copy(detached=true) };ui.onNodeWithText("Waiting ended · Result unconfirmed").assertExists()
        ui.runOnIdle { record=original.copy(phase="confirmed") };ui.onNodeWithTag("query-fork-original-fork").assertTextContains("Open branch")
        language("zh-Hans");ui.onNodeWithText("分支已创建").assertExists();assertEquals(intent,record.intent);assertEquals(original.error,record.error);proof("fork")
    }
    @Test fun chatArchiveSelectionAndConfirmationKeepSelectionAcrossLanguageAndCancelWithoutWriting() {
        connect();val key=session.targetKey;val ids=session.threads.map { it.id }
        mount(scroll=false) { WorkspaceArchivePage(session) }
        val item="management-item-"+ChatManagementFixture.first
        ui.onNodeWithTag(item).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"未选择")).performClick()
        ui.onNodeWithTag("management-selected-count").assertTextEquals("已选 1 项");language("en")
        ui.onNodeWithTag(item).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Selected"))
        ui.onNodeWithTag("management-selected-count").assertTextEquals("1 selected")
        ui.onNodeWithTag("management-submit").assertTextContains("Archive 1 chats").performClick()
        ui.onNodeWithText("Archive 1 selected chats?").assertExists();language("zh-Hans")
        ui.onNodeWithText("归档选中的 1 个对话？").assertExists();ui.onNodeWithTag("management-cancel").performClick()
        assertEquals(ids,session.threads.map { it.id });assertEquals(key,session.targetKey);ui.onNodeWithTag("management-selected-count").assertTextEquals("已选 1 项");proof("chat-archive")
    }
    @Test fun connectionRemovalAndSaveCopyPreserveOriginalNameAndNeverChangeConnections() {
        connect();val clients=session.clients;val selected=session.selectedClient
        mount(scroll=false) { NativeSheetHost("clients",session,null,{},{},{}) }
        ui.onNodeWithTag("remove-client-${selected.id}").performScrollTo().performClick();language("en")
        ui.onNodeWithText("Remove the connection to 展开 100% %@? Projects and chats on the computer will be kept.").assertExists()
        ui.onNodeWithText("Cancel").performClick();language("zh-Hans")
        mount { CodexConnectionPage(session,selected) {} };ui.onNodeWithTag("client-name").assertTextContains("展开 100% %@")
        language("en");ui.onNodeWithTag("save-client").performScrollTo().assertTextContains("Save changes")
        language("zh-Hans");ui.onNodeWithTag("save-client").assertTextContains("保存修改")
        assertEquals(clients,session.clients);assertEquals(selected,session.selectedClient);proof("connection")
    }
}
