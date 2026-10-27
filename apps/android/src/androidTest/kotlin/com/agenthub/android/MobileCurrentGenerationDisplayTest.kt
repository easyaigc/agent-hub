package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose controls, isolated local drafts and synthetic owner reads; no real model input. */
class MobileCurrentGenerationDisplayTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var previous: MainActivity.AcceptanceSessionState
    private lateinit var server: CommandFixture
    private val session get()=owned.session
    private val scope=TargetScope("codex",null,null,"current-model-host","current-model-workspace","current-model-runtime")
    private val values=mutableMapOf<String,Pair<String?,String?>>("model-a" to ("fixture-a" to "xhigh"),"model-b" to ("fixture-b" to "high"))
    private val text get()=NativeL10n(ui.activity,"en")
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun begin() {
        server=CommandFixture { WireResponse(404) }
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        main {
            owned=OwnedControlAcceptance(ui.activity,"current-generation-display")
            session.resetAcceptance()
            previous=ui.activity.adoptSessionForAcceptance(session)
        }
    }
    @After fun finish() {
        if(::owned.isInitialized) {
            main { session.leaveForeground();if(::previous.isInitialized)ui.activity.restoreSessionForAcceptance(previous) }
            owned.verifyDefault();assertTrue(session.operationRecords.isEmpty())
        }
        if(::server.isInitialized) { assertTrue(server.requests.all { it.method=="GET" });server.close() }
    }
    private fun setup(known: Boolean=true) {
        if(!known) values["model-a"]=null to null
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","synthetic current model").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray())
                .put("threads",JSONArray(values.keys.map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") }))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1","generation-settings","generation-speed","models:read","threads:create","threads:create:projectless","desktop:creations","desktop:creations:projectless"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val value=values.getValue(threadId)
                return HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(scope))
                    .put("messages",JSONArray().put(JSONObject().put("id","current-model-body-$threadId").put("role","assistant").put("text","Synthetic completed body")))
                    .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                        .put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)
                        .put("generationSettings",JSONObject().put("model",value.first ?: JSONObject.NULL).put("effort",value.second ?: JSONObject.NULL))).toString())
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf("fixture-a" to "Fixture A","fixture-b" to "Fixture B").map { (id,name) ->
                CodexModel(id,name,id=="fixture-a","low",listOf("low","high","xhigh"),"standard",listOf("standard","fast"))
            }
        }
        main { session.resetAcceptance(reader,commands);session.changeLanguage("en");session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme") ?: "light") }
        main { session.connect("synthetic current model",server.endpoint,"") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading&&session.threads.size==2 }
        select("model-a")
    }
    private fun select(id: String) {
        main { session.selectThread(session.threads.single { it.id==id }) }
        ui.waitUntil(10_000) { session.selectedThread==id&&!session.historyLoading&&session.runtime!=null&&session.models.size==2&&session.canConfigureGeneration }
        ui.waitForIdle()
    }
    private fun toolbar(): String = ui.onNodeWithTag("generation-settings").assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")
    private fun shot(name: String) {
        ui.waitForIdle();val instrument=InstrumentationRegistry.getInstrumentation();instrument.waitForIdleSync()
        val frames=CountDownLatch(1);ui.runOnUiThread { ui.activity.window.decorView.postOnAnimation { ui.activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS));instrument.uiAutomation.waitForIdle(300,5_000)
        assertEquals(ui.activity.packageName,instrument.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image=instrument.uiAutomation.takeScreenshot()
        try { File(owned.folder,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @Test fun originalDesktopSettingsAndNextSendOverrideRemainDistinctInToolbarAndPanel() {
        setup();main { session.text="unsent original draft";session.configure(GenerationSettings("fixture-b","low",null,"fast")) }
        assertTrue(toolbar().contains("Fixture A"));assertTrue(toolbar().contains(text.tr("极高")))
        assertFalse(toolbar().contains("Fixture B"));assertEquals("low",session.generationSettings.effort)
        shot("actual-toolbar-with-unsent-override")
        ui.onNodeWithTag("generation-settings").performClick()
        assertNotEquals("English cannot silently fall back to original copy", "以下选择用于下次发送，发送前不会修改电脑当前设置。", text.tr("以下选择用于下次发送，发送前不会修改电脑当前设置。"))
        ui.onNodeWithText(text.tr("电脑当前设置")).assertExists()
        ui.onNodeWithText("Fixture A · "+text.tr("极高")).assertExists()
        ui.onNodeWithText(text.tr("以下选择用于下次发送，发送前不会修改电脑当前设置。")).assertExists()
        ui.onNodeWithTag("model-picker").assertTextContains("Fixture B",substring=true)
        owned.dialogShot("actual-and-next-panel")
        assertEquals("unsent original draft",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun missingOwnerSettingsNeverUseCatalogueDefaultOrDraftOverrideAsActual() {
        setup(false);main { session.configure(GenerationSettings("fixture-b","low")) }
        assertNull(session.generationDisplay.model);assertNull(session.displayedEffort)
        assertTrue(toolbar().contains(text.tr("同步中")));assertTrue(toolbar().contains(text.tr("跟随电脑")))
        assertFalse(toolbar().contains("Fixture A"));assertFalse(toolbar().contains("Fixture B"))
        shot("unknown-owner-not-directory-default")
    }
    @Test fun newDraftUsesItsNextSendSettingsAndNeverClaimsAnExistingDesktopThread() {
        setup();main { session.newChat(null);session.text="new independent draft";session.configure(GenerationSettings("fixture-b","low")) }
        ui.waitUntil(10_000) { session.selectedThread.isEmpty()&&session.canConfigureGeneration }
        assertTrue(toolbar().contains("Fixture B"));assertTrue(toolbar().contains(text.tr("低")))
        assertFalse(toolbar().contains(text.tr("同步中")))
        ui.onNodeWithTag("generation-settings").performClick()
        ui.onNodeWithText(text.tr("电脑当前设置")).assertDoesNotExist()
        ui.onNodeWithTag("model-picker").assertTextContains("Fixture B",substring=true)
        assertEquals("new independent draft",session.text);owned.dialogShot("new-draft-next-send-only")
    }
    @Test fun perChatActualSettingsRefreshWithoutChangingIndependentDraftOverrides() {
        setup();main { session.text="draft-a";session.configure(GenerationSettings("fixture-b","low")) }
        select("model-b");main { session.text="draft-b" }
        assertTrue(toolbar().contains("Fixture B"));assertTrue(toolbar().contains(text.tr("高")))
        select("model-a");assertEquals("draft-a",session.text);assertEquals("low",session.generationSettings.effort)
        assertTrue(toolbar().contains("Fixture A"));assertTrue(toolbar().contains(text.tr("极高")))
        main { values["model-a"]="fixture-b" to "high";session.loadHistory(userInitiated=true) }
        ui.waitUntil(10_000) { session.generationDisplay.model=="fixture-b"&&session.displayedEffort=="high"&&!session.historyLoading }
        assertTrue(toolbar().contains("Fixture B"));assertEquals("low",session.generationSettings.effort);assertEquals("draft-a",session.text)
        shot("updated-owner-keeps-draft-override")
    }
}
