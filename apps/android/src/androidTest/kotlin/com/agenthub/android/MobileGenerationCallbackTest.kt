package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Retained real form callbacks model input delivered after a newer field/target update. */
@RunWith(AndroidJUnit4::class)
class MobileGenerationCallbackTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private val scope=TargetScope("codex",null,null,"generation-fixture","generation-workspace","generation-runtime")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private var catalogueSpeeds=listOf("standard","fast")
    private fun setup(speeds: List<String> = listOf("standard","fast"),runtimeSpeed: String? = null) {
        catalogueSpeeds=speeds
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",scope.hostId).put("name","synthetic generation callbacks").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("projects",JSONArray())
                .put("threads",JSONArray(listOf("generation-a","generation-b").map { JSONObject().put("id",it).put("hostId",scope.hostId).put("title",it).put("state","idle") }))
                .put("capabilities",JSONArray(listOf("reply","commands:target-bound:v1","generation-settings","generation-speed","models:read","threads:create","threads:create:projectless","desktop:creations","desktop:creations:projectless"))).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",scope.hostId).put("threadId",threadId)
                .put("readTarget",CodexCommandCodec.target(scope)).put("messages",JSONArray().put(JSONObject().put("id","body").put("role","assistant").put("text","synthetic complete body")))
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray())
                    .put("receipts",JSONArray()).put("canControl",true).put("generationSettings",JSONObject().put("model","fixture-model").put("speed",runtimeSpeed ?: JSONObject.NULL))).toString())
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf("fixture-model","other-model").map { CodexModel(it,it,it=="fixture-model","low",listOf("low","high"),"standard",catalogueSpeeds) }
        }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reader,commands) }
        main { session.connect("synthetic generation callbacks","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.models.size==2&&session.canConfigureGeneration }
        ui.onNodeWithTag("generation-settings").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("sheet-generation").assertIsDisplayed();true }.getOrDefault(false) }
    }
    private fun retained(tag: String): ()->Boolean {
        val picker=if(tag.startsWith("model-")) "model-picker" else null
        if(picker!=null) ui.onNodeWithTag(picker).performScrollTo().performClick()
        val actual=if(tag=="speed-fast") "fast-mode-toggle" else tag
        return checkNotNull(ui.onNodeWithTag(actual).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun retainedPermissionChoiceKeepsNewModelEffortAndSpeed() {
        setup();val click=retained("permission-guardian-approvals")
        ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high",null,"fast"));click() }
        assertEquals(GenerationSettings("fixture-model","high","guardian-approvals","fast"),session.generationSettings)
    }
    @Test fun retainedSpeedChoiceKeepsNewPermissionAndEffort() {
        setup();val click=retained("speed-fast")
        ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","guardian-approvals","standard"));click() }
        assertEquals(GenerationSettings("fixture-model","high","guardian-approvals","fast"),session.generationSettings)
    }
    @Test fun retainedModelChoiceReconcilesCurrentFieldsInsteadOfOldSnapshot() {
        setup();val click=retained("model-other-model")
        ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","guardian-approvals","fast"));click() }
        assertEquals(GenerationSettings("other-model","high","guardian-approvals","fast"),session.generationSettings)
    }
    @Test fun oldTargetFormCannotConfigureAnotherTargetEvenWhenItIsWritable() {
        setup();val click=retained("permission-guardian-approvals")
        val expected=GenerationSettings("other-model","high",null,"fast")
        // Keep the callback queued from A until B is ready, before a frame can
        // rebind this stable semantics action to the newly displayed form.
        ui.runOnIdle {
            session.selectThread(session.threads.last())
            assertFalse(session.historyLoading);assertTrue(session.canConfigureGeneration)
            session.configure(expected);click()
        }
        assertEquals(expected,session.generationSettings);assertEquals("generation-b",session.selectedThread)
    }
    private fun rememberNewDefaultsThenReturnToOldDraft(old: GenerationSettings) {
        main { session.configure(old);session.selectThread(session.threads.last()) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.canConfigureGeneration }
        main { session.configure(GenerationSettings("other-model","high","full-access","fast"));session.selectThread(session.threads.first()) }
        ui.waitUntil(5_000) { !session.historyLoading&&session.canConfigureGeneration }
        assertEquals(old,session.generationSettings)
    }
    private fun assertNewConversationDefaults(expected: GenerationSettings) {
        main { session.newChat() };ui.waitUntil(5_000) { session.selectedThread.isEmpty()&&session.canConfigureGeneration }
        assertEquals(expected,session.generationSettings)
    }
    @Test fun oldDraftPermissionEditPreservesIndependentlyRememberedModelEffortAndSpeed() {
        setup();rememberNewDefaultsThenReturnToOldDraft(GenerationSettings("fixture-model","low",null,"standard"))
        ui.onNodeWithTag("permission-guardian-approvals").performScrollTo().performClick()
        assertEquals(GenerationSettings("fixture-model","low","guardian-approvals","standard"),session.generationSettings)
        assertNewConversationDefaults(GenerationSettings("other-model","high","guardian-approvals","fast"))
    }
    @Test fun explicitUnchangedPermissionDefaultClearsOnlyRememberedPermission() {
        setup();rememberNewDefaultsThenReturnToOldDraft(GenerationSettings("fixture-model","low",null,"standard"))
        ui.onNodeWithTag("permission-default").performScrollTo().performClick()
        assertNewConversationDefaults(GenerationSettings("other-model","high",null,"fast"))
    }
    @Test fun explicitUnchangedModelDefaultClearsItsDependentPreferencesOnly() {
        setup();rememberNewDefaultsThenReturnToOldDraft(GenerationSettings(permissionMode="guardian-approvals"))
        choose("model-default","model-picker")
        assertNewConversationDefaults(GenerationSettings(permissionMode="full-access"))
    }

    private fun choose(tag: String,picker: String? = null) {
        if(picker!=null && ui.onAllNodesWithTag(picker).fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag(picker).performScrollTo().performClick()
        ui.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun generationShot(name: String) {
        ui.waitForIdle()
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val run=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("managementRunId") ?: error("Dedicated generation run required")
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val folder=java.io.File(i.targetContext.filesDir,"acceptance/P4-generation/$run").apply { mkdirs() }
        val image=checkNotNull(i.uiAutomation.takeScreenshot())
        try { java.io.File(folder,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }finally { image.recycle() }
    }
    @Test fun effectiveModelAndEffortHaveSingleNativeMenuSelection() {
        setup()
        ui.onNodeWithTag("model-picker").assertIsDisplayed().performClick()
        ui.onNodeWithTag("model-fixture-model").assertIsSelected();ui.onNodeWithTag("model-default").assertIsNotSelected()
        generationShot("model-menu")
        ui.onNodeWithTag("model-fixture-model").performClick()
        ui.onNodeWithTag("effort-picker").performScrollTo().performClick()
        ui.onNodeWithTag("effort-low").assertIsSelected();ui.onNodeWithTag("effort-default").assertIsNotSelected()
        generationShot("effort-menu")
        ui.onNodeWithTag("effort-high").performClick();assertEquals("high",session.generationSettings.effort)
    }
    @Test fun explicitModelDefaultUsesSharedDependentFieldNormalization() {
        setup();ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","full-access","fast")) }
        choose("model-default","model-picker")
        assertEquals(GenerationSettings(permissionMode="full-access",speed="standard"),session.generationSettings)
        assertNewConversationDefaults(GenerationSettings(permissionMode="full-access",speed="standard"))
    }
    @Test fun fastToggleAndResetFollowEffectiveSpeedWithoutChangingOtherFields() {
        setup();ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high","guardian-approvals","standard")) }
        ui.onNodeWithTag("fast-mode-toggle").performScrollTo().assertIsOff().assertIsEnabled().performClick()
        ui.onNodeWithTag("fast-mode-toggle").assertIsOn()
        assertEquals(GenerationSettings("fixture-model","high","guardian-approvals","fast"),session.generationSettings)
        generationShot("fast-toggle")
        ui.onNodeWithTag("reset-speed").performScrollTo().performClick()
        assertEquals(GenerationSettings("fixture-model","high","guardian-approvals",null),session.generationSettings)
        ui.onNodeWithTag("fast-mode-toggle").assertIsOff()
    }
    @Test fun multiSpeedMenuAndUnsupportedFastFollowDeclaredCatalogue() {
        setup(listOf("standard","fast","ultrafast"))
        ui.onNodeWithTag("speed-picker").performScrollTo().performClick()
        ui.onNodeWithTag("speed-standard").assertIsSelected();ui.onNodeWithTag("reset-speed-choice").assertIsNotSelected()
        generationShot("speed-menu")
        ui.onNodeWithTag("speed-ultrafast").performClick();assertEquals("ultrafast",session.generationSettings.speed)
        ui.runOnIdle { catalogueSpeeds=listOf("standard");session.retryModels() }
        ui.waitUntil(5_000) { session.selectedModel?.supportedSpeeds==listOf("standard") }
        ui.onNodeWithTag("fast-mode-toggle").performScrollTo().assertIsNotEnabled().assertIsOff()
        assertEquals("ultrafast",session.generationSettings.speed)
        generationShot("fast-unavailable")
    }

    @Test fun liveGenerationSelectorMakesMatchingRuntimeSpeedAnExplicitDraftChoice() {
        setup(runtimeSpeed="fast")
        assertNull(session.generationSettings.speed);assertEquals("fast",session.selectedSpeed)
        ui.selectNativeGeneration("fixture-model","high","fast","full-access")
        assertEquals(GenerationSettings("fixture-model","high","full-access","fast"),session.generationSettings)
        ui.selectNativeGeneration("fixture-model","low","standard","guardian-approvals")
        assertEquals(GenerationSettings("fixture-model","low","guardian-approvals","standard"),session.generationSettings)
    }
    @Test fun liveGenerationSelectorUsesDeclaredMultiSpeedMenu() {
        setup(listOf("standard","fast","ultrafast"))
        ui.selectNativeGeneration("other-model","high","ultrafast","guardian-approvals")
        assertEquals(GenerationSettings("other-model","high","guardian-approvals","ultrafast"),session.generationSettings)
    }

}
