package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Local plan choice never submits; a pending submitted operation retains its original settings. */
class MobilePlanToggleTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val args get()=InstrumentationRegistry.getArguments()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private var planAvailable=true
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"plan-toggle")
            session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light")
        }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    private fun connect(f: ExecutionFixture) {
        val native=NativeHostReads { "" }
        val reads=object: HostReads by native {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                val value=native.workspace(client,projectCursor,threadCursor)
                return if(planAvailable)value else value.copy(capabilities=value.capabilities.filter { it!="plan-mode" })
            }
        }
        main { session.resetAcceptance(reads);session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light");session.connect("Synthetic plan toggle",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null&&session.models.isNotEmpty() }
        main { allowSyntheticRegressionSharing(session);session.text="Original plan draft 100% %@" }
    }
    private fun read() { main { session.loadHistory() };ui.waitUntil(10_000) { !session.historyLoading };ui.waitForIdle() }
    private fun toggle()=ui.onNodeWithTag("toggle-plan-mode")
    private fun state(active: Boolean) {
        toggle().assertContentDescriptionEquals(NativeL10n(ui.activity,session.language).tr("计划模式"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,NativeL10n(ui.activity,session.language).tr(if(active)"已开启" else "已关闭")))
        assertEquals(if(active)"plan" else "default",session.selectedCollaboration)
    }
    private fun geometry(name: String) {
        ui.onNodeWithTag("generation-toolbar").performScrollTo().assertIsDisplayed();ui.waitForIdle()
        val nodes=listOf("permission-settings","toggle-plan-mode","generation-settings").map { ui.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode() }
        val outer=ui.onNodeWithTag("generation-toolbar").fetchSemanticsNode().boundsInRoot
        for(node in nodes) {
            val rect=node.boundsInRoot
            assertTrue("44dp width and height: $rect",rect.width/ui.density.density>=43.5f&&rect.height/ui.density.density>=43.5f)
            assertTrue("All controls remain inside the toolbar: $rect / $outer",rect.left>=outer.left-1&&rect.right<=outer.right+1)
        }
        assertTrue(nodes[0].boundsInRoot.right<=nodes[1].boundsInRoot.left+1)
        assertTrue(nodes[1].boundsInRoot.right<=nodes[2].boundsInRoot.left+1)
        File(owned.folder,"$name-geometry.json").writeText(JSONObject().put("fontScale",ui.density.fontScale).put("appearance",session.appearance)
            .put("orderedPermissionPlanModel",true).put("all44dp",true).put("allInside",true).toString())
        owned.nodeShot(ui.onNodeWithTag("generation-toolbar"),name)
    }
    @Test fun persistentToggleKeepsOriginalDraftFilesAndGeneration() {
        ExecutionFixture().use { f ->
            connect(f);main { session.addAttachment("Original plan 100%.txt","text/plain","original bytes".toByteArray()) }
            val text=session.text;val files=session.attachments;val settings=session.generationSettings
            state(false);geometry("plan-off")
            toggle().performClick();state(true);geometry("plan-on")
            assertEquals(settings.copy(collaborationMode="plan"),session.generationSettings)
            toggle().performClick();state(false)
            assertEquals(settings.copy(collaborationMode="default"),session.generationSettings)
            assertEquals(text,session.text);assertEquals(files,session.attachments)
            assertTrue(f.server.requests.none { it.method=="POST" })
            ui.onNodeWithTag("composer-add").performScrollTo().performClick()
            ui.onNodeWithTag("choose-files").assertIsDisplayed()
            ui.onNodeWithTag("choose-files").onParent().onChildren().filter(hasTestTag("toggle-plan-mode")).assertCountEquals(0)
        }
    }
    @Test fun unavailableCapabilityHidesOffAndKeepsSelectedPlanDisabled() {
        ExecutionFixture().use { f ->
            planAvailable=false;connect(f);toggle().assertDoesNotExist()
            planAvailable=true;main { session.refresh() };ui.waitUntil(10_000) { !session.loading&&session.canSelectPlan }
            toggle().performScrollTo().performClick();state(true)
            planAvailable=false;main { session.refresh() };ui.waitUntil(10_000) { !session.loading&&!session.canSelectPlan }
            toggle().performScrollTo().assertIsDisplayed().assertIsNotEnabled();state(true)
            assertTrue(f.server.requests.none { it.method=="POST" });geometry("plan-disabled-retained")
        }
    }
    @Test fun runningQueueAllowsPlanAndSteerPreservesDisabledChoice() {
        ExecutionFixture().use { f ->
            f.state="running";f.turn="plan-turn";connect(f)
            toggle().performScrollTo().assertIsEnabled().performClick();state(true)
            main { session.setReplyMode("steer") };toggle().assertIsNotEnabled();state(true)
            main { session.setPlanMode(false) };state(true)
            main { session.setReplyMode("queue") };toggle().assertIsEnabled().performClick();state(false)
            assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun detachedSemanticsCannotChangeNewDraftOrRevisitedTarget() {
        ExecutionFixture().use { f ->
            f.additionalThread="second-thread";connect(f)
            val old=checkNotNull(toggle().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            fun invokeOld() { ui.runOnIdle {
                try { old() } catch(error: IllegalStateException) {
                    assertTrue("Only the SDK detached-node guard is accepted",error.message?.contains("not currently attached")==true)
                    File(owned.folder,"detached-sdk-guard.json").writeText(JSONObject().put("detachedNodeRejected",true).put("businessCallbackDirectlyExercised",false).toString())
                }
            } }
            main { session.text="A new draft revision" };invokeOld();state(false)
            val first=session.threads.first();main { session.selectThread(session.threads.last()) }
            ui.waitUntil(10_000) { !session.historyLoading };invokeOld();state(false)
            main { session.selectThread(first) };ui.waitUntil(10_000) { !session.historyLoading }
            invokeOld();state(false);assertEquals("A new draft revision",session.text)
            toggle().performScrollTo().performClick();state(true);assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun nativePressAcrossDraftReplacementCannotToggleEitherRevision() {
        ExecutionFixture().use { f ->
            connect(f);toggle().performScrollTo().performTouchInput { down(center) }
            try { main { session.text="Replacement while held" };ui.waitForIdle() }
            finally { ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("toggle-plan-mode"))).performTouchInput { up() } }
            state(false);toggle().performTouchInput { click(center) };state(true)
            assertEquals("Replacement while held",session.text);assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun pendingReplyKeepsOriginalSubmittedPlanAndDisablesToggle() {
        ExecutionFixture().use { f ->
            connect(f);toggle().performScrollTo().performClick();state(true);f.holdReplyResponse=true
            try {
                ui.onNodeWithTag("send-message").performScrollTo().performClick()
                assertTrue(f.replyStarted.await(5,java.util.concurrent.TimeUnit.SECONDS))
                toggle().performScrollTo().assertIsNotEnabled();main { session.setPlanMode(false) };state(true)
                val posts=f.server.requests.filter { it.path=="/v1/replies" };assertEquals(1,posts.size)
                assertEquals("plan",JSONObject(String(posts.single().body)).getString("collaborationMode"))
            } finally { f.replyRelease.countDown() }
            ui.waitUntil(10_000) { session.transferStatus==null }
        }
    }
    @Test fun languageChangeUpdatesStateWithoutChangingSelectedMode() {
        ExecutionFixture().use { f ->
            connect(f);toggle().performScrollTo().performClick();state(true)
            main { session.changeLanguage("zh-Hans") };state(true);geometry("plan-chinese-on")
            toggle().performClick();state(false);assertTrue(f.server.requests.none { it.method=="POST" })
        }
    }
    @Test fun originalOwnerReadKeepsNativePlanChoiceLocal() {
        val name=args.getString("planLiveFixture")
        Assume.assumeTrue("Explicit owned read-only source required",name!=null)
        require(name!!.matches(Regex("plan-[a-f0-9]{8}\\.json")))
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/$name")
        val fixture=JSONObject(file.readText());require(java.net.URI(fixture.getString("endpoint")).host=="127.0.0.1")
        val hostId=fixture.getString("hostId");val threadId=fixture.getString("threadId");val target=fixture.getJSONObject("target")
        val native=NativeHostReads { fixture.getString("token") }
        val reader=object: HostReads by native {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                val value=native.workspace(client,projectCursor,threadCursor);val scope=checkNotNull(value.scope)
                check(value.mode=="desktop-runtime"&&value.runtimeConnected!=false&&"plan-mode" in value.capabilities)
                check(scope.agentId=="codex"&&scope.hostId==hostId&&scope.workspaceId==target.getString("workspaceId")&&scope.runtimeId==target.getString("runtimeId"))
                val own=value.threads.filter { it.id==threadId };check(own.size==1)
                return value.copy(projects=emptyList(),threads=own,nextProjectCursor=null,nextThreadCursor=null,pinnedThreadIds=emptyList(),sidebar=null)
            }
            override suspend fun history(client: MobileClient,selected: String,cursor: String?): MobileHistory {
                check(selected==threadId);return native.history(client,selected,cursor)
            }
        }
        try {
            main { session.resetAcceptance(reader,NativeCodexCommands { fixture.getString("token") });session.changeLanguage("en");session.changeAppearance("light")
                session.connect("Owned original model read",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(30_000) { !session.loading&&session.threads.size==1 }
            main { session.selectThread(session.threads.single()) }
            ui.waitUntil(30_000) { !session.historyLoading&&session.models.isNotEmpty()&&session.runtime?.canControl==true }
            assertTrue(session.messages.any { it.role=="assistant"&&it.text.contains("APP32_DECLINED_"+fixture.getString("sourceNonce")) })
            main { session.text="P6 local-only plan "+fixture.getString("nonce");session.addAttachment("local-only.txt","text/plain","not uploaded".toByteArray()) }
            val draft=session.text;val files=session.attachments;val settings=session.generationSettings;val original=session.selectedCollaboration
            require(original in listOf("default","plan"))
            toggle().performScrollTo().assertIsEnabled();geometry("original-owner-plan-before")
            toggle().performTouchInput { click(center) };assertEquals(if(original=="plan")"default" else "plan",session.selectedCollaboration)
            geometry("original-owner-plan-changed")
            toggle().performTouchInput { click(center) };assertEquals(original,session.selectedCollaboration)
            assertEquals(draft,session.text);assertEquals(files,session.attachments)
            ui.onNodeWithTag("open-sidebar").assertContentDescriptionEquals("Open sidebar")
            ui.onNodeWithTag("composer-add").assertContentDescriptionEquals("Add attachment")
            ui.onNodeWithTag("send-message").assertContentDescriptionEquals("Send message")
            assertEquals(settings.copy(collaborationMode=original),session.generationSettings)
            assertNull(session.error)
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.onNodeWithTag("sidebar-client-switch").assertContentDescriptionEquals(NativeL10n(ui.activity,"en").tr("切换客户端，%@，%@",session.selectedClient.name,NativeL10n(ui.activity,"en").tr("原桌面连接")))
            ui.onNodeWithTag("sidebar-recent-toggle").assertTextEquals(NativeL10n(ui.activity,"en").tr("最近"))
            owned.nodeShot(ui.onNodeWithTag("sidebar"),"original-owner-sidebar-copy")
            ui.onNodeWithTag("close-sidebar").performClick()
            File(owned.folder,"real-plan-proof.json").writeText(JSONObject().put("originalTargetMatched",true).put("actualModelReplyRead",true)
                .put("twoLocalToggles",true).put("originalModeRestored",true).put("draftAndFilesPreserved",true).put("phoneModelInputs",0).toString())
        } finally { file.delete() }
    }

}
