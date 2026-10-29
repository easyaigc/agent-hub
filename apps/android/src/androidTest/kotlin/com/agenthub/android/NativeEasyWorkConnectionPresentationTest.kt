package com.agenthub.android

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.UUID

internal data class P8PreferenceSelectionAx(
    val tag: String,val rolePresent: Boolean,val role: String?,val toggleableStatePresent: Boolean,
    val selectedPresent: Boolean,val composeSelected: Boolean?,val nativeClass: String?,
    val checkable: Boolean?,val checked: Boolean?,val selected: Boolean?
)
// Compose1.12.1 projects Selected to checked/checkable for this exact role-null menu profile.
// Exact owner/window/text association and enabled/action/whole-bounds guards are separate prerequisites.
internal fun p8ExactPreferenceSelection(observed: P8PreferenceSelectionAx,expectedSelected: Boolean): Boolean =
    observed.tag in setOf("connection-preference-automatic","connection-preference-lan-only","connection-preference-relay-only")&&
        !observed.rolePresent&&observed.role==null&&!observed.toggleableStatePresent&&observed.selectedPresent&&
        observed.composeSelected==expectedSelected&&observed.nativeClass=="android.view.View"&&
        observed.checkable==true&&observed.checked==expectedSelected&&observed.selected==false

internal data class P8PreferenceOptionAx(val value: String,val ownerId: Int,val textId: Int,val windowId: Int,
    val nativeTextToOwnerPath: List<Int>,val labelHash: String,val selection: P8PreferenceSelectionAx)
internal fun p8ExclusivePreferenceState(expected: String,labelHashes: Map<String,String>,options: List<P8PreferenceOptionAx>): Boolean {
    val values=setOf("automatic","lan-only","relay-only")
    return expected in values&&labelHashes.keys==values&&labelHashes.values.toSet().size==3&&labelHashes.values.all {it.matches(Regex("[a-f0-9]{64}"))}&&
        options.size==3&&options.map {it.value}.toSet()==values&&options.map {it.ownerId}.toSet().size==3&&options.map {it.textId}.toSet().size==3&&
        options.map {it.windowId}.toSet().size==1&&options.all {row->
            row.ownerId>=0&&row.textId>=0&&row.windowId>=0&&row.ownerId!=row.textId&&row.nativeTextToOwnerPath.size in 2..16&&
                row.nativeTextToOwnerPath.first()==row.textId&&row.nativeTextToOwnerPath.last()==row.ownerId&&row.nativeTextToOwnerPath.distinct().size==row.nativeTextToOwnerPath.size&&
                row.nativeTextToOwnerPath.dropLast(1).none {id->options.any {it.ownerId==id}}&&row.labelHash==labelHashes[row.value]&&
                row.selection.tag=="connection-preference-${row.value}"&&p8ExactPreferenceSelection(row.selection,row.value==expected)
        }&&options.count {it.selection.checked==true}==1&&options.count {it.selection.checked==false}==2
}

/** Actual AgentHubApp theme/localization and real product navigation, not a copied theme.
 * ComponentActivity hosting, controlled dialog bounds/fonts and physical display lanes
 * are explicitly distinguished. No pairing, scan, permission or business action is tapped.
 */
class NativeEasyWorkConnectionPresentationTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private lateinit var build: JSONObject
    private var defaults=emptyMap<String,String>()
    @get:Rule(order=0) val guard=object: ExternalResource(){override fun before(){
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PresentationBuild")))
        for(k in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(k).matches(Regex("[a-f0-9]{64}")))
        require(UUID.fromString(build.getString("leaseId")).toString()==build.getString("leaseId"))
        require(build.getInt("expectedSdk") in listOf(26,37));assertEquals(build.getInt("expectedSdk"),android.os.Build.VERSION.SDK_INT)
        assertEquals(build.getString("deviceFingerprint"),android.os.Build.FINGERPRINT)
        if(android.os.Build.HARDWARE !in listOf("ranchu","goldfish"))require(build.optBoolean("allowPhysicalUi",false)) {"Physical UI requires an explicit parent device lease; this test never acquires one"}
        assertEquals(build.getString("appSha256"),EasyWorkPresentationFixture.hash(File(i.targetContext.applicationInfo.sourceDir)))
        assertEquals(build.getString("runnerSha256"),EasyWorkPresentationFixture.hash(File(i.context.applicationInfo.sourceDir)))
        defaults=EasyWorkPresentationFixture.defaults(i.targetContext)
    }}
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order=2) val outcome=object: TestWatcher(){
        override fun succeeded(d: Description)=record(d,"passed")
        override fun failed(t: Throwable,d: Description)=record(d,"failed",t.javaClass.name)
    }
    private lateinit var fixture: EasyWorkPresentationFixture
    private lateinit var folder: File
    private lateinit var text: NativeL10n
    private var darkObserved: Boolean?=null
    private var renderedFont=0f
    private var expectedFont=0f
    private var expectedDark=false
    private var presentationLanguage=""
    private var originalWindow: Window?=null
    private var originalSize: Pair<Int,Int>?=null
    private var controlledBounds=false
    private var protectedEvidence: JSONObject?=null
    private val touches=mutableListOf<String>()
    private val probes=JSONArray()
    private var probe=0
    @Before fun setup(){
        folder=File(i.targetContext.filesDir,"acceptance/p8-presentation/$id").apply {check(!exists()&&mkdirs())}
        fixture=EasyWorkPresentationFixture(i.targetContext,id);ui.runOnIdle {fixture.initializeModel()};fixture.initializeOriginals();ui.runOnIdle {fixture.persist()}
    }
    @After fun cleanup(){
        if(::fixture.isInitialized)ui.runOnIdle {
            originalWindow?.let {w->originalSize?.let {w.setLayout(it.first,it.second)}}
            ui.activity.setContent {};fixture.session.connectionBusy=false;fixture.session.leaveForeground();fixture.noActivation()
        }
        if(::fixture.isInitialized)protectedEvidence=fixture.protectedBytes()
        assertEquals(defaults,EasyWorkPresentationFixture.defaults(i.targetContext))
    }
    private fun record(d: Description,result: String,error: String?=null){if(!::folder.isInitialized)return
        val preferenceOnly=d.methodName in setOf("chineseLightPreferenceOptionsStayNativeExclusiveAndPersist","englishDarkPreferenceOptionsStayNativeExclusiveAndPersist")
        File(folder,"result.json").writeText(JSONObject().put("caseId",id).put("method",d.methodName).put("junitResult",result).put("failureClass",error).put("build",build)
            .put("pid",android.os.Process.myPid()).put("deviceFingerprint",android.os.Build.FINGERPRINT).put("touchTags",JSONArray(touches)).put("measurements",probes)
            .put("protectedOriginals",protectedEvidence).put("host","ComponentActivity with actual AgentHubApp; not MainActivity lifecycle proof")
            .put("controlledDialogBounds",controlledBounds).put("busyCoverage",if(preferenceOnly)"not exercised by this preference-only method" else "injected global close guard only; actual connection action busy/error not exercised")
            .put("appLanguage",presentationLanguage).put("resolvedProductLanguage",if(::text.isInitialized)text.language else JSONObject.NULL)
            .put("accessibilityCoverage",if(preferenceOnly)"native preference owners/labels/selection/actions/bounds; no new field-editability, IME or TalkBack coverage" else "rendered Compose and Android node labels/selection/editability; not TalkBack speech or traversal")
            .put("forbiddenTouchActionsRejected",true).put("networkOrModelAcceptance",false).toString())
    }
    private fun launch(language: String,dark: Boolean,landscape: Boolean,fontOverride: Float?=null,imported: Boolean=false){
        val config=ui.activity.resources.configuration;val decor=ui.activity.window.decorView
        assertEquals(build.getDouble("expectedOsFontScale").toFloat(),config.fontScale,.02f)
        assertEquals(if(landscape)Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT,config.orientation)
        ui.waitUntil(5000){decor.width>0&&decor.height>0};assertEquals(landscape,decor.width>decor.height)
        expectedDark=dark;presentationLanguage=language;expectedFont=fontOverride?:config.fontScale;text=NativeL10n(i.targetContext,language)
        assertEquals(if(language=="en")"Name" else "名称",text.tr("名称"))
        ui.runOnIdle {
            if(imported)fixture.imported()
            fixture.session.changeLanguage(language);fixture.session.changeAppearance(if(dark)"dark" else "light")
            ui.activity.setContent {val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,fontOverride?:density.fontScale)) {
                    val actual=LocalDensity.current;SideEffect {renderedFont=actual.fontScale}
                    AgentHubApp(fixture.session){darkObserved=it}
                }
            }
        }
        ui.waitForIdle();assertEquals(dark,darkObserved);assertEquals(expectedFont,renderedFont,.01f)
        touch("client-selector",false);touch("edit-client-${fixture.client.id}")
        fixture.noActivation();capture("edit-open")
    }
    private fun touch(tag: String,scroll: Boolean=true){
        require(tag in listOf("client-selector","cancel-client-form","connection-preference","easywork-connection-method","easywork-name","easywork-relay-url","easywork-relay-code")||tag.startsWith("edit-client-")||tag.startsWith("connection-preference-")||tag.startsWith("easywork-method-")) {"Forbidden action in presentation-only test: $tag"}
        require(tag!="cancel-client-form"||!scroll) {"Footer has no scroll ancestor"}
        val n=ui.onNodeWithTag(tag);if(scroll)n.performScrollTo();if(tag=="cancel-client-form")recordGeometry("footer-before-hit",window())
        n.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val raw=n.getUnclippedBoundsInRoot();val b=n.fetchSemanticsNode().boundsInRoot
        assertTrue("Whole visible hit target $tag",b.width>=(raw.right.value-raw.left.value)*ui.density.density-1.5f&&b.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f)
        n.performTouchInput {click(center)};touches+=tag;ui.waitForIdle()
    }
    private fun window(): Window {
        var result: Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check {view,error->
            if(error!=null)throw error;assertEquals(i.targetContext.packageName,view.context.packageName)
            fun find(v: android.view.View){if(v is androidx.compose.ui.window.DialogWindowProvider)result=v.window;if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))};find(view.rootView)
            assertSame(view.rootView,checkNotNull(result).decorView.rootView)
        };return checkNotNull(result)
    }
    private fun windowObservation(w: Window)=JSONObject().put("widthPx",w.decorView.width).put("heightPx",w.decorView.height)
        .put("osOrientation",ui.activity.resources.configuration.orientation).put("activityWidthPx",ui.activity.window.decorView.width).put("activityHeightPx",ui.activity.window.decorView.height)
        .put("displayRotation",@Suppress("DEPRECATION") ui.activity.windowManager.defaultDisplay.rotation).put("osFontScale",ui.activity.resources.configuration.fontScale)
        .put("renderedFontScale",renderedFont).put("appDarkCallback",darkObserved).put("controlledBounds",controlledBounds)
    private fun capture(label: String){
        val w=window();val view=w.decorView;val b=ui.onNodeWithTag("sheet-add").fetchSemanticsNode().boundsInWindow
        val crop=android.graphics.Rect(kotlin.math.floor(b.left).toInt(),kotlin.math.floor(b.top).toInt(),kotlin.math.ceil(b.right).toInt(),kotlin.math.ceil(b.bottom).toInt())
        check(crop.left>=0&&crop.top>=0&&crop.right<=view.width&&crop.bottom<=view.height&&crop.width() in 1..4096&&crop.height() in 1..4096)
        val image=Bitmap.createBitmap(crop.width(),crop.height(),Bitmap.Config.ARGB_8888);val done=java.util.concurrent.CountDownLatch(1);var status=-1
        try {
            ui.runOnIdle {android.view.PixelCopy.request(w,crop,image,{code->status=code;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
            assertTrue(done.await(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,status)
            File(folder,"$label.png").outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
            probes.put(windowObservation(w).put("kind","actual-owned-dialog-PixelCopy").put("file","$label.png").put("cropInWindow",crop.toShortString()))
        }finally {image.recycle()}
    }
    private fun rectJson(r: androidx.compose.ui.geometry.Rect)=JSONObject().put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom)
    private fun saveProbe(value: JSONObject){probes.put(value);flushProbes()}
    private fun flushProbes(){
        val bytes=probes.toString().toByteArray(Charsets.UTF_8);check(bytes.size<=2*1024*1024) {"Presentation scalar evidence exceeded2MiB"}
        val file=android.util.AtomicFile(File(folder,"measurements.json"));val output=file.startWrite()
        try {output.write(bytes);file.finishWrite(output)}catch(failure: Throwable){file.failWrite(output);throw failure}
    }
    private fun nodeGeometry(tag: String): JSONObject=try {
        val node=ui.onNodeWithTag(tag);val n=node.fetchSemanticsNode();val raw=node.getUnclippedBoundsInRoot();val density=ui.density.density
        val scroll=n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val scrollValue=scroll?.value();val scrollMaximum=scroll?.maxValue()
        JSONObject().put("tag",tag).put("present",true).put("boundsInRootPx",rectJson(n.boundsInRoot)).put("boundsInWindowPx",rectJson(n.boundsInWindow))
            .put("unclippedRootPx",JSONObject().put("left",raw.left.value*density).put("top",raw.top.value*density).put("right",raw.right.value*density).put("bottom",raw.bottom.value*density))
            .put("focused",n.config.getOrNull(SemanticsProperties.Focused)?:JSONObject.NULL).put("disabled",n.config.contains(SemanticsProperties.Disabled))
            .put("verticalScrollValue",scrollValue?.takeIf {it.isFinite()}?:JSONObject.NULL).put("verticalScrollMaximum",scrollMaximum?.takeIf {it.isFinite()}?:JSONObject.NULL)
    }catch(failure: AssertionError){JSONObject().put("tag",tag).put("present",false).put("queryFailureClass",failure.javaClass.name)}
     catch(failure: Exception){JSONObject().put("tag",tag).put("present",false).put("queryFailureClass",failure.javaClass.name)}
    private fun windowGeometry(w: Window): JSONObject {
        val result=windowObservation(w)
        i.runOnMainSync {
            val view=w.decorView;val visible=android.graphics.Rect();view.getWindowVisibleDisplayFrame(visible);val origin=IntArray(2);view.getLocationOnScreen(origin)
            val insets=ViewCompat.getRootWindowInsets(view);val ime=insets?.getInsets(WindowInsetsCompat.Type.ime());val bars=insets?.getInsets(WindowInsetsCompat.Type.systemBars())
            result.put("decorWidthPx",view.width).put("decorHeightPx",view.height).put("decorScreenX",origin[0]).put("decorScreenY",origin[1]).put("softInputMode",w.attributes.softInputMode)
                .put("visibleFrameScreen",JSONObject().put("left",visible.left).put("top",visible.top).put("right",visible.right).put("bottom",visible.bottom))
                .put("insetsAvailable",insets!=null).put("imeVisible",insets?.isVisible(WindowInsetsCompat.Type.ime())==true)
                .put("imeInsets",JSONObject().put("left",ime?.left?:0).put("top",ime?.top?:0).put("right",ime?.right?:0).put("bottom",ime?.bottom?:0))
                .put("systemBarInsets",JSONObject().put("left",bars?.left?:0).put("top",bars?.top?:0).put("right",bars?.right?:0).put("bottom",bars?.bottom?:0))
                .put("hasWindowFocus",view.hasWindowFocus()).put("currentFocusClass",w.currentFocus?.javaClass?.name?:JSONObject.NULL).put("currentFocusViewId",w.currentFocus?.id?:-1)
        };return result
    }
    private fun recordGeometry(stage: String,w: Window){
        saveProbe(windowGeometry(w).put("kind","actual-window-ime-node-geometry").put("stage",stage).put("sampleUptimeMs",android.os.SystemClock.uptimeMillis())
            .put("focusedField",nodeGeometry("easywork-relay-url")).put("button",nodeGeometry("easywork-pair-relay"))
            .put("connectionPage",nodeGeometry("connection-page")).put("sheet",nodeGeometry("sheet-add")).put("footer",nodeGeometry("cancel-client-form")))
    }
    private fun settledIme(w: Window,deadline: Long,stage: String){
        val start=android.os.SystemClock.uptimeMillis();val remaining=deadline-start
        if(remaining<=0){recordGeometry("$stage-budget-exhausted",w);throw AssertionError("Original IME8s budget exhausted")}
        var previous="";var sameSince=0L;var sameCount=0;var samples=0
        try {ui.waitUntil(remaining){
            val now=android.os.SystemClock.uptimeMillis();val state=windowGeometry(w);val signature=state.toString();samples++
            if(signature!=previous){previous=signature;sameSince=now;sameCount=1;saveProbe(JSONObject(state.toString()).put("kind","actual-ime-window-change").put("stage",stage).put("sampleUptimeMs",now).put("remainingBudgetMs",deadline-now))}else sameCount++
            val frame=state.getJSONObject("visibleFrameScreen")
            state.getBoolean("imeVisible")&&state.getJSONObject("imeInsets").getInt("bottom")>0&&state.getInt("decorWidthPx")>0&&state.getInt("decorHeightPx")>0&&frame.getInt("right")>frame.getInt("left")&&frame.getInt("bottom")>frame.getInt("top")&&sameCount>=3&&now-sameSince>=100&&now<=deadline
        }}catch(failure: Throwable){recordGeometry("$stage-not-settled",w);throw failure}
        saveProbe(JSONObject().put("kind","actual-ime-window-settled").put("stage",stage).put("samples",samples).put("sameSamples",sameCount).put("stableForMs",android.os.SystemClock.uptimeMillis()-sameSince).put("remainingBudgetMs",deadline-android.os.SystemClock.uptimeMillis()).put("totalBudgetMs",8000))
    }
    private fun fullText(tag: String,value: String,requireWrap: Boolean=false){
        val n=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals(value);val layouts=mutableListOf<TextLayoutResult>()
        n.performSemanticsAction(SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))};assertEquals(1,layouts.size);val l=layouts.single()
        val b=n.fetchSemanticsNode().boundsInRoot;val raw=n.getUnclippedBoundsInRoot();val frame=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInRoot
        val x=raw.left.value*ui.density.density;val y=raw.top.value*ui.density.density
        fun rect(r: androidx.compose.ui.geometry.Rect)=P8TextRect(r.left,r.top,r.right,r.bottom)
        val clip=P8TextRect(maxOf(b.left,frame.left)-x,maxOf(b.top,frame.top)-y,minOf(b.right,frame.right)-x,minOf(b.bottom,frame.bottom)-y)
        val actual=l.layoutInput.text.text
        val observed=P8TextObservation(actual,l.size.width,l.size.height,clip,(0 until l.lineCount).map {line->P8TextLine(l.getLineStart(line),l.getLineEnd(line,visibleEnd=false),P8TextRect(l.getLineLeft(line),l.getLineTop(line),l.getLineRight(line),l.getLineBottom(line)),l.isLineEllipsized(line))},actual.indices.map {offset->P8TextCharacter(offset,l.getLineForOffset(offset),rect(l.getBoundingBox(offset)))})
        val constraints=l.layoutInput.constraints;val lines=JSONArray();for(line in 0 until l.lineCount)lines.put(JSONObject().put("index",line).put("start",l.getLineStart(line)).put("end",l.getLineEnd(line,false)).put("visibleEnd",l.getLineEnd(line,true)).put("left",l.getLineLeft(line)).put("right",l.getLineRight(line)).put("top",l.getLineTop(line)).put("bottom",l.getLineBottom(line)).put("baseline",l.getLineBaseline(line)).put("ellipsized",l.isLineEllipsized(line)))
        val chars=JSONArray();for(offset in actual.indices)chars.put(JSONObject().put("utf16Offset",offset).put("line",l.getLineForOffset(offset)).put("box",rectJson(l.getBoundingBox(offset))))
        val failures=p8TextVisibilityFailures(value,observed,requireWrap)
        saveProbe(windowGeometry(window()).put("kind","original-text-layout-before-guards").put("tag",tag).put("textHash",easyHash(value.toByteArray())).put("utf16Length",value.length).put("actualUtf16Length",actual.length).put("actualTextHash",easyHash(actual.toByteArray()))
            .put("layoutWidthPx",l.size.width).put("layoutHeightPx",l.size.height).put("paragraphWidthPx",l.multiParagraph.width).put("paragraphHeightPx",l.multiParagraph.height)
            .put("constraints",JSONObject().put("minWidth",constraints.minWidth).put("maxWidth",constraints.maxWidth).put("minHeight",constraints.minHeight).put("maxHeight",constraints.maxHeight))
            .put("hasVisualOverflow",l.hasVisualOverflow).put("didOverflowWidth",l.didOverflowWidth).put("didOverflowHeight",l.didOverflowHeight).put("requireWrap",requireWrap)
            .put("fontScale",l.layoutInput.density.fontScale).put("density",l.layoutInput.density.density).put("fontSize",l.layoutInput.style.fontSize.value)
            .put("node",nodeGeometry(tag)).put("connectionPage",nodeGeometry("connection-page")).put("actualVisibleClipInTextPx",JSONObject().put("left",clip.left).put("top",clip.top).put("right",clip.right).put("bottom",clip.bottom))
            .put("lines",lines).put("utf16Boxes",chars).put("failures",JSONArray(failures)))
        assertFalse("Original overflow guard retained for $tag",l.hasVisualOverflow);assertEquals(value,l.layoutInput.text.text);assertEquals(expectedFont,l.layoutInput.density.fontScale,.01f)
        assertTrue("Full original glyph/line/actual visible clip: $tag $failures",failures.isEmpty())
    }
    private fun targets(){
        for((index,key) in listOf("agent","authority","subject","host","workspace","runtime").withIndex()){
            val label=listOf("Agent 客户端","账号发行方","账号","电脑","工作区","运行时")[index]
            fullText("easywork-target-label-$key",text.tr(label));fullText("easywork-target-$key",EasyWorkPresentationFixture.fields(fixture.scope)[index],requireWrap=controlledBounds&&key!="agent")
            ui.onNodeWithTag("easywork-target-$key").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        }
        // Explicit label color comes from the actual AgentHubApp MaterialTheme inside its dialog.
        val layouts=mutableListOf<TextLayoutResult>();ui.onNodeWithTag("easywork-target-label-runtime").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals(if(expectedDark)androidx.compose.ui.graphics.Color(0xFF98989D) else androidx.compose.ui.graphics.Color(0xFF8A8A8E),layouts.single().layoutInput.style.color)
        probes.put(JSONObject().put("kind","actual-rendered-TextLayout-color").put("label","runtime").put("appDarkCallback",darkObserved).put("actualColor",layouts.single().layoutInput.style.color.toString()))
        nativeTextAx(fixture.scope.runtimeId,readonly=true)
    }
    private fun activeAppRoot(deadline: Long=android.os.SystemClock.uptimeMillis()+5000): android.view.accessibility.AccessibilityNodeInfo {
        var ready: android.view.accessibility.AccessibilityNodeInfo?=null;var nullSamples=0;var otherPackages=0
        val remaining=deadline-android.os.SystemClock.uptimeMillis();assertTrue("Original AX5s budget remains",remaining>0)
        try {ui.waitUntil(remaining){val root=i.uiAutomation.rootInActiveWindow
            if(root==null){nullSamples++;false}else if(root.packageName?.toString()!=i.targetContext.packageName){otherPackages++;@Suppress("DEPRECATION") root.recycle();false}else{ready=root;true}
        }}finally {saveProbe(JSONObject().put("kind","readonly-own-app-AX-readiness").put("nullSamples",nullSamples).put("otherPackageSamples",otherPackages).put("maxWaitMs",5000).put("remainingBudgetMs",deadline-android.os.SystemClock.uptimeMillis()).put("rootReady",ready!=null))}
        return checkNotNull(ready)
    }
    private fun axNodeFlags(n: android.view.accessibility.AccessibilityNodeInfo): JSONObject {
        val screen=android.graphics.Rect();val parent=android.graphics.Rect();n.getBoundsInScreen(screen);n.getBoundsInParent(parent)
        fun box(b: android.graphics.Rect)=JSONObject().put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom)
        val actions=n.actionList.map {it.id}.sorted()
        return JSONObject().put("nodeIdentityHash",n.hashCode()).put("windowId",n.windowId).put("targetPackage",n.packageName?.toString()==i.targetContext.packageName)
            .put("class",n.className?.toString()?:JSONObject.NULL).put("enabled",n.isEnabled).put("editable",n.isEditable).put("visibleToUser",n.isVisibleToUser)
            .put("focusable",n.isFocusable).put("focused",n.isFocused).put("accessibilityFocused",n.isAccessibilityFocused).put("clickable",n.isClickable)
            .put("selected",n.isSelected).put("checkable",n.isCheckable).put("checked",n.isChecked).put("scrollable",n.isScrollable).put("importantForAccessibility",n.isImportantForAccessibility)
            .put("boundsInScreen",box(screen)).put("boundsInParent",box(parent)).put("childCount",n.childCount)
            .put("actionCount",actions.size).put("actionIds",JSONArray(actions.take(64))).put("actionsTruncated",actions.size>64)
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun composeFieldAx(tag: String): JSONObject {
        val result=nodeGeometry(tag)
        for(unmerged in listOf(false,true))try {
            val n=ui.onNodeWithTag(tag,useUnmergedTree=unmerged).fetchSemanticsNode();val config=n.config
            result.put(if(unmerged)"unmerged" else "merged",JSONObject().put("semanticsId",n.id).put("isEditable",config.getOrNull(SemanticsProperties.IsEditable)?:JSONObject.NULL)
                .put("disabled",config.contains(SemanticsProperties.Disabled)).put("hasSetTextAction",config.contains(SemanticsActions.SetText))
                .put("hasEditableText",config.contains(SemanticsProperties.EditableText)).put("hasInputText",config.contains(SemanticsProperties.InputText)))
        }catch(failure: AssertionError){result.put(if(unmerged)"unmergedFailure" else "mergedFailure",failure.javaClass.name)}
        return result
    }
    private fun dialogAxWindowId(w: Window): Int {
        var result=-1;i.runOnMainSync {val own=w.decorView.createAccessibilityNodeInfo();if(own!=null){result=own.windowId;@Suppress("DEPRECATION") own.recycle()}};return result
    }
    private fun nativeFieldAx(label: String,tag: String){
        val started=android.os.SystemClock.uptimeMillis();val deadline=started+5000;val automation=i.uiAutomation;val w=window()
        val evidence=JSONObject().put("kind","exact-native-field-before-guards").put("tag",tag).put("labelHash",easyHash(label.toByteArray()))
            .put("totalBudgetMs",5000).put("phase","root-readiness").put("initialWindow",windowGeometry(w)).put("initialComposeField",composeFieldAx(tag))
        saveProbe(evidence)
        var firstRoot: android.view.accessibility.AccessibilityNodeInfo?=null;var finalField: android.view.accessibility.AccessibilityNodeInfo?=null;var finalLabel: android.view.accessibility.AccessibilityNodeInfo?=null
        var fieldCount=0;var labelCount=0;var associated=false;var expectedId=-1;var finalFieldId: Int?=null;var finalLabelId: Int?=null
        val recent=mutableListOf<JSONObject>();var firstSample: JSONObject?=null;var previous="";var sameSince=0L;var samples=0;var sameCount=0
        val idKey="androidx.compose.ui.semantics.id";val tagKey="androidx.compose.ui.semantics.testTag"
        fun budget(){assertTrue("Native identity query shares original AX5s budget",android.os.SystemClock.uptimeMillis()<=deadline)}
        fun owned(n: android.view.accessibility.AccessibilityNodeInfo,windowId: Int)=n.packageName?.toString()==i.targetContext.packageName&&n.windowId==windowId
        try {
            firstRoot=activeAppRoot(deadline);val remaining=deadline-android.os.SystemClock.uptimeMillis();assertTrue("Original AX5s budget remains",remaining>0)
            ui.waitUntil(remaining){
                val compose=composeFieldAx(tag);val anchor=compose.getJSONObject("unmerged").getInt("semanticsId");val mergedAnchor=compose.getJSONObject("merged").getInt("semanticsId")
                val windowId=dialogAxWindowId(w);val root=firstRoot?:automation.rootInActiveWindow;firstRoot=null
                val fields=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>();val labels=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                var allFresh=true;var visited=0;var idQueries=0;var nativeFields=0;var exactLabels=0;var relation=false
                val sample=JSONObject().put("expectedComposeId",anchor).put("mergedComposeId",mergedAnchor).put("dialogWindowId",windowId).put("rootPresent",root!=null)
                fun readId(n: android.view.accessibility.AccessibilityNodeInfo): Int? {
                    budget();if(!owned(n,windowId)){allFresh=false;return null}
                    if(!n.availableExtraData.contains(idKey)){if(!n.refresh()||!owned(n,windowId))allFresh=false;return null}
                    idQueries++;val ok=n.refreshWithExtraData(idKey,android.os.Bundle())
                    if(!ok||!owned(n,windowId)||!n.extras.containsKey(idKey)){allFresh=false;return null};return n.extras.getInt(idKey)
                }
                var candidate: android.view.accessibility.AccessibilityNodeInfo?=null;var candidateLabel: android.view.accessibility.AccessibilityNodeInfo?=null;var fid: Int?=null;var lid: Int?=null
                try {
                    val exactRoot=root!=null&&root.packageName?.toString()==i.targetContext.packageName&&root.refresh()&&windowId>=0&&owned(root,windowId)
                    sample.put("exactDialogRoot",exactRoot)
                    if(exactRoot){
                        fun visit(n: android.view.accessibility.AccessibilityNodeInfo){
                            budget();check(++visited<=2048);val nativeId=readId(n)
                            if(nativeId==anchor){nativeFields++;if(fields.size<2)fields.add(android.view.accessibility.AccessibilityNodeInfo.obtain(n))}
                            if(n.contentDescription?.toString()==label){exactLabels++;if(labels.size<2)labels.add(android.view.accessibility.AccessibilityNodeInfo.obtain(n))}
                            for(k in 0 until n.childCount){budget();val c=n.getChild(k)?:continue;try {if(owned(c,windowId))visit(c)else allFresh=false}finally{@Suppress("DEPRECATION") c.recycle()}}
                        }
                        visit(root!!);sample.put("root",axNodeFlags(root))
                        val fieldDetails=JSONArray();for(n in fields){val before=axNodeFlags(n);val id=readId(n);fieldDetails.put(JSONObject().put("id",id?:JSONObject.NULL).put("beforeRefresh",before).put("afterRefresh",axNodeFlags(n)))}
                        val labelDetails=JSONArray();for(n in labels){val id=readId(n);labelDetails.put(JSONObject().put("id",id?:JSONObject.NULL).put("flags",axNodeFlags(n)))}
                        sample.put("fieldCount",nativeFields).put("uniqueLabelCount",exactLabels).put("fieldDetails",fieldDetails).put("labelDetails",labelDetails)
                        if(nativeFields==1&&exactLabels==1){
                            val field=fields.single();val description=labels.single();fid=readId(field);lid=readId(description)
                            val available=field.availableExtraData;var tagMatches=false
                            if(available.contains(tagKey)){budget();val ok=field.refreshWithExtraData(tagKey,android.os.Bundle());allFresh=allFresh&&ok;tagMatches=ok&&field.extras.getCharSequence(tagKey)?.toString()==tag}
                            sample.put("fieldTagAdvertised",available.contains(tagKey)).put("fieldTagMatches",tagMatches)
                            if(field==description){relation=fid==anchor&&lid==anchor;sample.put("labelRelation","self")}
                            else {
                                val parent=description.parent;var parentId: Int?=null;var sameParent=false
                                if(parent!=null)try {parentId=readId(parent);sameParent=parent==field;sample.put("descriptionParent",axNodeFlags(parent))}finally{@Suppress("DEPRECATION") parent.recycle()}
                                var directChildMatches=0;for(k in 0 until field.childCount){budget();val child=field.getChild(k)?:continue;try{if(child==description)directChildMatches++}finally{@Suppress("DEPRECATION") child.recycle()}}
                                relation=fid==anchor&&lid?.toLong()==anchor.toLong()+2_000_000_000L&&parentId==anchor&&sameParent&&directChildMatches==1
                                sample.put("labelRelation","generated-description-child").put("descriptionParentId",parentId?:JSONObject.NULL).put("descriptionParentIsExactField",sameParent).put("fieldDirectDescriptionChildCount",directChildMatches)
                            }
                            sample.put("strictAssociation",relation).put("selectedField",axNodeFlags(field)).put("selectedLabel",axNodeFlags(description))
                            allFresh=allFresh&&fid==anchor&&owned(field,windowId)&&owned(description,windowId)&&description.contentDescription?.toString()==label&&tagMatches
                            candidate=android.view.accessibility.AccessibilityNodeInfo.obtain(field);candidateLabel=android.view.accessibility.AccessibilityNodeInfo.obtain(description)
                        }
                    }
                    val after=composeFieldAx(tag);allFresh=allFresh&&after.getJSONObject("unmerged").getInt("semanticsId")==anchor&&mergedAnchor==anchor
                    sample.put("visitedNodes",visited).put("idQueries",idQueries).put("composeFieldBefore",compose).put("composeFieldAfter",after).put("window",windowGeometry(w)).put("connectionPage",nodeGeometry("connection-page"))
                    val now=android.os.SystemClock.uptimeMillis();val signature=sample.toString();samples++
                    if(signature!=previous){previous=signature;sameSince=now;sameCount=1}else sameCount++
                    if(firstSample==null)firstSample=JSONObject(sample.toString());recent.add(JSONObject(sample.toString()).put("sampleUptimeMs",now));if(recent.size>8)recent.removeAt(0)
                    val stable=exactRoot&&allFresh&&sameCount>=2&&now-sameSince>=100&&now<=deadline
                    evidence.put("phase",if(stable)"before-original-assertions" else "exact-identity-sampling").put("samples",samples).put("firstSample",firstSample)
                        .put("recentSamples",JSONArray(recent)).put("recentLimit",8).put("stableForMs",now-sameSince).put("remainingBudgetMs",deadline-now).put("readinessDoesNotRequireFlagsTrue",true)
                    flushProbes()
                    if(stable){fieldCount=nativeFields;labelCount=exactLabels;associated=relation;expectedId=anchor;finalFieldId=fid;finalLabelId=lid;finalField=candidate;candidate=null;finalLabel=candidateLabel;candidateLabel=null}
                    stable
                }catch(failure: Throwable){evidence.put("interruptedSample",sample.put("visitedNodes",visited).put("idQueries",idQueries));flushProbes();throw failure}
                finally {candidate?.let {@Suppress("DEPRECATION") it.recycle()};candidateLabel?.let {@Suppress("DEPRECATION") it.recycle()};fields.forEach {@Suppress("DEPRECATION") it.recycle()};labels.forEach {@Suppress("DEPRECATION") it.recycle()};root?.let {@Suppress("DEPRECATION") it.recycle()}}
            }
            assertEquals("Unique exact native field semantic ID",1,fieldCount);assertEquals("Original unique exact native label",1,labelCount)
            assertEquals(expectedId,finalFieldId);assertTrue("Unique label is strictly linked to independently selected field",associated)
            val field=checkNotNull(finalField);val description=checkNotNull(finalLabel)
            assertTrue("Native field isEnabled: $tag",field.isEnabled);assertTrue("Native field isEditable: $tag",field.isEditable);assertTrue("Native field isVisibleToUser: $tag",field.isVisibleToUser)
            assertEquals("Real native editor class","android.widget.EditText",field.className?.toString());assertTrue("Native SetText action present",field.actionList.any {it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT})
            assertTrue("Native SetSelection action present",field.actionList.any {it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION})
            assertTrue("Unique description enabled/visible",description.isEnabled&&description.isVisibleToUser)
            val b=android.graphics.Rect();field.getBoundsInScreen(b);assertTrue("Native field has positive bounds: $tag",b.width()>0&&b.height()>0)
            val labelBounds=android.graphics.Rect();description.getBoundsInScreen(labelBounds);assertTrue("Unique native label has positive bounds",labelBounds.width()>0&&labelBounds.height()>0)
            val uiField=ui.onNodeWithTag(tag).assertIsDisplayed();val raw=uiField.getUnclippedBoundsInRoot();val bounds=uiField.fetchSemanticsNode().boundsInWindow;val density=ui.density.density
            val visible=android.graphics.Rect();w.decorView.getWindowVisibleDisplayFrame(visible);val origin=IntArray(2);w.decorView.getLocationOnScreen(origin)
            val page=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInWindow;val ime=ViewCompat.getRootWindowInsets(w.decorView)
            val imeTop=if(ime?.isVisible(WindowInsetsCompat.Type.ime())==true)origin[1]+w.decorView.height-ime.getInsets(WindowInsetsCompat.Type.ime()).bottom else origin[1]+w.decorView.height
            val clip=android.graphics.Rect(maxOf(visible.left,origin[0],kotlin.math.ceil(page.left+origin[0]).toInt()),maxOf(visible.top,origin[1],kotlin.math.ceil(page.top+origin[1]).toInt()),minOf(visible.right,origin[0]+w.decorView.width,kotlin.math.floor(page.right+origin[0]).toInt()),minOf(visible.bottom,imeTop,kotlin.math.floor(page.bottom+origin[1]).toInt()))
            val whole=b.left>=clip.left-1&&b.top>=clip.top-1&&b.right<=clip.right+1&&b.bottom<=clip.bottom+1
            evidence.put("nativeFieldBoundsScreen",JSONObject().put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom))
                .put("actualNativeClipScreen",JSONObject().put("left",clip.left).put("top",clip.top).put("right",clip.right).put("bottom",clip.bottom))
                .put("nativeLabelBoundsScreen",JSONObject().put("left",labelBounds.left).put("top",labelBounds.top).put("right",labelBounds.right).put("bottom",labelBounds.bottom))
                .put("nativeFieldWhole",whole).put("selectedNativeId",finalFieldId).put("descriptionNativeId",finalLabelId)
                .put("requiredScrollDeltaPx",if(b.bottom>clip.bottom)b.bottom-clip.bottom else if(b.top<clip.top)b.top-clip.top else 0).put("connectionPage",nodeGeometry("connection-page"))
            flushProbes()
            assertTrue("Whole field visible in Compose clip: $tag",bounds.width>=(raw.right.value-raw.left.value)*density-1.5f&&bounds.height>=(raw.bottom.value-raw.top.value)*density-1.5f)
            assertTrue("Whole native field inside visible display frame: $tag",b.left>=visible.left-1&&b.top>=visible.top-1&&b.right<=visible.right+1&&b.bottom<=visible.bottom+1)
            assertTrue("Whole native control inside actual dialog/Page/IME clip: $tag",whole)
            assertTrue("Whole unique native label inside the same actual clip",labelBounds.left>=clip.left-1&&labelBounds.top>=clip.top-1&&labelBounds.right<=clip.right+1&&labelBounds.bottom<=clip.bottom+1)
            assertTrue("Label bounds belong to exact native field",labelBounds.left>=b.left-1&&labelBounds.top>=b.top-1&&labelBounds.right<=b.right+1&&labelBounds.bottom<=b.bottom+1)
            evidence.put("phase","exact-native-field-label-action-and-full-boundary-passed");flushProbes()
        }catch(failure: Throwable){evidence.put("phase","failed").put("failureClass",failure.javaClass.name).put("elapsedMs",android.os.SystemClock.uptimeMillis()-started);flushProbes();throw failure}
        finally {firstRoot?.let {@Suppress("DEPRECATION") it.recycle()};finalField?.let {@Suppress("DEPRECATION") it.recycle()};finalLabel?.let {@Suppress("DEPRECATION") it.recycle()}}
    }
    private fun <T> menuMainRead(deadline: Long,read: ()->T): T {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        val task=java.util.concurrent.FutureTask<T> {check(android.os.SystemClock.uptimeMillis()<deadline);read()}
        try {
            check(android.os.SystemClock.uptimeMillis()<deadline);check(handler.post(task))
            val remaining=deadline-android.os.SystemClock.uptimeMillis();check(remaining>0)
            val result=task.get(remaining,java.util.concurrent.TimeUnit.MILLISECONDS);check(android.os.SystemClock.uptimeMillis()<=deadline);return result
        }catch(failure: java.util.concurrent.ExecutionException){throw failure.cause?:failure}
        finally {handler.removeCallbacks(task);task.cancel(false)}
    }
    private fun preferencePopupView(deadline: Long): android.view.View {
        check(android.os.Build.VERSION.SDK_INT>=29){"Exact menu Popup inspection requires API29+"}
        return menuMainRead(deadline){
            val roots=android.view.inspector.WindowInspector.getGlobalWindowViews();check(roots.size<=64)
            val own=roots.filter {it.javaClass.name=="androidx.compose.ui.window.PopupLayout"&&it.context.packageName==i.targetContext.packageName}
            assertEquals("Unique exact owned Compose Popup root",1,own.size);val view=own.single();assertSame(view,view.rootView)
            assertTrue("Owned Popup still attached",view.isAttachedToWindow)
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,(view.layoutParams as android.view.WindowManager.LayoutParams).type);view
        }
    }
    private fun popupGeometry(view: android.view.View,deadline: Long): JSONObject =menuMainRead(deadline){
            assertTrue("Owned Popup remains attached",view.isAttachedToWindow);assertSame(view,view.rootView)
            assertEquals("androidx.compose.ui.window.PopupLayout",view.javaClass.name);assertEquals(i.targetContext.packageName,view.context.packageName)
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,(view.layoutParams as android.view.WindowManager.LayoutParams).type)
            val info=view.createAccessibilityNodeInfo();val id=info?.windowId?:-1;info?.let {@Suppress("DEPRECATION") it.recycle()}
            val visible=android.graphics.Rect();view.getWindowVisibleDisplayFrame(visible);val origin=IntArray(2);view.getLocationOnScreen(origin)
            JSONObject().put("windowId",id).put("class",view.javaClass.name).put("targetPackage",view.context.packageName==i.targetContext.packageName)
                .put("type",(view.layoutParams as? android.view.WindowManager.LayoutParams)?.type?:-1).put("hasWindowFocus",view.hasWindowFocus()).put("shown",view.isShown)
                .put("left",origin[0]).put("top",origin[1]).put("right",origin[0]+view.width).put("bottom",origin[1]+view.height)
                .put("visibleFrame",JSONObject().put("left",visible.left).put("top",visible.top).put("right",visible.right).put("bottom",visible.bottom))
    }
    private fun preferenceComposeIdentity(value: String,tag: String): JSONObject {
        val owner=ui.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode();val paths=mutableListOf<List<Int>>();var count=0
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode,path: List<Int>){check(++count<=256);check(path.size<=16)
            if(n.config.getOrNull(SemanticsProperties.Text)?.any {it.text==value}==true)paths.add(path)
            for(child in n.children)walk(child,path+child.id)
        };walk(owner,listOf(owner.id))
        return nodeGeometry(tag).put("ownerId",owner.id).put("composeSelected",owner.config.getOrNull(SemanticsProperties.Selected)?:JSONObject.NULL)
            .put("selectedPresent",owner.config.contains(SemanticsProperties.Selected)).put("rolePresent",owner.config.contains(SemanticsProperties.Role))
            .put("toggleableStatePresent",owner.config.contains(SemanticsProperties.ToggleableState)).put("toggleableState",owner.config.getOrNull(SemanticsProperties.ToggleableState)?.toString()?:JSONObject.NULL)
            .put("role",owner.config.getOrNull(SemanticsProperties.Role)?.toString()?:JSONObject.NULL).put("textMatchCount",paths.size)
            .put("textPaths",JSONArray(paths.map {JSONArray(it)})).put("textHash",easyHash(value.toByteArray()))
    }
    private fun nativeSelectedMenuAx(value: String,tag: String,expectedSelected: Boolean=true,sharedDeadline: Long?=null): P8PreferenceOptionAx {
        val start=android.os.SystemClock.uptimeMillis();val deadline=sharedDeadline?:start+5000
        val evidence=JSONObject().put("kind","exact-selected-menu-before-guards").put("tag",tag).put("valueHash",easyHash(value.toByteArray())).put("totalBudgetMs",5000)
            .put("expectedSelected",expectedSelected).put("sharedStateDeadline",sharedDeadline!=null).put("remainingAtEntryMs",deadline-start)
            .put("popupInspectionMinApi",29).put("sdkInt",android.os.Build.VERSION.SDK_INT).put("phase","before-owned-popup-read")
        var firstRoot: android.view.accessibility.AccessibilityNodeInfo?=null;var finalOwner: android.view.accessibility.AccessibilityNodeInfo?=null;var finalText: android.view.accessibility.AccessibilityNodeInfo?=null
        var ownerCount=0;var textCount=0;var textAssociated=false;var ownerTagMatches=false;var finalPopup: JSONObject?=null;var finalAnchor: JSONObject?=null
        val recent=mutableListOf<JSONObject>();var first: JSONObject?=null;var previous="";var sameSince=0L;var sameCount=0;var samples=0
        val idKey="androidx.compose.ui.semantics.id";val tagKey="androidx.compose.ui.semantics.testTag"
        fun budget(){assertTrue("Selected menu shares original AX5s budget",android.os.SystemClock.uptimeMillis()<=deadline)}
        fun owned(n: android.view.accessibility.AccessibilityNodeInfo,id: Int)=n.packageName?.toString()==i.targetContext.packageName&&n.windowId==id
        try {
            saveProbe(evidence)
            check(deadline>start&&deadline<=start+5000){"Original menu AX5s remaining budget"}
            val popup=preferencePopupView(deadline);evidence.put("initialPopup",popupGeometry(popup,deadline)).put("phase","root-readiness");flushProbes()
            val automation=i.uiAutomation
            firstRoot=activeAppRoot(deadline);val remaining=deadline-android.os.SystemClock.uptimeMillis();assertTrue("Original menu AX5s remains",remaining>0)
            ui.waitUntil(remaining){
                val anchor=preferenceComposeIdentity(value,tag);val ownerId=anchor.getInt("ownerId");val paths=anchor.getJSONArray("textPaths")
                val expectedPath=if(paths.length()==1)(0 until paths.getJSONArray(0).length()).map {paths.getJSONArray(0).getInt(it)}else emptyList()
                val geometry=popupGeometry(popup,deadline);val windowId=geometry.getInt("windowId");val root=firstRoot?:automation.rootInActiveWindow;firstRoot=null
                val owners=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>();val texts=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                var ownerMatches=0;var textMatches=0;var visited=0;var idQueries=0;var fresh=true;var relation=false;var tagMatches=false
                var candidate: android.view.accessibility.AccessibilityNodeInfo?=null;var candidateText: android.view.accessibility.AccessibilityNodeInfo?=null
                val sample=JSONObject().put("compose",anchor).put("popup",geometry).put("rootPresent",root!=null)
                fun id(n: android.view.accessibility.AccessibilityNodeInfo): Int? {
                    budget();if(!owned(n,windowId)){fresh=false;return null}
                    if(!n.availableExtraData.contains(idKey)){if(!n.refresh()||!owned(n,windowId))fresh=false;return null}
                    idQueries++;val ok=n.refreshWithExtraData(idKey,android.os.Bundle());if(!ok||!owned(n,windowId)||!n.extras.containsKey(idKey)){fresh=false;return null};return n.extras.getInt(idKey)
                }
                try {
                    val exactRoot=root!=null&&root.packageName?.toString()==i.targetContext.packageName&&root.refresh()&&windowId>=0&&owned(root,windowId)
                    sample.put("exactPopupRoot",exactRoot)
                    if(exactRoot){
                        fun walk(n: android.view.accessibility.AccessibilityNodeInfo){budget();check(++visited<=2048);val nodeId=id(n)
                            if(nodeId==ownerId){ownerMatches++;if(owners.size<2)owners.add(android.view.accessibility.AccessibilityNodeInfo.obtain(n))}
                            if(n.text?.toString()==value){textMatches++;if(texts.size<2)texts.add(android.view.accessibility.AccessibilityNodeInfo.obtain(n))}
                            for(k in 0 until n.childCount){budget();val c=n.getChild(k)?:continue;try{if(owned(c,windowId))walk(c)else fresh=false}finally{@Suppress("DEPRECATION") c.recycle()}}
                        };walk(root!!);sample.put("root",axNodeFlags(root))
                        val ownerDetails=JSONArray();for(n in owners){val before=axNodeFlags(n);val nativeId=id(n);ownerDetails.put(JSONObject().put("id",nativeId?:JSONObject.NULL).put("beforeRefresh",before).put("afterRefresh",axNodeFlags(n)))}
                        val textDetails=JSONArray();for(n in texts){val nativeId=id(n);textDetails.put(JSONObject().put("id",nativeId?:JSONObject.NULL).put("flags",axNodeFlags(n)))}
                        sample.put("ownerCount",ownerMatches).put("uniqueTextCount",textMatches).put("ownerDetails",ownerDetails).put("textDetails",textDetails)
                        if(ownerMatches==1&&textMatches==1){
                            val owner=owners.single();val text=texts.single();val nativeOwnerId=id(owner);val textId=id(text)
                            if(owner.availableExtraData.contains(tagKey)){budget();val ok=owner.refreshWithExtraData(tagKey,android.os.Bundle());fresh=fresh&&ok;tagMatches=ok&&owner.extras.getCharSequence(tagKey)?.toString()==tag}
                            val actualPath=mutableListOf<Int>();var cursor: android.view.accessibility.AccessibilityNodeInfo?=android.view.accessibility.AccessibilityNodeInfo.obtain(text);var terminalIsOwner=false
                            try {for(k in expectedPath.indices.reversed()){
                                val n=cursor?:break;val currentId=id(n);if(currentId!=null)actualPath.add(currentId)
                                if(k==0){terminalIsOwner=n==owner;break}
                                val parent=n.parent;@Suppress("DEPRECATION") n.recycle();cursor=parent
                            }}finally {cursor?.let {@Suppress("DEPRECATION") it.recycle()}}
                            relation=expectedPath.isNotEmpty()&&nativeOwnerId==ownerId&&textId==expectedPath.last()&&actualPath==expectedPath.reversed()&&terminalIsOwner
                            sample.put("nativeTextToOwnerPath",JSONArray(actualPath)).put("terminalIsIndependentlySelectedOwner",terminalIsOwner).put("strictTextAssociation",relation)
                                .put("nativeOwnerId",nativeOwnerId?:JSONObject.NULL).put("nativeTextId",textId?:JSONObject.NULL).put("ownerTagMatches",tagMatches)
                                .put("selectedOwner",axNodeFlags(owner)).put("uniqueTextLeaf",axNodeFlags(text))
                            candidate=android.view.accessibility.AccessibilityNodeInfo.obtain(owner);candidateText=android.view.accessibility.AccessibilityNodeInfo.obtain(text)
                            fresh=fresh&&owned(owner,windowId)&&owned(text,windowId)&&text.text?.toString()==value&&tagMatches
                        }
                    }
                    val after=preferenceComposeIdentity(value,tag);fresh=fresh&&after.toString()==anchor.toString();sample.put("composeAfter",after).put("visitedNodes",visited).put("idQueries",idQueries)
                    val now=android.os.SystemClock.uptimeMillis();val signature=sample.toString();samples++;if(signature!=previous){previous=signature;sameSince=now;sameCount=1}else sameCount++
                    if(first==null)first=JSONObject(sample.toString());recent.add(JSONObject(sample.toString()).put("sampleUptimeMs",now));if(recent.size>8)recent.removeAt(0)
                    val stable=exactRoot&&fresh&&sameCount>=2&&now-sameSince>=100&&now<=deadline
                    evidence.put("phase",if(stable)"before-native-owner-guards" else "popup-owner-diagnostic").put("firstSample",first).put("recentSamples",JSONArray(recent)).put("samples",samples)
                        .put("remainingBudgetMs",deadline-now).put("stableForMs",now-sameSince).put("nativeSelectedNotAReadinessPredicate",true);flushProbes()
                    if(stable){ownerCount=ownerMatches;textCount=textMatches;textAssociated=relation;ownerTagMatches=tagMatches;finalOwner=candidate;candidate=null;finalText=candidateText;candidateText=null;finalPopup=geometry;finalAnchor=anchor}
                    stable
                }catch(failure: Throwable){evidence.put("interruptedSample",sample.put("visitedNodes",visited).put("idQueries",idQueries));flushProbes();throw failure}
                finally {candidate?.let {@Suppress("DEPRECATION") it.recycle()};candidateText?.let {@Suppress("DEPRECATION") it.recycle()};owners.forEach {@Suppress("DEPRECATION") it.recycle()};texts.forEach {@Suppress("DEPRECATION") it.recycle()};root?.let {@Suppress("DEPRECATION") it.recycle()}}
            }
            assertEquals("Unique exact native menu owner",1,ownerCount);assertEquals("Original unique native menu text",1,textCount);assertTrue("Owner actual public tag matches",ownerTagMatches)
            assertTrue("Text path strictly links to independently selected menu owner",textAssociated)
            val owner=checkNotNull(finalOwner);val text=checkNotNull(finalText);val geometry=checkNotNull(finalPopup)
            assertTrue("Original native text visible",text.isVisibleToUser)
            val ownerBounds=android.graphics.Rect();owner.getBoundsInScreen(ownerBounds);val textBounds=android.graphics.Rect();text.getBoundsInScreen(textBounds);val frame=geometry.getJSONObject("visibleFrame")
            val clip=android.graphics.Rect(maxOf(geometry.getInt("left"),frame.getInt("left")),maxOf(geometry.getInt("top"),frame.getInt("top")),minOf(geometry.getInt("right"),frame.getInt("right")),minOf(geometry.getInt("bottom"),frame.getInt("bottom")))
            fun box(b: android.graphics.Rect)=JSONObject().put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom)
            evidence.put("ownerBoundsScreen",box(ownerBounds)).put("textBoundsScreen",box(textBounds)).put("actualPopupClipScreen",box(clip));flushProbes()
            val anchor=checkNotNull(finalAnchor)
            val selection=P8PreferenceSelectionAx(tag,anchor.getBoolean("rolePresent"),if(anchor.isNull("role"))null else anchor.getString("role"),anchor.getBoolean("toggleableStatePresent"),
                anchor.getBoolean("selectedPresent"),if(anchor.isNull("composeSelected"))null else anchor.getBoolean("composeSelected"),owner.className?.toString(),owner.isCheckable,owner.isChecked,owner.isSelected)
            evidence.put("selectionProjection",JSONObject().put("profile","compose1.12.1-preference-role-null-selected-no-toggle").put("expectedSelected",expectedSelected)
                .put("compose",anchor).put("native",axNodeFlags(owner)).put("matchesExactProfile",p8ExactPreferenceSelection(selection,expectedSelected)).put("projectionNotAReadinessPredicate",true));flushProbes()
            assertTrue("Native choice matches independent expected state under exact role-null preference projection",p8ExactPreferenceSelection(selection,expectedSelected))
            assertTrue("Native menu owner enabled",owner.isEnabled);assertTrue("Native menu owner clickable",owner.isClickable);assertTrue("Native menu owner visible",owner.isVisibleToUser)
            assertTrue("Native owner exposes actual click action",owner.actionList.any {it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK})
            fun whole(b: android.graphics.Rect)=b.width()>0&&b.height()>0&&b.left>=clip.left-1&&b.top>=clip.top-1&&b.right<=clip.right+1&&b.bottom<=clip.bottom+1
            assertTrue("Whole native owner visible",whole(ownerBounds));assertTrue("Whole unique native text visible",whole(textBounds))
            assertTrue("Text bounds inside exact owner",textBounds.left>=ownerBounds.left-1&&textBounds.top>=ownerBounds.top-1&&textBounds.right<=ownerBounds.right+1&&textBounds.bottom<=ownerBounds.bottom+1)
            val n=ui.onNodeWithTag(tag).assertIsDisplayed();val b=n.fetchSemanticsNode().boundsInWindow;val raw=n.getUnclippedBoundsInRoot();val density=ui.density.density
            assertTrue("Whole Compose menu owner visible",b.width>=(raw.right.value-raw.left.value)*density-1.5f&&b.height>=(raw.bottom.value-raw.top.value)*density-1.5f)
            assertEquals("Original exact native option label after guards",value,text.text?.toString())
            evidence.put("phase","native-platform-choice-owner-and-text-all-guards-passed");flushProbes()
            val observed=recent.last();val path=observed.getJSONArray("nativeTextToOwnerPath")
            return P8PreferenceOptionAx(tag.removePrefix("connection-preference-"),observed.getInt("nativeOwnerId"),observed.getInt("nativeTextId"),owner.windowId,
                (0 until path.length()).map {path.getInt(it)},easyHash(checkNotNull(text.text).toString().toByteArray()),selection)
        }catch(failure: Throwable){evidence.put("phase","failed").put("failureClass",failure.javaClass.name).put("elapsedMs",android.os.SystemClock.uptimeMillis()-start);flushProbes();throw failure}
        finally {firstRoot?.let {@Suppress("DEPRECATION") it.recycle()};finalOwner?.let {@Suppress("DEPRECATION") it.recycle()};finalText?.let {@Suppress("DEPRECATION") it.recycle()}}
    }
    private fun nativeTextAx(value: String,readonly: Boolean=false,selected: Boolean=false,expectedMenuTag: String?=null){
        if(selected){nativeSelectedMenuAx(value,requireNotNull(expectedMenuTag));return}
        val root=activeAppRoot();val found=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>();var count=0
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo){check(++count<=2048);if(n.text?.toString()==value)found.add(android.view.accessibility.AccessibilityNodeInfo.obtain(n));for(k in 0 until n.childCount){val c=n.getChild(k)?:continue;try{walk(c)}finally{@Suppress("DEPRECATION") c.recycle()}}}
        try{assertEquals(i.targetContext.packageName,root.packageName?.toString());walk(root);assertEquals(1,found.size);val n=found.single();assertTrue(n.isVisibleToUser);if(readonly){assertFalse(n.isEditable);assertFalse(n.isClickable)};if(selected){assertTrue(n.isSelected);assertTrue(n.isEnabled);assertTrue(n.isClickable)}
            probes.put(JSONObject().put("kind","actual-Android-AX-text").put("textHash",easyHash(value.toByteArray())).put("readonly",readonly).put("selected",n.isSelected).put("editable",n.isEditable).put("class",n.className))
        }finally {found.forEach {@Suppress("DEPRECATION") it.recycle()};@Suppress("DEPRECATION") root.recycle()}
    }
    private fun method(value: String){touch("easywork-connection-method");val item=ui.onNodeWithTag("easywork-method-$value").performScrollTo().assertIsDisplayed();item.assertIsEnabled();touch("easywork-method-$value")}
    private fun preferenceSaved(expected: String,phase: String,deadline: Long){
        val evidence=JSONObject().put("kind","preference-live-and-atomic-saved-read").put("phase",phase).put("expected",expected).put("expectedSource","independent scripted choice")
        saveProbe(evidence)
        try {
            val remaining=deadline-android.os.SystemClock.uptimeMillis();check(remaining>0)
            runBlocking {kotlinx.coroutines.withTimeout(remaining){fixture.store.awaitQueuedWrites()}}
            val live=menuMainRead(deadline){fixture.live().agentConnection?.preference}
            val raw=checkNotNull(fixture.store.read("metadata"));val saved=JSONObject(raw).getJSONArray("clients").objects().single()
            assertEquals("Exact fixture client owns persisted preference",fixture.client.id,saved.getString("id"))
            val persisted=saved.getJSONObject("agentConnection").getString("preference")
            evidence.put("actualLive",live?:JSONObject.NULL).put("actualSaved",persisted).put("metadataSha256",easyHash(raw.toByteArray()))
                .put("freshAtomicRead",true).put("clientIdHash",easyHash(fixture.client.id.toByteArray()));flushProbes()
            assertEquals("Live preference matches independent choice",expected,live);assertEquals("Atomic saved preference matches independent choice",expected,persisted)
            check(android.os.SystemClock.uptimeMillis()<=deadline);evidence.put("phase",phase+"-passed");flushProbes()
        }catch(failure: Throwable){evidence.put("phase",phase+"-failed").put("failureClass",failure.javaClass.name);flushProbes();throw failure}
    }
    private fun preferenceTriplet(expected: String,labels: Map<String,String>,phase: String){
        val start=android.os.SystemClock.uptimeMillis();val deadline=start+5000;val rows=mutableListOf<P8PreferenceOptionAx>()
        val evidence=JSONObject().put("kind","native-exclusive-preference-triplet").put("phase",phase).put("expected",expected)
            .put("expectedSource","independent scripted choice; never Compose selection").put("totalBudgetMs",5000)
        fun rowJson(row: P8PreferenceOptionAx)=JSONObject().put("value",row.value).put("ownerId",row.ownerId).put("textId",row.textId).put("windowId",row.windowId)
            .put("nativeTextToOwnerPath",JSONArray(row.nativeTextToOwnerPath)).put("labelHash",row.labelHash).put("checkable",row.selection.checkable)
            .put("checked",row.selection.checked).put("selected",row.selection.selected).put("nativeClass",row.selection.nativeClass)
            .put("rolePresent",row.selection.rolePresent).put("role",row.selection.role?:JSONObject.NULL).put("toggleableStatePresent",row.selection.toggleableStatePresent)
            .put("composeSelectedDiagnostic",row.selection.composeSelected?:JSONObject.NULL)
        saveProbe(evidence)
        try {
            preferenceSaved(expected,phase+"-before",deadline);val beforeTouches=touches.size
            for((id,label) in labels){rows.add(nativeSelectedMenuAx(label,"connection-preference-$id",id==expected,deadline));evidence.put("completedNativeRows",JSONArray(rows.map {rowJson(it)}));flushProbes()}
            preferenceSaved(expected,phase+"-after",deadline);assertEquals("No input between triplet observations",beforeTouches,touches.size)
            val hashes=labels.mapValues {easyHash(it.value.toByteArray())};val matches=p8ExclusivePreferenceState(expected,hashes,rows)
            evidence.put("nativeCheckedCount",rows.count {it.selection.checked==true}).put("nativeUncheckedCount",rows.count {it.selection.checked==false})
                .put("matchesExactExclusiveState",matches).put("elapsedMs",android.os.SystemClock.uptimeMillis()-start);flushProbes()
            assertTrue("Exact three native options expose one known active and two inactive choices",matches);check(android.os.SystemClock.uptimeMillis()<=deadline)
            evidence.put("phase",phase+"-all-native-and-persisted-guards-passed");flushProbes()
        }catch(failure: Throwable){evidence.put("phase",phase+"-failed").put("failureClass",failure.javaClass.name).put("elapsedMs",android.os.SystemClock.uptimeMillis()-start);flushProbes();throw failure}
    }
    private fun preferenceExclusiveScenario(language: String,dark: Boolean){
        launch(language,dark,false)
        val labels=when(language){
            "zh-Hans"->linkedMapOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继")
            "en"->linkedMapOf("automatic" to "Automatic","lan-only" to "Direct only","relay-only" to "Relay only")
            else->error("Explicit expected preference labels required")
        }
        touch("connection-preference");preferenceTriplet("automatic",labels,"known-initial-automatic")
        for(next in listOf("lan-only","relay-only","automatic")){
            touch("connection-preference-$next");labels.keys.forEach {ui.onNodeWithTag("connection-preference-$it").assertDoesNotExist()}
            preferenceSaved(next,"after-selection-click-$next",android.os.SystemClock.uptimeMillis()+5000)
            touch("cancel-client-form",false);touch("edit-client-${fixture.client.id}");touch("connection-preference")
            preferenceTriplet(next,labels,"after-click-and-form-reopen-$next")
        }
        fixture.noActivation()
    }
    @Test fun chineseLightPortraitUsesProductNavigationAndSavedPreference(){
        launch("zh-Hans",false,false);targets();val raw="  Renamed 中文 😀 %@  "
        val name=ui.onNodeWithTag("easywork-name").performScrollTo();name.assertContentDescriptionEquals(text.tr("名称"));name.performTextReplacement(raw)
        name.assert(SemanticsMatcher("full editable UTF16 retained"){p8ExactInput(raw,it.config.getOrNull(SemanticsProperties.EditableText)?.text,it.config.getOrNull(SemanticsProperties.InputText)?.text)})
        name.performImeAction();nativeFieldAx(text.tr("名称"),"easywork-name");ui.runOnIdle {assertEquals(raw,fixture.live().name)}
        touch("connection-preference");ui.onNodeWithTag("connection-preference-automatic").assertIsSelected();nativeTextAx(text.tr("自动"),selected=true,expectedMenuTag="connection-preference-automatic");touch("connection-preference-relay-only")
        touch("connection-preference");ui.onNodeWithTag("connection-preference-relay-only").assertIsSelected();touch("connection-preference-relay-only")
        ui.runOnIdle {fixture.session.connectionBusy=true};ui.onNodeWithTag("cancel-client-form").assertIsNotEnabled();ui.runOnIdle {fixture.session.connectionBusy=false}
        capture("zh-light-renamed");runBlocking {fixture.store.awaitQueuedWrites()}
        val saved=JSONObject(checkNotNull(fixture.store.read("metadata"))).getJSONArray("clients").objects().single();assertEquals(raw,saved.getString("name"));assertEquals("relay-only",saved.getJSONObject("agentConnection").getString("preference"))
    }
    @Test fun boundaryWhitespaceRenameKeepsLastSavedRawNameForNormalAndImported(){
        launch("zh-Hans",false,false)
        fun savedName(): String {runBlocking {fixture.store.awaitQueuedWrites()};return JSONObject(checkNotNull(fixture.store.read("metadata"))).getJSONArray("clients").objects().single().getString("name")}
        fun input(value: String){val field=ui.onNodeWithTag("easywork-name").performScrollTo();field.performTextReplacement(value)
            field.assert(SemanticsMatcher("exact boundary whitespace remains editable"){p8ExactInput(value,it.config.getOrNull(SemanticsProperties.EditableText)?.text,it.config.getOrNull(SemanticsProperties.InputText)?.text)})
        }
        for(imported in listOf(false,true)) {
            if(imported){ui.runOnIdle {fixture.imported()};touch("edit-client-${fixture.client.id}")}
            val raw=if(imported)" \u00a0 Imported 中文 😀 %@ \u2003 " else " \u00a0 Saved 中文 😀 %@ \u2003 "
            input(raw);ui.runOnIdle {assertEquals(raw,fixture.live().name)};assertEquals(raw,savedName())
            for(blank in listOf("","   ","\u00a0\u2003")) {
                input(blank);ui.runOnIdle {assertEquals(raw,fixture.live().name)};assertEquals(raw,savedName())
            }
            // Reopen through product navigation: a rejected blank draft cannot replace saved input.
            ui.onNodeWithTag("easywork-name").performImeAction();touch("cancel-client-form",false);touch("edit-client-${fixture.client.id}")
            ui.onNodeWithTag("easywork-name").performScrollTo().assert(SemanticsMatcher("reopened raw saved name"){p8ExactInput(raw,it.config.getOrNull(SemanticsProperties.EditableText)?.text,it.config.getOrNull(SemanticsProperties.InputText)?.text)})
            assertEquals(raw,savedName());capture(if(imported)"boundary-imported-raw-name" else "boundary-normal-raw-name");touch("cancel-client-form",false)
        }
    }
    @Test fun englishDarkControlledShortDialogKeepsImportedReadonlyTargets(){
        assertEquals("Use actual OS font2 inside Dialog",2f,i.targetContext.resources.configuration.fontScale,.01f)
        launch("en",true,false,imported=true)
        val w=window();originalWindow=w;originalSize=w.attributes.width to w.attributes.height;val density=ui.activity.resources.displayMetrics.density
        ui.runOnIdle {w.setLayout((320*density).toInt(),(420*density).toInt())};ui.waitUntil(5000){kotlin.math.abs(w.decorView.width-320*density)<2&&kotlin.math.abs(w.decorView.height-420*density)<2};controlledBounds=true
        ui.onNodeWithTag("easywork-migration-pairing-required").performScrollTo().assertTextEquals(text.tr(NativeMigrationEasyWorkGate.MESSAGE));targets()
        method("扫码连接");ui.onNodeWithTag("easywork-pair-lan").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("easywork-qr-description").performScrollTo().assertContentDescriptionEquals(text.tr("或粘贴连接二维码内容"));nativeFieldAx(text.tr("或粘贴连接二维码内容"),"easywork-qr-description")
        capture("en-dark-controlled-short");ui.runOnIdle {assertNull(fixture.live().agentConnection);assertEquals(EasyWorkPresentationFixture.fields(fixture.scope),EasyWorkPresentationFixture.fields(fixture.live().migrationEasyWork!!.target))}
    }
    @Test fun englishDarkActualLandscapeKeepsRealImeAndEmptySubmitVisible(){
        launch("en",true,true);method("通过中继连接")
        val value="https://relay.presentation.invalid/"+"public-long-path/".repeat(10)
        touch("easywork-relay-url");val w=window();val imeDeadline=android.os.SystemClock.uptimeMillis()+8000
        val field=ui.onNodeWithTag("easywork-relay-url");field.performTextReplacement(value);recordGeometry("before-ime-stability",w);settledIme(w,imeDeadline,"before-submit-scroll");field.assertIsFocused().assertContentDescriptionEquals(text.tr("中继服务地址"))
        field.assert(SemanticsMatcher("complete long URI in native edit channels"){p8ExactInput(value,it.config.getOrNull(SemanticsProperties.EditableText)?.text,it.config.getOrNull(SemanticsProperties.InputText)?.text)})
        val button=ui.onNodeWithTag("easywork-pair-relay");recordGeometry("before-submit-scroll",w)
        try {button.performScrollTo()}catch(failure: Throwable){recordGeometry("submit-scroll-failed",w);throw failure}
        settledIme(w,imeDeadline,"after-submit-scroll");recordGeometry("before-disabled-submit-assertions",w)
        try {button.assertIsDisplayed().assertIsNotEnabled()}catch(failure: AssertionError){
            recordGeometry("disabled-submit-assertion-failed",w)
            try {capture("en-dark-landscape-submit-failure")}catch(photo: Exception){failure.addSuppressed(photo)}catch(photo: AssertionError){failure.addSuppressed(photo)}
            throw failure
        }
        val visible=android.graphics.Rect();w.decorView.getWindowVisibleDisplayFrame(visible);val origin=IntArray(2);w.decorView.getLocationOnScreen(origin)
        val bounds=button.fetchSemanticsNode().boundsInWindow;val raw=button.getUnclippedBoundsInRoot();val density=ui.density.density
        assertTrue("Whole disabled submit width",bounds.width>=(raw.right.value-raw.left.value)*density-1.5f)
        assertTrue("Whole disabled submit height",bounds.height>=(raw.bottom.value-raw.top.value)*density-1.5f)
        assertTrue("Disabled submit inside visible frame on every edge",bounds.left>=visible.left-origin[0]-1&&bounds.top>=visible.top-origin[1]-1&&bounds.right<=visible.right-origin[0]+1&&bounds.bottom<=visible.bottom-origin[1]+1)
        probes.put(JSONObject().put("kind","whole-disabled-submit").put("widthPx",bounds.width).put("heightPx",bounds.height)
            .put("unclippedWidthPx",(raw.right.value-raw.left.value)*density).put("unclippedHeightPx",(raw.bottom.value-raw.top.value)*density)
            .put("allVisibleFrameEdgesChecked",true).put("disabledNotTapped",true))
        val imeBottom=checkNotNull(ViewCompat.getRootWindowInsets(w.decorView)).getInsets(WindowInsetsCompat.Type.ime()).bottom;assertTrue(imeBottom>0)
        val imeTop=w.decorView.height-imeBottom
        val pageBounds=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInWindow
        assertTrue("Whole submit above actual IME",bounds.bottom<=imeTop+1)
        assertTrue("Whole submit within form clip",bounds.left>=pageBounds.left-1&&bounds.top>=pageBounds.top-1&&bounds.right<=pageBounds.right+1&&bounds.bottom<=pageBounds.bottom+1)
        saveProbe(JSONObject().put("kind","actual-ime-submit-intersection").put("imeTopWindowPx",imeTop).put("buttonBottomWindowPx",bounds.bottom).put("wholeButtonInsidePage",true))
        val cancel=ui.onNodeWithTag("cancel-client-form").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val cancelBounds=cancel.fetchSemanticsNode().boundsInWindow;val cancelRaw=cancel.getUnclippedBoundsInRoot()
        assertTrue("Whole cancel reachable while IME visible",cancelBounds.height>=(cancelRaw.bottom.value-cancelRaw.top.value)*density-1.5f&&cancelBounds.bottom<=imeTop+1)
        recordGeometry("keyboard-header-cancel-reachable",w)
        probes.put(windowObservation(w).put("kind","actual-IME").put("imeBottomPx",imeBottom).put("emptyCodeSubmitDisabled",true).put("doneDelivery","Compose IME action callback + observed real IME hide; not physical keyboard-button hit"))
        capture("en-dark-landscape-ime");field.performImeAction();ui.waitUntil(8000){ViewCompat.getRootWindowInsets(w.decorView)?.isVisible(WindowInsetsCompat.Type.ime())!=true};field.assertIsNotFocused()
        field.performScrollTo();nativeFieldAx(text.tr("中继服务地址"),"easywork-relay-url");capture("en-dark-landscape-done")
    }
    @Test fun chineseLightPreferenceOptionsStayNativeExclusiveAndPersist(){preferenceExclusiveScenario("zh-Hans",false)}
    @Test fun englishDarkPreferenceOptionsStayNativeExclusiveAndPersist(){preferenceExclusiveScenario("en",true)}
}
