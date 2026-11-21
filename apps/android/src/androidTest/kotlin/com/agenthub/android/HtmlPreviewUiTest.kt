package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Local-only resource surface; never connects a Host or sends a model task. */
class HtmlPreviewUiTest {
    @get:Rule val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    private var opened by mutableStateOf<MobileSession.PreparedMessageResource?>(null)
    private val files=mutableListOf<File>()
    private val evidence=HtmlPreviewEvidence("permission-ui")
    private var language by mutableStateOf("en")
    private fun file(name: String): MobileSession.PreparedMessageResource {
        val path=File(ui.activity.cacheDir,"html-ui-${UUID.randomUUID()}.html")
        path.writeText("<button onclick=\"this.textContent='OK'\">Go</button>")
        files.add(path)
        return MobileSession.PreparedMessageResource(name,"text/html",path)
    }
    @After fun clean(): Unit {
        var failure: Throwable?=null
        fun attempt(action: ()->Unit) {
            try { action() } catch(error: Throwable) {
                val original=failure
                if(original==null)failure=error else if(original!==error)original.addSuppressed(error)
            }
        }
        // Retire the production preview before disposing this fixture's composition.
        attempt { ui.runOnIdle { opened=null };ui.waitForIdle() }
        attempt { ui.runOnIdle {
            val content=ui.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            for(index in 0 until content.childCount)
                (content.getChildAt(index) as? androidx.compose.ui.platform.AbstractComposeView)?.disposeComposition()
        } }
        files.forEach { file -> attempt { assertTrue(file.delete()) } };files.clear()
        failure?.let { throw it }
    }
    private fun offline() { ui.onNodeWithTag("visualization-network-offline").assertTextEquals("Offline preview; external resources blocked") }
    private fun grant() {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("visualization-network-permission").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false }
        ui.onNodeWithTag("visualization-network-permission").performClick()
        ui.onNodeWithTag("visualization-network-allow").assertIsDisplayed().assertIsEnabled().assertHasClickAction().performClick()
        ui.onNodeWithTag("visualization-network-online").assertTextEquals("Online resources allowed for this preview")
        ui.onNodeWithTag("visualization-network-permission").assertTextContains("Go offline")
    }
    private fun capturePhase(name: String,expectedLanguage: String,labels: List<Pair<String,String>>,expectedRoot: android.view.View?=null) {
        fun observe(): org.json.JSONObject {
            ui.waitForIdle()
            val selected=ui.runOnIdle { language };assertEquals(expectedLanguage,selected)
            val texts=org.json.JSONArray()
            for((tag,expected) in labels) {
                val node=(if(tag.isEmpty())ui.onNodeWithText(expected) else ui.onNodeWithTag(tag))
                    .assertIsDisplayed().assertTextContains(expected).fetchSemanticsNode()
                texts.put(org.json.JSONObject().put("tag",tag).put("expected",expected).put("nodeId",node.id)
                    .put("actual",node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("\n") { it.text })
                    .put("boundsInRoot",node.boundsInRoot.toString()))
            }
            return org.json.JSONObject().put("providerLanguage",selected).put("labels",texts)
                .put("semanticsObservedElapsedNanos",android.os.SystemClock.elapsedRealtimeNanos())
        }
        val before=observe()
        val state=org.json.JSONObject().put("phase",name).put("before",before).put("languageVisualVerified",false)
        try {
            state.put("windowCaptured",evidence.screenshot(name,excludedRoot=ui.activity.window.decorView,phaseEvidence=before,expectedRoot=expectedRoot))
            state.put("after",observe())
            state.put("languageStateBeforeAndAfterAsserted",true)
        } catch(error: Throwable) {
            state.put("failure",error.javaClass.simpleName+": "+error.message?.take(240));throw error
        } finally { evidence.json("$name-language-state.json",state) }
    }
    /** Visual source diagnosis only: no grant/revoke/reopen cycle or external resource request. */
    @Test fun offlinePreviewOwnWindowLayerDiagnosis(): Unit {
        // Public package metadata, captured once for this exact case before any HTML observation.
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val packageRecord=org.json.JSONObject().put("schemaVersion",1)
            .put("selector","com.agenthub.android.HtmlPreviewUiTest#offlinePreviewOwnWindowLayerDiagnosis")
            .put("status","unsupported").put("api","PackageManager.getPackageInfo(String,0)")
        var packageFailure: Throwable?=null
        try {
            val caseId=checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ownerCallbacksRun"))
            check(UUID.fromString(caseId).toString()==caseId) { "invalid-html-case-id" }
            packageRecord.put("ownerCallbacksRun",caseId)
            check(target.packageName=="com.easyai.agenthub.android.acceptance"&&
                target.applicationInfo.uid==android.os.Process.myUid()&&
                ui.activity.packageName==target.packageName&&ui.activity.applicationInfo.uid==target.applicationInfo.uid) {
                "unsupported-html-package-context"
            }
            val started=android.os.SystemClock.elapsedRealtimeNanos()
            @Suppress("DEPRECATION")
            val info=target.packageManager.getPackageInfo(target.packageName,0)
            val app=checkNotNull(info.applicationInfo)
            check(info.packageName==target.packageName&&app.packageName==target.packageName&&app.uid==target.applicationInfo.uid&&
                info.firstInstallTime>0&&info.lastUpdateTime>=info.firstInstallTime) { "unsupported-html-package-metadata" }
            packageRecord.put("packageName",info.packageName).put("uid",app.uid).put("pid",android.os.Process.myPid())
                .put("contextPackage",target.packageName).put("contextUid",target.applicationInfo.uid)
                .put("versionCode",info.longVersionCode).put("firstInstallTimeMs",info.firstInstallTime)
                .put("lastUpdateTimeMs",info.lastUpdateTime).put("sdk",android.os.Build.VERSION.SDK_INT)
                .put("startedElapsedRealtimeNanos",started).put("finishedElapsedRealtimeNanos",android.os.SystemClock.elapsedRealtimeNanos())
                .put("status","captured").put("timeSource","public-PackageInfo-exact-milliseconds")
        } catch(failure: Throwable) {
            packageFailure=failure;packageRecord.put("failureType",failure.javaClass.name);throw failure
        } finally {
            try { evidence.json("installed-target-package-info.json",packageRecord) }
            catch(saveFailure: Throwable) {
                val original=packageFailure
                if(original==null)throw saveFailure
                if(original!==saveFailure)original.addSuppressed(saveFailure)
            }
        }
        NativeHtmlNetworkProbe.captureProxy(ui.activity.applicationContext,
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("htmlSystemProxyExpectation"),"none",evidence)
        val probe=NativeHtmlSyntheticProbe(evidence)
        try {
        val compositor=ui.runOnIdle { NativeHtmlCompositorCapture(evidence,ui.activity,
            ui.activity.window.decorView,syntheticResource={ opened },syntheticProbe=probe) }
        ui.runOnIdle {
            language="en"
            val path=File(ui.activity.cacheDir,"html-dom-${probe.nonce}.html")
            check(!path.exists());path.writeText(probe.fragment);files.add(path)
            val resource=MobileSession.PreparedMessageResource("synthetic-layer-diagnosis.html","text/html",path)
            probe.claim(resource)
            ui.activity.setContent {
                DisposableEffect(probe) {onDispose {probe.close()}}
                MaterialTheme { ProductTextProvider(language) {
                opened?.let { value -> key(value) { NativeResourcePreview(value,close={opened=null},current={opened===value}) } }
            } } }
            opened=resource
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("visualization-network-permission").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false }
        fun observe(stage: String,expectedLanguage: String,labels: List<Pair<String,String>>,tags: List<String>): NativeHtmlCompositorCapture.Observation {
            ui.waitForIdle()
            assertEquals(expectedLanguage,ui.runOnIdle { language })
            labels.forEach { (tag,text) -> (if(tag.isEmpty())ui.onNodeWithText(text) else ui.onNodeWithTag(tag)).assertIsDisplayed().assertTextContains(text) }
            val titles=labels.filter { it.first.isEmpty() }
            val nodeTags=tags+titles.map { "title" }
            val nodes=tags.map { ui.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode() }+
                titles.map { ui.onNodeWithText(it.second).assertIsDisplayed().fetchSemanticsNode() }
            val owners=nodes.map { checkNotNull((it.root as? androidx.compose.ui.platform.ViewRootForTest)?.view) }
            val ownership=ui.runOnIdle { org.json.JSONArray(owners.mapIndexed { index,v -> org.json.JSONObject()
                .put("tag",nodeTags[index]).put("ownerClass",v.javaClass.name).put("ownerIdentity",System.identityHashCode(v))
                .put("rootIdentity",System.identityHashCode(v.rootView)).put("token",v.windowToken?.toString())
                .put("uid",v.context.applicationInfo.uid).put("package",v.context.packageName).put("focus",v.hasWindowFocus()) }) }
            evidence.json("$stage-semantic-owners.json",org.json.JSONObject().put("owners",ownership))
            check(owners.all { it===owners.first() }) { "Diagnostic nodes have different actual native semantics owners" }
            val geometry=org.json.JSONArray()
            nodes.forEachIndexed { index,node -> val b=node.boundsInRoot
                geometry.put(org.json.JSONObject().put("tag",nodeTags[index]).put("id",node.id)
                    .put("text",node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("\n") { it.text })
                    .put("bounds",org.json.JSONArray(listOf(b.left,b.top,b.right,b.bottom)))) }
            val record=ui.runOnIdle { NativeHtmlWindowLayers.snapshot(owners.first(),geometry) }
            evidence.json("$stage-view-layers.json",record)
            return NativeHtmlCompositorCapture.Observation(owners.first(),expectedLanguage,geometry)
        }
        fun capture(stage: String,expectedLanguage: String,labels: List<Pair<String,String>>,tags: List<String>) {
            assertTrue("Own-window compositor evidence unavailable: $stage; inspect private receipt",
                compositor.capture(stage) { checkpoint -> observe("$stage-$checkpoint",expectedLanguage,labels,tags) })
        }
        val previewTags=listOf("visualization-network-offline","visualization-network-permission","visualization-web")
        capture("english-offline","en",listOf("visualization-network-offline" to "Offline preview; external resources blocked",
            "visualization-network-permission" to "Allow online resources…"),previewTags)
        ui.runOnIdle { language="zh-Hans" }
        capture("chinese-offline","zh-Hans",listOf("visualization-network-offline" to "默认离线预览，外部资源已阻止",
            "visualization-network-permission" to "允许联网…"),previewTags)
        ui.runOnIdle {probe.close()} // Restore exact shipping ChromeClient before another Dialog takes focus.
        // A known visible AlertDialog is the narrow rendering control, without granting permission.
        ui.onNodeWithTag("visualization-network-permission").assertIsDisplayed().assertIsEnabled().performClick()
        ui.onNodeWithText("预览联网许可").assertIsDisplayed()
        capture("chinese-disclosure","zh-Hans",listOf("" to "预览联网许可","visualization-network-allow" to "允许本次预览联网",
            "visualization-network-decline" to "保持离线"),listOf("visualization-network-allow","visualization-network-decline"))
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {probe.close()}
        }
    }
    @Test fun permissionRequiresConfirmationRevokesAndDoesNotSurviveReopenOrFileChange(): Unit {
        lateinit var first: MobileSession.PreparedMessageResource
        lateinit var second: MobileSession.PreparedMessageResource
        ui.runOnIdle {
            first=file("one.html");second=file("two.html")
            ui.activity.setContent { MaterialTheme { ProductTextProvider(language) {
                opened?.let { resource -> key(resource) { NativeResourcePreview(resource,close={opened=null},current={opened===resource}) } }
            } } }
            opened=first
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("visualization-network-permission").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false }
        offline()
        capturePhase("english-offline","en",listOf("visualization-network-offline" to "Offline preview; external resources blocked",
            "visualization-network-permission" to "Allow online resources…"))
        ui.runOnIdle { language="zh-Hans" }
        ui.onNodeWithTag("visualization-network-offline").assertTextEquals("默认离线预览，外部资源已阻止")
        capturePhase("chinese-offline","zh-Hans",listOf("visualization-network-offline" to "默认离线预览，外部资源已阻止",
            "visualization-network-permission" to "允许联网…"))
        ui.onNodeWithTag("visualization-network-permission").performClick()
        ui.onNodeWithText("预览联网许可").assertExists()
        capturePhase("chinese-disclosure","zh-Hans",listOf("" to "预览联网许可",
            "visualization-network-allow" to "允许本次预览联网","visualization-network-decline" to "保持离线"))
        ui.onNodeWithTag("visualization-network-decline").performClick()
        ui.runOnIdle { language="en" }
        offline()
        ui.onNodeWithTag("visualization-network-permission").performClick()
        ui.onNodeWithText("Preview network permission").assertExists()
        ui.onNodeWithTag("visualization-network-allow").assertTextContains("Allow online resources for this preview")
        ui.onNodeWithTag("visualization-network-decline").assertTextContains("Stay offline").performClick();offline()
        grant()
        ui.onNodeWithTag("visualization-network-permission").performClick();offline()
        grant()
        ui.onNodeWithTag("visualization-preview-close").performClick()
        ui.onNodeWithTag("visualization-preview-screen").assertDoesNotExist()
        ui.runOnIdle { opened=first };offline()
        grant()
        ui.runOnIdle { opened=second };offline()
    }
}
