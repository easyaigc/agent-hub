package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Two completed tasks share idle/null active Turn; retained menus still need the original task identity. */
class MobileTerminalControlContextTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val scope=TargetScope("codex",null,null,"terminal-fixture-host","terminal-workspace",java.util.UUID.randomUUID().toString())
    private var user="task-user-a"
    private var run="task-run-a"
    private var assistant="Synthetic completed answer"
    private val replies=mutableListOf<ReplyIntent>()
    private val receipts=mutableMapOf<String,DeliveryReceipt>()
    private val chosen=GenerationSettings("fixture-model","high","read-only","fast","default")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup() {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject().put("hostId",scope.hostId).put("name","Synthetic terminal controls").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray()).put("threads",JSONArray().put(JSONObject().put("id","terminal-thread").put("hostId",scope.hostId).put("title","Synthetic terminal task").put("state","idle")))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1","generation-settings","generation-speed","models:read","plan-mode"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                .put("messages",JSONArray().put(JSONObject().put("id",user).put("role","user").put("text","Synthetic task body").put("runId",run)).put(JSONObject().put("id","answer-"+run).put("role","assistant").put("text",assistant).put("status","completed").put("runId",run)))
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"high",listOf("low","high"),"standard",listOf("standard","fast")))
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt { replies.add(intent);return DeliveryReceipt(scope.hostId,intent.threadId,intent.operationId,"submitted","recovery-turn",null,null,intent.text,intent.attachmentIds).also { receipts[intent.operationId]=it } }
            override suspend fun receipt(client: MobileClient,threadId: String,operationId: String)=receipts.getValue(operationId)
        }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"terminal");session.resetAcceptance(reader,commands) }
        main { session.connect("Synthetic terminal controls","https://fixture.invalid","") }
        ui.waitUntil(5_000) { !session.loading&&session.threads.isNotEmpty()&&session.models.isNotEmpty() }
        ui.runOnIdle { session.selectThread(session.threads.single()) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.canConfigureGeneration }
        main { allowSyntheticRegressionSharing(session) };ui.runOnIdle { session.configure(chosen) }
        assertEquals("send",session.decision.action);assertFalse(session.decision.enabled);assertTrue(session.canRunAgain);assertNull(session.runtime!!.turnId)
    }
    private fun menu(tag: String): ()->Boolean {
        ui.onNodeWithTag("send-message").assertIsNotEnabled().performTouchInput { longClick() }
        owned.nodeShot(ui.onNodeWithTag(tag).onParent(),"terminal-menu-"+tag)
        return checkNotNull(ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }
    private fun reload() { session.loadHistory(userInitiated=true);assertFalse(session.historyLoading);assertEquals("idle",session.runtimeState);assertNull(session.runtime!!.turnId);assertEquals("send",session.decision.action) }
    private fun settle() { ui.waitUntil(10_000) { session.transferStatus==null };ui.waitForIdle() }
    @After fun stop() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    @Test fun retainedRestartCannotTargetAnotherCompletedTaskInTheSameSelection() {
        setup();val click=menu("action-restart")
        ui.runOnIdle { user="task-user-b";run="task-run-b";reload();click() }
        settle();assertTrue("Both active Turn IDs are null, but the task changed",replies.isEmpty());assertEquals(chosen,session.generationSettings)
    }
    @Test fun retainedContinueCannotTargetANewerRunWithTheSameUserItem() {
        setup();val click=menu("action-resume")
        ui.runOnIdle { run="task-run-b";reload();click() }
        settle();assertTrue("A new finished run must invalidate the old menu",replies.isEmpty())
    }
    @Test fun sameTaskPresentationRefreshKeepsTheExplicitActionAvailable() {
        setup();val click=menu("action-resume")
        ui.runOnIdle { assistant="Updated presentation of the same completed task";reload();click() }
        ui.waitUntil(10_000) { replies.size==1&&session.transferStatus==null };assertEquals(com.agenthub.core.MobileRules.runAgainText(false),replies.single().text);assertEquals(chosen,replies.single().settings)
    }
    @Test fun aFreshMenuForTheNewCompletedTaskCreatesOneNewIntent() {
        setup();ui.runOnIdle { user="task-user-b";run="task-run-b";reload() }
        ui.onNodeWithTag("send-message").performTouchInput { longClick() };ui.onNodeWithTag("action-restart").performClick()
        ui.waitUntil(10_000) { replies.size==1&&session.transferStatus==null };val intent=replies.single();assertEquals(com.agenthub.core.MobileRules.runAgainText(true),intent.text);assertEquals(chosen,intent.settings)
        val saved=session.operationRecords.single { it.id==intent.operationId };assertEquals(JSONObject(saved.intent).getString("clientMessageId"),intent.operationId);assertEquals("terminal-thread",intent.threadId);assertEquals("",session.text)
    }
    @Test fun coordinateContinueTargetsTheObservedOwnedPopupOnce() {
        setup();ui.onNodeWithTag("send-message").performTouchInput { longClick() }
        owned.nodeShot(ui.onNodeWithTag("action-resume").onParent(),"coordinate-continue-menu")
        touchOwnedMenu("继续任务",owned.folder,"coordinate-continue")
        ui.waitUntil(10_000) { replies.size==1&&session.transferStatus==null }
        assertEquals(com.agenthub.core.MobileRules.runAgainText(false),replies.single().text);assertEquals(chosen,replies.single().settings)
    }
    @Test fun coordinateRestartTargetsTheObservedOwnedPopupOnce() {
        setup();ui.onNodeWithTag("send-message").performTouchInput { longClick() }
        owned.nodeShot(ui.onNodeWithTag("action-restart").onParent(),"coordinate-restart-menu")
        touchOwnedMenu("重新运行上一任务",owned.folder,"coordinate-restart")
        ui.waitUntil(10_000) { replies.size==1&&session.transferStatus==null }
        assertEquals(com.agenthub.core.MobileRules.runAgainText(true),replies.single().text)
    }
}
