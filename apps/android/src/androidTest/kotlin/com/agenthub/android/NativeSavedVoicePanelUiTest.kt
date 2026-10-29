package com.agenthub.android

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.Settings
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual strip, shared production palette/localization, empty native window and native AX.
 * Resource/worker gates are synthetic. No MainActivity, Session startup, JNI, speech or Host. */
class NativeSavedVoicePanelUiTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var lease: String
    private lateinit var profile: String
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(180)
    private val cleanupErrors=mutableListOf<Throwable>()
    @get:Rule(order=1) val failureProof=object: TestWatcher() {
        override fun failed(error: Throwable,description: Description) {
            if(!::folder.isInitialized)return
            fun flatten(t: Throwable): List<Throwable> = if(t is MultipleFailureException)t.failures.flatMap(::flatten)else listOf(t)
            val primary=flatten(error).filter {candidate->cleanupErrors.none {it===candidate}}
            report.put("outcome","FAILED_OR_UNKNOWN").put("primaryFailures",JSONArray(primary.map(::failureJson)))
                .put("cleanupFailures",JSONArray(cleanupErrors.map(::failureJson)))
            NativeRealFileAsrContract.atomic(File(folder,"result.json"),report)
        }
    }
    private fun failureJson(error: Throwable)=JSONObject().put("class",error.javaClass.name)
        .put("message",(error.message?:"").take(4096)).put("stack",JSONArray(error.stackTrace.take(16).map {it.toString()}))
    @get:Rule(order=2) val preflight=object: ExternalResource() {
        override fun before() {
            require(instrumentation.targetContext.packageName==NativeRealFileAsrContract.PACKAGE)
            require(instrumentation.context.packageName==NativeRealFileAsrContract.PACKAGE+".test")
            val args=InstrumentationRegistry.getArguments()
            require(args.keySet().filter {it.startsWith("panelUi")}.toSet()==setOf("panelUiLease","panelUiProfile"))
            lease=requireNotNull(args.getString("panelUiLease"));require(UUID.fromString(lease).toString()==lease)
            profile=requireNotNull(args.getString("panelUiProfile"));require(profile in setOf("short","landscape"))
            require(android.os.Build.VERSION.SDK_INT in setOf(26,37))
            require(kotlin.math.abs(Settings.System.getFloat(instrumentation.targetContext.contentResolver,Settings.System.FONT_SCALE,-1f)-2f)<.01f) {"Actual OS font scale 2 is required; no composed override"}
            require(kotlin.math.abs(instrumentation.targetContext.resources.configuration.fontScale-2f)<.01f)
        }
    }
    @get:Rule(order=3) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    @get:Rule(order=4) val name=TestName()
    private lateinit var folder: File
    private lateinit var diagnostics: NativeSavedVoicePanelDiagnostics
    private lateinit var failureFrames: NativeSavedVoicePanelFailureFrame
    private var failureViewport: JSONObject?=null
    private val fixtures=mutableListOf<NativeSavedVoicePanelFixture>()
    private var permissionBefore=0
    private var axFlagsBefore=0
    private var axFlagsCaptured=false
    private var language by mutableStateOf("zh-Hans")
    private var dark by mutableStateOf(false)
    private var checked=false
    private var evidence=0
    private var layoutEvidence=0
    private val report=JSONObject()
    private fun main(block: suspend ()->Unit)=runBlocking {withContext(Dispatchers.Main){block()}}
    @Before fun setup() {
        require(kotlin.math.abs(ui.activity.resources.configuration.fontScale-2f)<.01f)
        val landscape=ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        require(landscape==(profile=="landscape")) {"Parent must provide the declared actual OS orientation"}
        require(name.methodName in methods)
        val root=NativeRealFileAsrContract.privateDirectory(NativeRealFileAsrContract.privateDirectory(instrumentation.targetContext.filesDir,"acceptance"),"P7-saved-panel")
        val owned=NativeRealFileAsrContract.privateDirectory(root,lease)
        folder=File(owned,name.methodName);require(!folder.exists()) {"UI case already claimed; preserve its original proof instead of replaying"}
        folder=NativeRealFileAsrContract.privateDirectory(owned,name.methodName)
        diagnostics=NativeSavedVoicePanelDiagnostics(folder)
        failureFrames=NativeSavedVoicePanelFailureFrame(ui.activity,folder)
        assertFalse("Fixed input has no original sharing/Host rerecord authority",NativeSavedVoicePanelInputs.originalHostRerecordAuthorized)
        assertFalse("Fixed input must not enable live microphone invocation",NativeSavedVoicePanelInputs.microphoneInvocationsAuthorized)
        assertTrue("Saved-file recognition remains separately authorized",NativeSavedVoicePanelInputs.localSavedFileRecognitionAuthorized)
        assertFalse("Rerecord must be disabled under this independent input",NativeSavedVoicePanelInputs.expectedRerecordEnabled)
        report.put("fixtureInputs",NativeSavedVoicePanelInputs.json()).put("authorizedLiveRerecordAcceptance","pending separate authorized scenario")
        permissionBefore=instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val service=instrumentation.uiAutomation.serviceInfo;axFlagsBefore=service.flags;axFlagsCaptured=true
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo=service
        report.put("axServiceFlagsBefore",axFlagsBefore).put("axServiceFlagsObserved",service.flags)
        report.put("version",1).put("lease",lease).put("case",name.methodName).put("profile",profile)
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("buildFingerprint",android.os.Build.FINGERPRINT)
            .put("package",ui.activity.packageName).put("fontScale",ui.activity.resources.configuration.fontScale)
            .put("osFontScale",Settings.System.getFloat(ui.activity.contentResolver,Settings.System.FONT_SCALE,-1f))
            .put("orientation",ui.activity.resources.configuration.orientation).put("recordAudioBefore",permissionBefore)
            .put("syntheticResourceAndWorkerGates",true).put("realJniAcousticOrNetworkProof",false)
            .put("mainActivityOrDefaultSessionLaunched",false)
            .put("appSha256",NativeRealFileAsrContract.sha(File(instrumentation.targetContext.applicationInfo.sourceDir)))
            .put("runnerSha256",NativeRealFileAsrContract.sha(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("pixelMethod","PixelCopy.owned-activity-window").put("fullSystemComposite",false).put("outcome","RUNNING")
        NativeRealFileAsrContract.immutable(File(folder,"intent.json"),JSONObject(report.toString()))
    }
    @After fun cleanup() {
        if(!::folder.isInitialized)return
        val preserved=JSONArray()
        try {
            main {fixtures.forEach {f->try {preserved.put(f.cleanup())}catch(t: Throwable){cleanupErrors.add(t)}}}
            try {assertEquals(permissionBefore,instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))}catch(t: Throwable){cleanupErrors.add(t)}
            report.put("originalProofs",preserved).put("permissionUnchanged",permissionBefore==instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                .put("outcome",if(checked&&cleanupErrors.isEmpty())"COMPONENT_ASSERTIONS_PASSED_REAL_ENGINE_AND_FULL_DEV_SEPARATE"else"FAILED_OR_UNKNOWN")
                .put("cleanupFailures",JSONArray(cleanupErrors.map(::failureJson)))
        }finally {
            try {
                if(axFlagsCaptured) {
                val service=instrumentation.uiAutomation.serviceInfo;service.flags=axFlagsBefore;instrumentation.uiAutomation.serviceInfo=service
                val restored=instrumentation.uiAutomation.serviceInfo.flags==axFlagsBefore
                report.put("axServiceFlagsRestored",restored)
                if(!restored)cleanupErrors.add(AssertionError("UIAutomation flags restore readback mismatch"))
                }else report.put("axServiceFlagsRestored",true).put("axFlagsObservation","not-captured-or-modified")
            }catch(t: Throwable){cleanupErrors.add(t);report.put("axServiceFlagsRestored",false).put("axFlagsRestoreFailure",t.javaClass.name)}
            report.put("failureFrameDiagnostics",if(::failureFrames.isInitialized)failureFrames.summary()else JSONObject.NULL)
                .put("cleanupFailures",JSONArray(cleanupErrors.map(::failureJson))).put("evidencePoints",evidence).put("finishedAtMs",System.currentTimeMillis())
            if(cleanupErrors.isNotEmpty())report.put("outcome","FAILED_OR_UNKNOWN")
            NativeRealFileAsrContract.atomic(File(folder,"result.json"),report)
        }
        MultipleFailureException.assertEmpty(cleanupErrors)
    }
    private fun fixture(): NativeSavedVoicePanelFixture {
        check(fixtures.size<4);var result: NativeSavedVoicePanelFixture?=null
        main {result=NativeSavedVoicePanelFixture.create(instrumentation.targetContext,folder)}
        return checkNotNull(result).also(fixtures::add)
    }
    private fun content(f: NativeSavedVoicePanelFixture) {
        ui.runOnIdle {failureViewport=null;ui.activity.setContent {
            // The OS density/font are inherited without replacement. Only the component viewport is bounded.
            NativeTheme(dark,language) {
                BoxWithConstraints(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
                    val width=minOf(maxWidth,if(profile=="short")320.dp else 640.dp)
                    val height=minOf(maxHeight,if(profile=="short")420.dp else 240.dp)
                    Surface(Modifier.width(width).height(height).testTag("saved-panel-viewport"),color=MaterialTheme.colorScheme.background) {
                        if(f.controller.active)VoiceInputStrip(f.controller,f.draft.text)
                        else Box(Modifier.fillMaxSize().testTag("saved-panel-detached"))
                    }
                    val scale=LocalDensity.current.fontScale
                    SideEffect {report.put("composedFontScale",scale)}
                }
            }
        }};ui.waitForIdle()
        assertEquals(2f,report.getDouble("composedFontScale").toFloat(),.01f)
    }
    private fun matrix(body: (NativeSavedVoicePanelFixture,String)->Unit) {
        for(locale in listOf("zh-Hans","en"))for(night in listOf(false,true)) {
            val f=fixture();ui.runOnIdle {language=locale;dark=night};content(f)
            body(f,"$locale-${if(night)"dark"else"light"}")
            main {f.preserved()}
        }
    }
    private fun text(key: String)=NativeL10n(ui.activity,language).tr(key).also {
        if(language=="en")require(it!=key) {"English resource missing: $key"}
    }
    private fun node(tag: String)=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun control(tag: String,enabled: Boolean=true): SemanticsNodeInteraction {
        val n=node(tag);if(enabled)n.assertIsEnabled()else n.assertIsNotEnabled()
        n.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val b=n.fetchSemanticsNode().boundsInRoot;val viewport=ui.onNodeWithTag("saved-panel-viewport").fetchSemanticsNode();val v=viewport.boundsInRoot
        failureViewport=JSONObject().put("boundsInComposeRoot",JSONArray(listOf(v.left,v.top,v.right,v.bottom)))
            .put("positionInWindow",JSONArray(listOf(viewport.positionInWindow.x,viewport.positionInWindow.y)))
            .put("sizePx",JSONArray(listOf(viewport.size.width,viewport.size.height)))
        assertTrue("Complete control bounds must be inside the measured viewport: $tag",b.left>=v.left-1f&&b.right<=v.right+1f&&b.top>=v.top-1f&&b.bottom<=v.bottom+1f)
        return n
    }
    private fun tap(tag: String) {control(tag).performTouchInput {click(center)};ui.waitForIdle()}
    private fun layoutRow(l: TextLayoutResult,budget: NativeSavedVoiceReadBudget): JSONObject {
        val c=l.layoutInput.constraints;val lines=JSONArray();val count=budget.takeLines(l.lineCount)
        for(line in 0 until count) {
            val value=budget.read {JSONObject().put("start",l.getLineStart(line)).put("end",l.getLineEnd(line))
                .put("ellipsized",l.isLineEllipsized(line)).put("left",l.getLineLeft(line)).put("right",l.getLineRight(line))
                .put("top",l.getLineTop(line)).put("bottom",l.getLineBottom(line))}?:break
            lines.put(value.value)
        }
        return JSONObject().put("text",budget.clip(l.layoutInput.text.text,2048)).put("size",JSONArray(listOf(l.size.width,l.size.height)))
            .put("paragraph",JSONArray(listOf(l.multiParagraph.width,l.multiParagraph.height))).put("constraints",budget.clip(c.toString(),200))
            .put("minWidth",c.minWidth).put("maxWidth",c.maxWidth).put("minHeight",c.minHeight).put("maxHeight",c.maxHeight)
            .put("fontScale",l.layoutInput.density.fontScale).put("density",l.layoutInput.density.density)
            .put("fontSize",budget.clip(l.layoutInput.style.fontSize.toString(),64)).put("lineHeight",budget.clip(l.layoutInput.style.lineHeight.toString(),64))
            .put("maxLines",l.layoutInput.maxLines).put("softWrap",l.layoutInput.softWrap).put("overflow",budget.clip(l.layoutInput.overflow.toString(),64))
            .put("hasVisualOverflow",l.hasVisualOverflow).put("didOverflowWidth",l.didOverflowWidth).put("didOverflowHeight",l.didOverflowHeight)
            .put("lineCount",l.lineCount).put("linesCaptured",lines.length()).put("lines",lines)
    }
    private fun layout(tag: String,expected: String): JSONObject {
        val n=ui.onNodeWithTag(tag,useUnmergedTree=true).performScrollTo().assertIsDisplayed()
        val budget=NativeSavedVoiceReadBudget();val layouts=mutableListOf<TextLayoutResult>()
        n.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals("One actual Text layout for $tag",1,layouts.size);val l=layouts.single()
        val rows=JSONArray();if(budget.active())rows.put(layoutRow(l,budget))
        val diagnostic=diagnostics.textLayout(tag,expected,rows,budget.active()&&!budget.truncated,budget)
        assertEquals(expected,l.layoutInput.text.text);assertFalse("No clipped or ellipsized text for $tag; immutable layout diagnostic retained",l.hasVisualOverflow)
        assertTrue(l.lineCount>0);assertEquals(expected.length,l.getLineEnd(l.lineCount-1))
        assertTrue((0 until l.lineCount).none {l.isLineEllipsized(it)})
        assertTrue("Complete bounded TextLayoutResult diagnosis required",diagnostic.getBoolean("complete"))
        return JSONObject().put("tag",tag).put("text",expected).put("lines",l.lineCount).put("fontScale",l.layoutInput.density.fontScale)
            .put("size",JSONArray(listOf(l.size.width,l.size.height))).put("constraints",l.layoutInput.constraints.toString())
            .put("hasVisualOverflow",l.hasVisualOverflow).put("lastCharacter",l.getLineEnd(l.lineCount-1))
    }
    private fun recordButtonLayouts(tag: String,expected: String): JSONObject {
        val budget=NativeSavedVoiceReadBudget();val rows=JSONArray();var complete=true
        val candidates=budget.read {ui.onAllNodes((hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true).fetchSemanticsNodes()}?.value.orEmpty()
        if(candidates.size>16)complete=false
        for(n in candidates.take(16)) {
            if(!budget.active()){complete=false;break}
            val layouts=mutableListOf<TextLayoutResult>()
            try {
                val observed=budget.read {
                    ui.onNode(SemanticsMatcher("Exact diagnostic semantics id ${n.id}"){it.id==n.id},useUnmergedTree=true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
                }
                if(observed==null){layouts.clear();complete=false;break}
                if(layouts.size>4)complete=false
                for(l in layouts.take(4)) {
                    if(!budget.active()){complete=false;break}
                    val b=n.boundsInRoot
                    rows.put(layoutRow(l,budget).put("semanticsId",n.id)
                        .put("testTag",budget.clip(n.config.getOrNull(SemanticsProperties.TestTag),128))
                        .put("boundsInRoot",JSONArray(listOf(b.left,b.top,b.right,b.bottom))))
                }
            }catch(t: Throwable){complete=false;if(budget.active())rows.put(JSONObject().put("semanticsId",n.id).put("observation","layout-unavailable").put("failureClass",t.javaClass.name))}
        }
        return diagnostics.buttonLayouts(tag,expected,rows,complete&&budget.active()&&!budget.truncated,budget)
    }
    private fun captureGuardFrame(reason: NativeSavedVoicePanelFailureFrame.Reason,tag: String,detail: ()->JSONObject) {
        try {
            failureFrames.capture(reason,tag) {
                val f=fixtures.lastOrNull();val controller=f?.controller
                JSONObject().put("viewport",checkNotNull(failureViewport){"No measured viewport cached; no frame proof"})
                    .put("language",language).put("dark",dark).put("profile",profile).put("composedFontScale",report.opt("composedFontScale"))
                    .put("provider",controller?.provider?.name ?: JSONObject.NULL).put("phase",controller?.phase?.name ?: JSONObject.NULL)
                    .put("savedFileBusy",controller?.savedFileBusy ?: JSONObject.NULL).put("savedFileStatus",controller?.savedFileStatusKey ?: JSONObject.NULL)
                    .put("controllerStatus",controller?.status ?: JSONObject.NULL).put("guardDetail",detail())
            }
        }catch(t: Throwable) {
            // Screenshot/metadata failures must never replace the original hard assertion below.
            try {report.put("failureFrameBoundaryError",t.javaClass.name)}catch(_: Throwable){}
        }
    }
    private fun buttonLayout(tag: String,key: String): JSONObject {
        control(tag,ui.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled)==null)
        val captured=recordButtonLayouts(tag,text(key)) // Persist actual layouts before expected-label/overflow assertions.
        val label=ui.onNode(hasText(text(key)) and hasAnyAncestor(hasTestTag(tag)),useUnmergedTree=true)
        val list=mutableListOf<TextLayoutResult>();label.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(list)}
        assertEquals(1,list.size);val l=list.single();assertEquals(text(key),l.layoutInput.text.text)
        if(l.hasVisualOverflow)captureGuardFrame(NativeSavedVoicePanelFailureFrame.Reason.BUTTON_OVERFLOW,tag) {
            JSONObject().put("expectedLabel",text(key)).put("actualText",l.layoutInput.text.text).put("hasVisualOverflow",true)
                .put("constraints",l.layoutInput.constraints.toString()).put("size",JSONArray(listOf(l.size.width,l.size.height)))
                .put("paragraph",JSONArray(listOf(l.multiParagraph.width,l.multiParagraph.height))).put("fontScale",l.layoutInput.density.fontScale)
                .put("fontSize",l.layoutInput.style.fontSize.toString()).put("lineHeight",l.layoutInput.style.lineHeight.toString())
                .put("buttonLayoutDiagnostic",captured)
        }
        assertFalse("Full button/chip label must wrap: $tag",l.hasVisualOverflow)
        assertEquals(text(key).length,l.getLineEnd(l.lineCount-1));assertTrue((0 until l.lineCount).none {l.isLineEllipsized(it)})
        assertTrue("Complete button layout diagnosis required",captured.getBoolean("complete"))
        return JSONObject().put("tag",tag).put("text",text(key)).put("lines",l.lineCount).put("noOverflowOrEllipsis",true).put("diagnostic",captured)
    }
    private fun nativeAx(tag: String,key: String,enabled: Boolean,selected: Boolean?=null,literal: Boolean=false): JSONObject {
        if(literal)node(tag)else control(tag,enabled);instrumentation.waitForIdleSync()
        val layoutCapture=recordButtonLayouts(tag,if(literal)key else text(key))
        val rootBudget=NativeSavedVoiceReadBudget(1000);var observedRoot: AccessibilityNodeInfo?=null
        val rootRead=rootBudget.read {
            instrumentation.uiAutomation.waitForIdle(50,600)
            instrumentation.uiAutomation.rootInActiveWindow.also {observedRoot=it}
        }
        report.put("nativeAxRootReadiness",JSONObject().put("quietMs",50).put("globalTimeoutMs",600).put("insideOriginalRootBudgetMs",1000))
        val ownedChildren=java.util.IdentityHashMap<AccessibilityNodeInfo,Boolean>()
        var callerSearchBudget: NativeSavedVoiceReadBudget?=null;var callerSearchTiming: JSONObject?=null
        report.put("nativeAxRootReadBudget",rootBudget.timing())
        try {
        val captured=diagnostics.ax(if(rootRead!=null)observedRoot else null,tag,if(literal)key else text(key),enabled,selected,instrumentation.uiAutomation.serviceInfo.flags)
        val root=checkNotNull(if(rootRead!=null)observedRoot else null) {"Native AX root unavailable"}
        assertEquals(NativeRealFileAsrContract.PACKAGE,root.packageName.toString())
        require(root.windowId>=0) {"Native AX owned window unavailable"}
        val searchBudget=NativeSavedVoiceReadBudget();callerSearchBudget=searchBudget;val matches=mutableListOf<AccessibilityNodeInfo>();var nodes=0;var ownedScopeComplete=true
        fun inOwnedScope(n: AccessibilityNodeInfo): Boolean {
            val pkg=n.packageName;val target=NativeRealFileAsrContract.PACKAGE
            return n.windowId==root.windowId&&pkg!=null&&pkg.length==target.length&&target.indices.all {pkg[it]==target[it]}
        }
        fun visit(n: AccessibilityNodeInfo,depth: Int) {
            require(depth<=32&&++nodes<=512)
            if(!searchBudget.active()||searchBudget.read {inOwnedScope(n)}?.value!=true){ownedScopeComplete=false;return}
            if(n.viewIdResourceName==tag)matches.add(n)
            val count=searchBudget.read {n.childCount}?.value ?: return
            for(index in 0 until minOf(count,512)) {
                val child=searchBudget.read {n.getChild(index)?.also {if(it!==root)ownedChildren[it]=true}}?.value
                if(child==null){ownedScopeComplete=false;if(!searchBudget.active())return}else visit(child,depth+1)
            }
            if(count>512)ownedScopeComplete=false
        }
        visit(root,0);assertEquals("Exactly one real AX node for $tag",1,matches.size);val n=matches.single()
        assertEquals(enabled,n.isEnabled);assertTrue(n.isVisibleToUser)
        val spoken=checkNotNull(searchBudget.read {listOfNotNull(n.text?.toString(),n.contentDescription?.toString()).joinToString(" ")}) {"Native AX owner label read exceeded diagnostic budget"}.value
        callerSearchTiming=searchBudget.timing()
        report.put("nativeAxOwnerSearchBudget",callerSearchTiming)
        if(!spoken.contains(if(literal)key else text(key)))captureGuardFrame(NativeSavedVoicePanelFailureFrame.Reason.AX_OWNER_LABEL,tag) {
            JSONObject().put("expectedLabel",if(literal)key else text(key)).put("actualOwnerLabel",spoken).put("ownerWindowId",n.windowId)
                .put("ownerEnabled",n.isEnabled).put("ownerVisible",n.isVisibleToUser).put("axOwnerDiagnostic",captured).put("buttonLayoutDiagnostic",layoutCapture)
        }
        assertTrue("Actual AX must retain the full localized label: $tag",spoken.contains(if(literal)key else text(key)))
        if(selected!=null)assertEquals(selected,n.isSelected||n.isCheckable&&n.isChecked)
        assertTrue("Native AX search must stay in the verified owned window/package",ownedScopeComplete)
        if(!captured.getBoolean("complete"))captureGuardFrame(NativeSavedVoicePanelFailureFrame.Reason.AX_DIAGNOSTIC_INCOMPLETE,tag) {
            JSONObject().put("expectedLabel",if(literal)key else text(key)).put("callerFoundUniqueOwner",true)
                .put("callerOwnerSearchTiming",callerSearchTiming).put("axOwnerDiagnostic",captured).put("buttonLayoutDiagnostic",layoutCapture)
        }
        assertTrue("Complete exact-owner/descendant diagnosis required",captured.getBoolean("complete"))
        assertTrue("Complete associated TextLayoutResult diagnosis required",layoutCapture.getBoolean("complete"))
        return JSONObject().put("tag",tag).put("text",spoken).put("class",n.className.toString()).put("enabled",n.isEnabled)
            .put("selected",n.isSelected).put("checkable",n.isCheckable).put("checked",n.isChecked).put("clickable",n.isClickable).put("nativeWindowId",n.windowId)
            .put("ownerDiagnostic",captured).put("layoutDiagnostic",layoutCapture)
        }finally {
            try {report.put("nativeAxOwnerSearchBudget",callerSearchTiming ?: callerSearchBudget?.timing() ?: JSONObject.NULL)}catch(t: Throwable){cleanupErrors.add(t)}
            val cleanupBefore=cleanupErrors.size
            ownedChildren.keys.forEach {child->try{child.recycle()}catch(t: Throwable){cleanupErrors.add(t)}}
            observedRoot?.let {ownedRoot->try{ownedRoot.recycle()}catch(t: Throwable){cleanupErrors.add(t)}}
            try {report.put("nativeAxCallerResources",JSONObject().put("childrenAcquired",ownedChildren.size).put("rootAcquired",observedRoot!=null)
                .put("releaseFailures",cleanupErrors.size-cleanupBefore))}catch(t: Throwable){cleanupErrors.add(t)}
        }
    }
    private fun point(label: String,details: JSONObject) {
        require(++evidence<=24);val stem="%02d-%s".format(evidence,label);require(stem.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        val view=ui.onNodeWithTag("saved-panel-viewport").fetchSemanticsNode().boundsInRoot
        details.put("language",language).put("dark",dark).put("requestedMaxDp",if(profile=="short")"320x420"else"640x240")
            .put("actualViewportPx",JSONArray(listOf(view.left,view.top,view.right,view.bottom))).put("systemFontOverriddenInComposition",false)
        NativeRealFileAsrContract.immutable(File(folder,"$stem.json"),details)
        val ready=CountDownLatch(1);instrumentation.runOnMainSync {ui.activity.window.decorView.postOnAnimation {ui.activity.window.decorView.postOnAnimation {ready.countDown()}}}
        assertTrue(ready.await(5,TimeUnit.SECONDS));val decor=ui.activity.window.decorView
        assertTrue(decor.hasWindowFocus());val image=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
        val done=CountDownLatch(1);var result=-1
        try {
            instrumentation.runOnMainSync {PixelCopy.request(ui.activity.window,image,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
            File(folder,"$stem.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
        }finally{image.recycle()}
    }
    @Test fun resourcesLanguagesAndCompleteTextRemainReachableAtActualOsFontTwo() {
        require(profile=="short")
        matrix {f,label->
            val layouts=JSONArray().put(layout("dictation-transcript",f.draft.text)).put(layout("voice-status",text(f.controller.statusCopyKey!!)))
                .put(layout("saved-file-title",text("在手机识别原录音"))).put(layout("saved-file-privacy",text("仅下载语言模型需要联网，录音留在本机。")))
            for((locale,key)in listOf("zh-CN" to "中文（普通话）","en-US" to "英语（美国）")) {
                tap("saved-file-locale-$locale");assertEquals(locale,f.controller.savedFileLocale)
                ui.onNodeWithTag("saved-file-locale-$locale").assertIsSelected()
                layouts.put(buttonLayout("saved-file-locale-$locale",key))
                nativeAx("saved-file-locale-$locale",key,true,true)
            }
            assertEquals(0,f.engine.probes);assertEquals(0,f.engine.installs);assertEquals(0,f.engine.decodes)
            for(state in SavedVoiceAvailability.entries) {
                f.engine.availability=state;tap("saved-file-probe");main {NativeSavedVoicePanelFixture.until {!f.controller.savedFileBusy}}
                val detail=JSONObject().put("state",state.name).put("layouts",JSONArray().put(layout("saved-file-status",text(f.controller.savedFileStatusKey))))
                val installed=state==SavedVoiceAvailability.INSTALLED
                control("saved-file-transcribe",installed)
                detail.put("transcribe",nativeAx("saved-file-transcribe","识别并填入原草稿",installed))
                if(state in setOf(SavedVoiceAvailability.MISSING,SavedVoiceAvailability.CORRUPT)) {
                    detail.put("downloadLabel",buttonLayout("saved-file-download","下载离线模型（约45MB）"))
                    detail.put("download",nativeAx("saved-file-download","下载离线模型（约45MB）",true))
                }else ui.onNodeWithTag("saved-file-download").assertDoesNotExist()
                if(state==SavedVoiceAvailability.UNSUPPORTED)detail.put("initialLayouts",layouts)
                point("$label-${state.name.lowercase()}",detail)
            }
            assertEquals(0,f.engine.installs);assertEquals(0,f.engine.decodes);main {f.controller.endWaiting();NativeSavedVoicePanelFixture.until {!f.controller.active}}
        };checked=true
    }
    private fun busy(download: Boolean) {
        require(profile=="short")
        matrix {f,label->
            f.engine.availability=if(download)SavedVoiceAvailability.MISSING else SavedVoiceAvailability.INSTALLED
            tap("saved-file-probe");main {NativeSavedVoicePanelFixture.until {!f.controller.savedFileBusy}}
            f.engine.gate=CompletableDeferred();tap(if(download)"saved-file-download"else"saved-file-transcribe")
            main {NativeSavedVoicePanelFixture.until {if(download)f.engine.installs==1 else f.engine.decodes==1}}
            for(tag in listOf("saved-file-locale-zh-CN","saved-file-locale-en-US","saved-file-probe","saved-file-transcribe","cancel-dictation"))control(tag,false)
            if(download)control("saved-file-download",false)
            val before=f.controller.savedFileLocale;control("saved-file-locale-en-US",false).performTouchInput {click(center)}
            assertEquals(before,f.controller.savedFileLocale)
            point("$label-busy",JSONObject().put("status",layout("saved-file-status",text(f.controller.savedFileStatusKey)))
                .put("disabledTranscribe",nativeAx("saved-file-transcribe","识别并填入原草稿",false)))
            tap("voice-end-waiting");assertTrue(f.controller.savedFileBusy);assertFalse(f.engine.actualExit)
            control("cancel-dictation",false);control("saved-file-transcribe",false)
            point("$label-closing",JSONObject().put("status",layout("saved-file-status",text("正在结束原录音识别…"))).put("workerExit",false))
            main {f.engine.gate!!.complete(Unit);NativeSavedVoicePanelFixture.until {!f.controller.active&&!f.controller.savedFileBusy}}
            ui.waitForIdle();ui.onNodeWithTag("saved-panel-detached").assertIsDisplayed();assertTrue(f.engine.actualExit)
            assertEquals(if(download)1 else 0,f.engine.installs);assertEquals(if(download)0 else 1,f.engine.decodes);assertEquals(0,f.discardCalls)
            assertTrue(NativeVoiceLocalResults.read(f.storage).isEmpty())
        };checked=true
    }
    @Test fun downloadBusyControlsAndEndWaitingJoinSyntheticWorkerWithoutChangingOriginal()=busy(true)
    @Test fun recognitionBusyControlsAndEndWaitingRejectLateSyntheticText()=busy(false)
    private fun lateSuccess(ownerChanged: Boolean) {
        require(profile=="short")
        matrix {f,label->
            f.engine.availability=SavedVoiceAvailability.INSTALLED
            tap("saved-file-probe");main {NativeSavedVoicePanelFixture.until {!f.controller.savedFileBusy}}
            f.engine.returnSuccess=true;f.engine.gate=CompletableDeferred();tap("saved-file-transcribe")
            main {NativeSavedVoicePanelFixture.until {f.engine.decodes==1}}
            // Locale changes remain forbidden during work. Do not force an impossible production state.
            val originalLocale=f.controller.savedFileLocale
            control("saved-file-locale-en-US",false).performTouchInput {click(center)}
            assertEquals(originalLocale,f.controller.savedFileLocale)
            if(ownerChanged)ui.runOnIdle {f.ownerCurrent=false}else tap("voice-end-waiting")
            assertTrue(f.controller.savedFileBusy);assertEquals(0,f.engine.successfulReturns)
            main {f.engine.gate!!.complete(Unit);NativeSavedVoicePanelFixture.until {!f.controller.savedFileBusy}}
            assertTrue(f.engine.actualExit);assertEquals(1,f.engine.successfulReturns)
            assertEquals("The observed wrapper actually received the engine's successful return",1,f.engine.forwardedSuccessfulReturns)
            assertEquals(NativeSavedVoicePanelFixture.Engine.LATE_TEXT,f.engine.returnedText!!.text)
            assertTrue(NativeVoiceLocalResults.read(f.storage).isEmpty());assertEquals(0,f.discardCalls)
            if(ownerChanged) {
                assertEquals(VoiceInputController.Phase.FAILED,f.controller.phase)
                assertFalse(f.controller.canTranscribeSavedFile)
                tap("voice-end-waiting");main {NativeSavedVoicePanelFixture.until {!f.controller.active}}
            }else main {NativeSavedVoicePanelFixture.until {!f.controller.active}}
            ui.waitForIdle();ui.onNodeWithTag("saved-panel-detached").assertIsDisplayed()
            point("$label-late-success",JSONObject().put("engineSuccessfulReturns",f.engine.successfulReturns)
                .put("successfulReturnsForwardedToProductionCaller",f.engine.forwardedSuccessfulReturns).put("syntheticText",f.engine.returnedText!!.text)
                .put("invalidatedBy",if(ownerChanged)"original-owner-guard"else"end-waiting-generation-and-cancellation")
                .put("busyLocaleChangeRejected",true).put("noStagedOrAppliedResult",true))
        };checked=true
    }
    @Test fun actualSuccessfulEngineReturnAfterOwnerInvalidationCannotStageOrFill()=lateSuccess(true)
    @Test fun actualSuccessfulEngineReturnAfterEndWaitingCannotStageOrFill()=lateSuccess(false)
    @Test fun explicitLocalClosePreservesOriginalAndDraftWithoutHostOrMicrophone() {
        require(profile=="short")
        matrix {f,label->
            control("cancel-dictation").assertContentDescriptionEquals(text("丢弃录音"))
            tap("cancel-dictation");main {NativeSavedVoicePanelFixture.until {!f.controller.active}}
            ui.waitForIdle();ui.onNodeWithTag("saved-panel-detached").assertIsDisplayed();assertEquals(1,f.discardCalls)
            val marker=NativeVoiceDiscardCatalog.read(f.storage).getValue(f.original.id)
            assertEquals(NativeVoiceDiscardRequest(f.original).fingerprint,marker.fingerprint)
            point("$label-closed",JSONObject().put("localTerminalPersisted",true).put("original",runBlocking {f.preserved()}))
        };checked=true
    }
    @Test fun landscapeLongOriginalAndStatusKeepEveryCharacterAndReachBothEnds() {
        require(profile=="landscape")
        matrix {f,label->
            val details=JSONObject().put("draft",layout("dictation-transcript",f.draft.text)).put("status",layout("voice-status",text(f.controller.statusCopyKey!!)))
            assertTrue(details.getJSONObject("draft").getInt("lines")>6)
            details.put("nativeFullOriginalAX",nativeAx("dictation-transcript",f.draft.text,true,literal=true))
            assertFalse(f.inputs.originalHostRerecordAuthorized)
            assertFalse(f.inputs.expectedRerecordEnabled)
            control("voice-record-again",enabled=f.inputs.expectedRerecordEnabled)
            details.put("rerecordInput",f.inputs.json()).put("nativeRerecordDisabled",nativeAx("voice-record-again","重新录音",enabled=false))
            point("$label-bottom",details)
            ui.onNodeWithTag("voice-panel").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
            ui.waitForIdle();ui.onNodeWithTag("dictation-transcript").assertIsDisplayed()
            point("$label-top",JSONObject().put("entireTextLaidOut",true).put("physicalOrientation","landscape"))
            main {f.controller.endWaiting();NativeSavedVoicePanelFixture.until {!f.controller.active}}
        };checked=true
    }
    companion object {private val methods=setOf("actualSuccessfulEngineReturnAfterOwnerInvalidationCannotStageOrFill","actualSuccessfulEngineReturnAfterEndWaitingCannotStageOrFill","resourcesLanguagesAndCompleteTextRemainReachableAtActualOsFontTwo","downloadBusyControlsAndEndWaitingJoinSyntheticWorkerWithoutChangingOriginal","recognitionBusyControlsAndEndWaitingRejectLateSyntheticText","explicitLocalClosePreservesOriginalAndDraftWithoutHostOrMicrophone","landscapeLongOriginalAndStatusKeepEveryCharacterAndReachBothEnds")}
}
