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

/** Deliver a retained native gesture before another frame can rebind its semantics wrapper. */
class MobileControlCallbackTest {
    private val args get()=InstrumentationRegistry.getArguments()
    private fun menuFolder()=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P4-owner-menus/${args.getString("ownerCallbacksRun") ?: "fixture"}").apply { mkdirs() }
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule({args.getString("viewportOrientation") ?: ""},{menuFolder()},{"owner-menu"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val replies=mutableListOf<ReplyIntent>()
    private val stops=mutableListOf<Pair<String,String>>()
    private val stopGates=mutableMapOf<String,CompletableDeferred<Unit>>()
    private var historyReads=0
    private val receipts=mutableMapOf<String,DeliveryReceipt>()
    private val scope=TargetScope("codex",null,null,"control-callback-fixture-host","control-callback-workspace",java.util.UUID.randomUUID().toString())
    private var state="interrupted"
    private var turn="original-turn"
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun setup(running: Boolean=false) {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        state=if(running)"running" else "interrupted"
        val reads=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","Synthetic control callbacks").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray())
                .put("threads",JSONArray(listOf("control-a","control-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state",state) }))
                .put("capabilities",JSONArray(listOf("reply","stop","commands:target-bound:v1","generation-settings","generation-speed","models:read"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                historyReads++;return HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId)
                .put("readTarget",CodexCommandCodec.target(scope)).put("messages",JSONArray().put(JSONObject().put("id","prior-user").put("role","user").put("text","Synthetic prior user task")))
                .put("runtime",JSONObject().put("state",state).put("turnId",turn).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"high",listOf("low","high"),"standard",listOf("standard","fast")))
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {
                replies.add(intent)
                return DeliveryReceipt(scope.hostId,intent.threadId,intent.operationId,"submitted",turn,null,null,intent.text,intent.attachmentIds).also { receipts[intent.operationId]=it }
            }
            override suspend fun receipt(client: MobileClient,threadId: String,operationId: String)=receipts.getValue(operationId)
            override suspend fun stop(client: MobileClient,threadId: String,turnId: String) { stops.add(threadId to turnId);stopGates[turnId]?.await() }
        }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"control");session.resetAcceptance(reads,commands) }
        ui.runOnIdle { session.changeAppearance(args.getString("viewportTheme") ?: "dark") }
        java.io.File(menuFolder(),"profile.json").writeText(JSONObject().put("appearance",session.appearance).put("fontScale",ui.density.fontScale).put("orientation",ui.activity.resources.configuration.orientation).toString())
        main { session.connect("Synthetic control callbacks","https://fixture.invalid","") }
        ui.waitUntil(5_000) { !session.loading&&session.threads.size==2&&session.models.isNotEmpty() }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.runtime!=null }
        main { allowSyntheticRegressionSharing(session) }
        assertEquals(if(running)"stop" else "resume",session.decision.action)
    }
    private fun retained(tag: String)=checkNotNull(ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun menu(tag: String): ()->Boolean {
        ui.onNodeWithTag(if(state=="running")"stop-turn" else "resume-task").performTouchInput { longClick() }
        owned.nodeShot(ui.onNodeWithTag(tag).onParent(),"menu-"+tag);return retained(tag)
    }
    private fun settle() { ui.waitUntil(10_000) { session.transferStatus==null&&session.decision.action!="busy" };ui.waitForIdle() }
    @After fun stop() { ui.runOnIdle { if(::owned.isInitialized) { stopGates.values.forEach { it.complete(Unit) };session.leaveForeground();owned.verifyDefault() } } }
    @Test fun coordinateStopTargetsTheObservedOwnedPopupOnce() {
        setup(true);ui.onNodeWithTag("stop-turn").performTouchInput { longClick() }
        owned.nodeShot(ui.onNodeWithTag("action-stop").onParent(),"coordinate-stop-menu")
        touchOwnedMenu("停止任务",owned.folder,"coordinate-stop")
        ui.waitUntil(10_000) { stops.size==1&&session.decision.action!="busy" }
        assertEquals(listOf("control-a" to "original-turn"),stops)
    }
    private fun nativeMenu(restart: Boolean) {
        setup()
        val settings=GenerationSettings("fixture-model","high","read-only","fast","default")
        ui.runOnIdle { session.configure(settings);session.text="Independent native-menu draft" }
        ui.onNodeWithTag("send-message").performTouchInput { longClick() }
        ui.onNodeWithTag("action-stop").assertDoesNotExist()
        for(tag in listOf("action-resume","action-restart")) {
            val row=ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled();val r=row.getUnclippedBoundsInRoot()
            assertTrue("Every original-owner menu row has a full 44dp target",r.bottom.value-r.top.value>=43.5f)
        }
        val tag=if(restart)"action-restart"else"action-resume"
        owned.nodeShot(ui.onNodeWithTag(tag).onParent(),"native-"+tag)
        activateOwnedMenu(if(restart)"重新运行上一任务"else"继续任务",owned.folder,tag)
        ui.waitUntil(10_000) { replies.size==1&&session.transferStatus==null };ui.waitForIdle()
        val intent=replies.single();assertEquals("control-a",intent.threadId)
        assertEquals(com.agenthub.core.MobileRules.runAgainText(restart),intent.text)
        assertEquals(settings,intent.settings);assertTrue(intent.attachmentIds.isEmpty())
        assertEquals("Independent native-menu draft",session.text);assertEquals(settings,session.generationSettings);assertTrue(stops.isEmpty())
        ui.onNodeWithTag(tag).assertDoesNotExist()
    }
    @Test fun nativeContinueMenuTargetsOriginalOnceAndKeepsDraft()=nativeMenu(false)
    @Test fun nativeRestartMenuTargetsOriginalOnceAndKeepsDraft()=nativeMenu(true)
    @Test fun nativeStopMenuTargetsOriginalTurnOnce() {
        setup(true);ui.runOnIdle { session.text="Independent stop-menu draft" }
        ui.onNodeWithTag("send-message").performTouchInput { longClick() }
        val row=ui.onNodeWithTag("action-stop").assertIsDisplayed().assertIsEnabled();val r=row.getUnclippedBoundsInRoot()
        assertTrue(r.bottom.value-r.top.value>=43.5f)
        ui.onNodeWithTag("action-resume").assertDoesNotExist();ui.onNodeWithTag("action-restart").assertDoesNotExist()
        owned.nodeShot(row.onParent(),"native-action-stop")
        activateOwnedMenu("停止任务",owned.folder,"action-stop")
        ui.waitUntil(10_000) { stops.size==1&&session.decision.action!="busy" }
        assertEquals(listOf("control-a" to "original-turn"),stops);assertTrue(replies.isEmpty());assertEquals("Independent stop-menu draft",session.text)
        ui.onNodeWithTag("action-stop").assertDoesNotExist()
    }
    @Test fun oldStopMenuWithDraftCannotInterruptANewerTurn() {
        setup(true);ui.runOnIdle { session.text="Keep running draft" }
        ui.onNodeWithTag("send-message").performTouchInput { longClick() };val click=retained("action-stop")
        ui.runOnIdle { turn="replacement-turn";session.loadHistory(userInitiated=true);assertEquals("replacement-turn",session.runtime!!.turnId);click() }
        settle();assertTrue(stops.isEmpty());assertTrue(replies.isEmpty());assertEquals("Keep running draft",session.text)
    }
    @Test fun oldStopMenuWithDraftCannotActAfterDraftRevision() {
        setup(true);ui.runOnIdle { session.text="Original running draft" }
        ui.onNodeWithTag("send-message").performTouchInput { longClick() };val click=retained("action-stop")
        ui.runOnIdle { session.text="Newer running draft";click() }
        settle();assertTrue(stops.isEmpty());assertTrue(replies.isEmpty());assertEquals("Newer running draft",session.text)
    }
    @Test fun oldContinueMenuCannotSendToAnotherReadyTarget() {
        setup();val click=menu("action-resume")
        ui.runOnIdle { session.selectThread(session.threads.last());assertFalse(session.historyLoading);assertTrue(session.canRunAgain);session.text="B independent draft";click() }
        settle();assertTrue("Retained A action cannot create B input",replies.isEmpty());assertEquals("B independent draft",session.text)
    }
    @Test fun oldRestartMenuCannotActOnRevisitedTarget() {
        setup();val click=menu("action-restart")
        ui.runOnIdle { val a=session.threads.first();session.selectThread(session.threads.last());session.selectThread(a);assertFalse(session.historyLoading);session.text="A revisited draft";click() }
        settle();assertTrue("A to B to A requires a new native gesture",replies.isEmpty());assertEquals("A revisited draft",session.text)
    }
    @Test fun oldStopButtonCannotInterruptANewerTurn() {
        setup(true);val click=retained("stop-turn")
        ui.runOnIdle { turn="newer-turn";session.loadHistory(userInitiated=true);assertFalse(session.historyLoading);assertEquals("newer-turn",session.runtime!!.turnId);click() }
        settle();assertTrue("Old stop targets its rendered turn",stops.isEmpty())
        ui.onNodeWithTag("stop-turn").assertIsEnabled().performClick();ui.waitUntil(5_000) { stops.isNotEmpty() };assertEquals(listOf("control-a" to "newer-turn"),stops)
    }
    @Test fun oldResumeButtonCannotUseNewerDraftSettings() {
        setup();val click=retained("resume-task")
        val newer=GenerationSettings("fixture-model","low","full-access","fast","plan")
        ui.runOnIdle { session.configure(newer);session.text="Newer editable local draft";click() }
        settle();assertTrue("Old action cannot use new permissions",replies.isEmpty());assertEquals(newer,session.generationSettings);assertEquals("Newer editable local draft",session.text)
    }
    @Test fun oldSendButtonCannotSubmitReplacedDraft() {
        setup();ui.runOnIdle { state="idle";session.loadHistory(userInitiated=true);session.text="Old editable input" }
        ui.waitUntil(5_000) { session.decision.action=="send" };val click=retained("send-message")
        ui.runOnIdle { session.text="Newer editable input";click() }
        settle();assertTrue("Retained send does not submit a replacement draft",replies.isEmpty());assertEquals("Newer editable input",session.text)
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick();ui.waitUntil(10_000) { replies.size==1&&session.transferStatus==null };assertEquals("Newer editable input",replies.single().text)
    }
    @Test fun lateStopFailureCannotReplaceAnErrorAfterNavigationRoundTrip() {
        setup(true);val gate=CompletableDeferred<Unit>();stopGates[turn]=gate
        ui.onNodeWithTag("stop-turn").performClick();ui.waitUntil(5_000) { stops.size==1 }
        ui.runOnIdle { val a=session.threads.first();session.selectThread(session.threads.last());session.selectThread(a);session.text="Revisited independent draft";session.error="Newer navigation error" }
        val reads=historyReads
        ui.runOnIdle { gate.completeExceptionally(IllegalStateException("Old stop failure")) }
        ui.waitUntil(5_000) { session.decision.action!="busy" };ui.waitForIdle()
        assertEquals("Newer navigation error",session.error);assertEquals("Revisited independent draft",session.text);assertEquals(reads,historyReads);assertEquals(1,stops.size);owned.nodeShot(ui.onNodeWithTag("composer"),"late-stop-revisited-draft")
    }
    @Test fun lateStopSuccessCannotRefreshARevisitedSelection() {
        setup(true);val gate=CompletableDeferred<Unit>();stopGates[turn]=gate
        ui.onNodeWithTag("stop-turn").performClick();ui.waitUntil(5_000) { stops.size==1 }
        ui.runOnIdle { val a=session.threads.first();session.selectThread(session.threads.last());session.selectThread(a);session.text="Keep newer A draft" }
        val reads=historyReads
        ui.runOnIdle { gate.complete(Unit) };ui.waitUntil(5_000) { session.decision.action!="busy" };ui.waitForIdle()
        assertEquals("Late stop cannot start a fresh read for another selection",reads,historyReads);assertEquals("Keep newer A draft",session.text);assertEquals(1,stops.size)
    }
    @Test fun oldStopCompletionCannotUnlockTheNewerTurnStop() {
        setup(true);val old=CompletableDeferred<Unit>();val newer=CompletableDeferred<Unit>();stopGates[turn]=old;stopGates["newer-turn"]=newer
        ui.onNodeWithTag("stop-turn").performClick();ui.waitUntil(5_000) { stops.size==1 }
        ui.runOnIdle { turn="newer-turn";session.loadHistory(userInitiated=true);session.error="Newer turn error" }
        ui.onNodeWithTag("stop-turn").assertIsEnabled().performClick();ui.waitUntil(5_000) { stops.size==2 }
        ui.runOnIdle { old.completeExceptionally(IllegalStateException("Old turn failure")) };ui.waitForIdle()
        assertEquals("busy",session.decision.action);assertEquals("Newer turn error",session.error);owned.nodeShot(ui.onNodeWithTag("composer"),"new-stop-stays-busy")
        assertEquals(listOf("control-a" to "original-turn","control-a" to "newer-turn"),stops)
        ui.runOnIdle { newer.complete(Unit) };ui.waitUntil(5_000) { session.decision.action=="stop" };assertEquals(2,stops.size)
    }
    @Test fun currentStopFailureIsShownButDoesNotReplaceANewerError() {
        setup(true);val first=CompletableDeferred<Unit>();stopGates[turn]=first
        ui.onNodeWithTag("stop-turn").performClick();ui.waitUntil(5_000) { stops.size==1 }
        ui.runOnIdle { first.completeExceptionally(IllegalStateException("Current stop failure")) }
        ui.waitUntil(5_000) { session.decision.action=="stop" };assertEquals("Current stop failure",session.error)
        val second=CompletableDeferred<Unit>();stopGates[turn]=second
        ui.onNodeWithTag("stop-turn").performClick();ui.waitUntil(5_000) { stops.size==2 }
        ui.runOnIdle { session.error="Newer independent error";second.completeExceptionally(IllegalStateException("Late same target failure")) }
        ui.waitUntil(5_000) { session.decision.action=="stop" };assertEquals("Newer independent error",session.error)
    }
    @Test fun pendingSameTurnStopCannotBeDuplicatedAfterRevisit() {
        setup(true);val gate=CompletableDeferred<Unit>();stopGates[turn]=gate
        ui.onNodeWithTag("stop-turn").performClick();ui.waitUntil(5_000) { stops.size==1 }
        ui.runOnIdle { val a=session.threads.first();session.selectThread(session.threads.last());session.selectThread(a);session.performAction() }
        ui.waitForIdle();assertEquals("busy",session.decision.action);assertEquals(1,stops.size)
        ui.runOnIdle { gate.complete(Unit) };ui.waitUntil(5_000) { session.decision.action=="stop" };assertEquals(1,stops.size)
    }

}
