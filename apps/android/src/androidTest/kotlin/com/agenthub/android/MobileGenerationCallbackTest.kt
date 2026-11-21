package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsProperties
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
            override suspend fun models(client: MobileClient)=listOf("fixture-model","other-model").map { CodexModel(it,it,it=="fixture-model","low",listOf("low","high"),"standard".takeIf { it in catalogueSpeeds },catalogueSpeeds) }
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
    @Test fun fastOnlyCatalogueOffersDeclaredSpeedWithoutStandardFallback() {
        setup(listOf("fast"))
        ui.onNodeWithTag("speed-picker").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("speed-standard").assertDoesNotExist()
        ui.onNodeWithTag("speed-ultrafast").assertDoesNotExist()
        ui.onNodeWithTag("speed-fast").performClick()
        assertEquals("fast",session.generationSettings.speed)
        ui.onNodeWithTag("model-picker").performScrollTo().performClick()
        ui.onNodeWithTag("model-default").performClick()
        assertNull(session.generationSettings.speed)
    }
    @Test fun retainedFastSwitchCannotWriteUndeclaredStandardAfterCatalogueRefresh() {
        setup(runtimeSpeed="fast")
        ui.onNodeWithTag("fast-mode-toggle").performScrollTo()
        val click=retained("speed-fast")
        val expected=GenerationSettings("fixture-model","high","guardian-approvals","fast")
        // Deliver the retained action after the synchronous fixture refresh but
        // before recomposition disposes the old toggle's semantics node.
        ui.runOnIdle {
            session.configure(expected);catalogueSpeeds=listOf("fast");session.retryModels()
            assertEquals(listOf("fast"),session.selectedModel?.supportedSpeeds)
            click()
        }
        assertEquals(expected,session.generationSettings)
        ui.onNodeWithTag("fast-mode-toggle").assertDoesNotExist()
        ui.onNodeWithTag("speed-picker").performScrollTo().performClick()
        ui.onNodeWithTag("speed-standard").assertDoesNotExist()
        ui.onNodeWithTag("speed-fast").assertIsSelected()
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
        ui.waitUntil(5_000) { i.uiAutomation.rootInActiveWindow?.packageName?.toString()==i.targetContext.packageName }
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
        // Following the desktop model does not declare a supported speed.
        assertEquals(GenerationSettings(permissionMode="full-access"),session.generationSettings)
        assertNewConversationDefaults(GenerationSettings(permissionMode="full-access"))
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

    /** Run the same production entry on Root's actual compact/wide, large-font configurations. */
    @Test fun toolbarSpeedsStayDistinctAtNarrowWideAndLargeTextWidths() {
        val compactRun=if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("compactSpeedEvidence")=="1") java.util.UUID.randomUUID().toString() else null
        setup(listOf("standard","fast","ultrafast"))
        androidx.test.espresso.Espresso.pressBack()
        for(speed in listOf("standard","fast","ultrafast")) {
            ui.runOnIdle { session.configure(GenerationSettings("fixture-model","high",null,speed)) }
            if(compactRun==null) assertToolbarSpeed(speed)
            else compactSpeedWitness(compactRun,speed,false) { assertToolbarSpeed(speed) }
        }
        ui.runOnIdle { session.configure(GenerationSettings()) }
        assertNull(session.generationSettings.speed)
        if(compactRun==null) assertToolbarSpeed(session.selectedSpeed)
        else compactSpeedWitness(compactRun,"follow-default",true) { assertToolbarSpeed(session.selectedSpeed) }
        val description=ui.onNodeWithTag("generation-settings").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.contains(NativeL10n(ui.activity,session.language).tr("跟随电脑")))
    }

    /** Extra evidence gate only; neither sets configuration nor replaces the production composition. */
    private fun compactSpeedWitness(runId: String,stage: String,follow: Boolean,originalAssertions: ()->Unit) {
        ui.waitForIdle()
        val measured=JSONObject()
        ui.runOnIdle {
            val resources=ui.activity.resources
            measured.put("activityFontScale",resources.configuration.fontScale)
                .put("densityDpi",resources.displayMetrics.densityDpi)
                .put("activityDensity",resources.displayMetrics.density)
                .put("activityWidthPixels",resources.displayMetrics.widthPixels)
                .put("activityHeightPixels",resources.displayMetrics.heightPixels)
                .put("configurationScreenWidthDp",resources.configuration.screenWidthDp)
        }
        val density=ui.density
        measured.put("composeRuleDensity",density.density).put("composeRuleFontScale",density.fontScale)
        val tags=listOf("generation-toolbar","generation-settings","generation-speed-indicator",
            "generation-settings-label","generation-speed-fallback","generation-speed-bolt-0","generation-speed-bolt-1")
        val nodes=JSONObject()
        for(tag in tags) {
            val interaction=ui.onNodeWithTag(tag,useUnmergedTree=true)
            val matches=ui.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes()
            val row=JSONObject().put("count",matches.size).put("present",matches.isNotEmpty())
            if(matches.size==1) {
                val px=matches.single().boundsInRoot
                val dp=interaction.getUnclippedBoundsInRoot()
                row.put("displayed",interaction.isDisplayed())
                    .put("visiblePx",JSONObject().put("left",px.left).put("top",px.top).put("right",px.right).put("bottom",px.bottom))
                    .put("unclippedDp",JSONObject().put("left",dp.left.value).put("top",dp.top.value).put("right",dp.right.value).put("bottom",dp.bottom.value))
                val config=matches.single().config
                row.put("contentDescription",JSONArray(if(config.contains(SemanticsProperties.ContentDescription)) config[SemanticsProperties.ContentDescription] else emptyList<String>()))
            }
            nodes.put(tag,row)
        }
        val toolbar=nodes.getJSONObject("generation-toolbar").optJSONObject("unclippedDp")
        val usableWidth=toolbar?.let { it.getDouble("right")-it.getDouble("left") }
        val effectiveWidth=usableWidth?.let { it/density.fontScale }
        val record=JSONObject().put("version",1).put("runId",runId).put("stageId",java.util.UUID.randomUUID().toString())
            .put("selector","com.agenthub.android.MobileGenerationCallbackTest#toolbarSpeedsStayDistinctAtNarrowWideAndLargeTextWidths")
            .put("stage",stage).put("timeMillis",System.currentTimeMillis()).put("packageName",ui.activity.packageName)
            .put("requestedArg","compactSpeedEvidence=1").put("measurements",measured).put("nodes",nodes)
            .put("draftSpeed",session.generationSettings.speed ?: JSONObject.NULL).put("selectedSpeed",session.selectedSpeed ?: JSONObject.NULL)
            .put("usableToolbarWidthDp",usableWidth ?: JSONObject.NULL).put("derivedEffectiveWidthDp",effectiveWidth ?: JSONObject.NULL)
            .put("internalConstraintMaxWidthDirectlyObserved",false)
            .put("derivation","Unclipped fillMaxWidth generation-toolbar row, already inside the production 16dp padding on each side; do not subtract 32dp again. Divide observed row width by compatible Compose fontScale. Internal Box constraints are not directly observed; absent model label plus actual fallback/bolt nodes independently witness the branch.")
            .put("gateResult","observed-before-gates")
        val folder=java.io.File(ui.activity.filesDir,"acceptance/compact-speed/$runId")
        check(folder.isDirectory || folder.mkdirs())
        android.system.Os.chmod(folder.absolutePath,448)
        val file=java.io.File(folder,"$stage-${record.getString("stageId")}.json")
        check(file.createNewFile())
        android.system.Os.chmod(file.absolutePath,384)
        fun persist() { java.io.FileOutputStream(file).use { stream -> stream.write((record.toString(2)+"\n").toByteArray(Charsets.UTF_8));stream.fd.sync() } }
        fun count(tag: String)=nodes.getJSONObject(tag).getInt("count")
        fun displayed(tag: String) { assertEquals("One actual node: $tag",1,count(tag));assertTrue("Displayed: $tag",nodes.getJSONObject(tag).getBoolean("displayed")) }
        fun inside(child: String,parent: String) {
            val c=nodes.getJSONObject(child).getJSONObject("visiblePx");val p=nodes.getJSONObject(parent).getJSONObject("visiblePx")
            assertTrue("Actual bounds $child inside $parent",c.getDouble("left")>=p.getDouble("left")-1.5 && c.getDouble("right")<=p.getDouble("right")+1.5 && c.getDouble("top")>=p.getDouble("top")-1.5 && c.getDouble("bottom")<=p.getDouble("bottom")+1.5)
        }
        persist()
        try {
            assertEquals("Actual Activity fontScale",1.8,measured.getDouble("activityFontScale"),0.001)
            assertEquals("Compatible Compose fontScale",1.8,density.fontScale.toDouble(),0.001)
            assertTrue("Actual densityDpi",measured.getInt("densityDpi")>0)
            assertTrue("Actual Compose density",density.density.isFinite() && density.density>0f)
            assertEquals("Activity and Compose density",measured.getDouble("activityDensity"),density.density.toDouble(),0.001)
            assertEquals("densityDpi and Compose density",measured.getDouble("densityDpi")/160.0,density.density.toDouble(),0.001)
            displayed("generation-toolbar");displayed("generation-settings");displayed("generation-speed-indicator")
            assertTrue("Observed usable toolbar width",usableWidth!=null && usableWidth.isFinite() && usableWidth>0.0)
            assertTrue("Observed effective width below 220",effectiveWidth!=null && effectiveWidth.isFinite() && effectiveWidth<220.0)
            val px=nodes.getJSONObject("generation-toolbar").getJSONObject("visiblePx")
            assertEquals("Toolbar not horizontally clipped",checkNotNull(usableWidth)*density.density,px.getDouble("right")-px.getDouble("left"),1.5)
            assertEquals("Compact model label must be absent",0,count("generation-settings-label"))
            inside("generation-settings","generation-toolbar");inside("generation-speed-indicator","generation-settings")
            val bolts=when(stage) { "fast"->1; "ultrafast"->2; else->0 }
            if(bolts==0) displayed("generation-speed-fallback") else assertEquals(0,count("generation-speed-fallback"))
            for(index in 0..1) if(index<bolts) displayed("generation-speed-bolt-$index") else assertEquals(0,count("generation-speed-bolt-$index"))
            if(follow) { assertNull(session.generationSettings.speed);assertEquals("standard",session.selectedSpeed) }
            else { assertEquals(stage,session.generationSettings.speed);assertEquals(stage,session.selectedSpeed) }
            val description=nodes.getJSONObject("generation-settings").getJSONArray("contentDescription").toString()
            val text=NativeL10n(ui.activity,session.language)
            assertTrue(description.contains(text.tr(when(stage) { "fast"->"快速"; "ultrafast"->"极速"; else->"标准" })))
            if(follow) assertTrue(description.contains(text.tr("跟随电脑")))
            originalAssertions()
            record.put("gateResult","passed-including-original-assertions");persist()
        } catch(failure: Throwable) {
            record.put("gateResult","failed").put("failureClass",failure.javaClass.name).put("failureMessage",failure.message ?: JSONObject.NULL)
            persist();throw failure
        }
    }

    private fun assertToolbarSpeed(speed: String?) {
        val chip=ui.onNodeWithTag("generation-settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val toolbar=ui.onNodeWithTag("generation-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("Product entry must fit actual toolbar",chip.left>=toolbar.left && chip.right<=toolbar.right)
        val bolts=when(speed) { "ultrafast"->2; "fast"->1; else->0 }
        for(index in 0..1) {
            val node=ui.onNodeWithTag("generation-speed-bolt-$index",useUnmergedTree=true)
            if(index<bolts) node.assertIsDisplayed() else node.assertDoesNotExist()
        }
        val hasModelLabel=ui.onAllNodesWithTag("generation-settings-label",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
        val fallback=ui.onNodeWithTag("generation-speed-fallback",useUnmergedTree=true)
        if(bolts==0 && !hasModelLabel) fallback.assertIsDisplayed() else fallback.assertDoesNotExist()
        ui.onNodeWithTag("generation-speed-follow",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("generation-speed-label",useUnmergedTree=true).assertDoesNotExist()
        val text=NativeL10n(ui.activity,session.language).tr(when(speed) { "standard"->"标准"; "fast"->"快速"; "ultrafast"->"极速"; else->"跟随电脑" })
        val description=ui.onNodeWithTag("generation-settings").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.contains(text))
        if(bolts>0 || !hasModelLabel) {
            val indicator=ui.onNodeWithTag("generation-speed-indicator",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue(indicator.left>=chip.left && indicator.right<=chip.right)
        } else ui.onNodeWithTag("generation-speed-indicator",useUnmergedTree=true).assertDoesNotExist()
    }

    @Test fun retainedUltraChoiceCannotWriteAfterOpenMenuCatalogueDropsSupport() {
        setup(listOf("standard","fast","ultrafast"))
        ui.onNodeWithTag("speed-picker").performScrollTo().performClick()
        val click=checkNotNull(ui.onNodeWithTag("speed-ultrafast").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val expected=GenerationSettings("fixture-model","high","guardian-approvals","standard")
        ui.runOnIdle {
            session.configure(expected);catalogueSpeeds=listOf("standard");session.retryModels()
            assertEquals(listOf("standard"),session.selectedModel?.supportedSpeeds)
            click()
        }
        assertEquals(expected,session.generationSettings)
        assertTrue(session.operationRecords.isEmpty())
    }

    @Test fun speedMenuSelectionImmediatelyUpdatesToolbarAndPreservesFollowRule() {
        setup(listOf("standard","fast","ultrafast"))
        for(speed in listOf("ultrafast","fast","standard")) {
            ui.onNodeWithTag("speed-picker").performScrollTo().performClick()
            ui.onNodeWithTag("speed-$speed").performClick()
            assertEquals(speed,session.generationSettings.speed)
            androidx.test.espresso.Espresso.pressBack()
            ui.onNodeWithTag("generation-settings").assertIsDisplayed()
            assertToolbarSpeed(speed)
            ui.onNodeWithTag("generation-settings").performClick()
        }
        ui.onNodeWithTag("reset-speed").performScrollTo().performClick()
        assertNull(session.generationSettings.speed)
        androidx.test.espresso.Espresso.pressBack()
        assertToolbarSpeed(session.selectedSpeed)
        assertTrue(session.operationRecords.isEmpty())
    }

}
