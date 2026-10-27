package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real SDK pointer arbitration on isolated production UI; never submits model input. */
class MobileReadingGestureUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var session: MobileSession
    private lateinit var server: CommandFixture
    private var originalDraftMetadata: Map<String,String?> = emptyMap()
    private val target=TargetScope("codex",null,null,"reading-gesture-host","reading-workspace","reading-runtime")
    private val draft="Independent unsent gesture draft"
    private val code="CODE_BEGIN "+"0123456789_abcdefghij ".repeat(18)+" CODE_END\nSECOND_CODE_LINE"
    private val plain="SELECTABLE_SENTINEL original words remain independently selectable."
    private val run get()=(InstrumentationRegistry.getArguments().getString("readingGestureRunId")?:"reading-gesture-local").also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private fun folder()=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P3-reading-gestures/$run").apply { mkdirs() }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun isolate() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();ui.activity.session.discovery?.stop();ui.activity.session.easyWorkDiscovery?.stop();ui.activity.session.leaveForeground()
            val prior=NativeSessionStorage(context).metadata()
            originalDraftMetadata=listOf("drafts","draftFiles","draftVersions").associateWith { prior?.opt(it)?.toString() }
            session=MobileSession(NativeSessionStorage(context,"P3-reading-gesture-"+UUID.randomUUID()));session.resetAcceptance()
            ui.activity.setContent { AgentHubApp(session) { } }
        }
        val workspace=JSONObject().put("hostId",target.hostId).put("name","reading gesture fixture").put("connectionMode","history-only")
            .put("readTarget",CodexCommandCodec.target(target)).put("capabilities",JSONArray()).put("projects",JSONArray())
            .put("threads",JSONArray().put(JSONObject().put("id","gesture-thread").put("hostId",target.hostId).put("title","Reading gestures").put("state","idle")))
        val messages=JSONArray((0..17).map { index -> JSONObject().put("id",when(index) { 14->"code";15->"selectable";else->"row-$index" }).put("role","assistant")
            .put("text",when(index) { 14->"```kotlin\n$code\n```";15->plain;else->"Original reading row $index\nVertical message list content $index" }) })
        server=CommandFixture { request -> when(request.path) {
            "/v1/workspace"->WireResponse(body=workspace.toString())
            "/v1/history"->WireResponse(body=JSONObject().put("hostId",target.hostId).put("threadId","gesture-thread").put("readTarget",CodexCommandCodec.target(target)).put("messages",messages).put("nextCursor",JSONObject.NULL).toString())
            else->WireResponse(404)
        } }
        main { session.connect("reading gesture fixture",server.endpoint,"") }
        ui.waitUntil(15_000) { session.hasClient&&!session.loading }
        main { session.selectThread(session.threads.single()) }
        ui.waitUntil(15_000) { session.messages.size==18&&!session.historyLoading }
        main { session.text=draft }
    }
    @After fun preserveRealDraftAndNoInput() {
        try {
            if(::session.isInitialized)main { session.leaveForeground() }
            if(::server.isInitialized)assertTrue("Reading pointers never submit input or management",server.requests.all { it.method=="GET" })
        } finally {
            if(::server.isInitialized)server.close()
            val current=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).metadata()
            for((key,value)in originalDraftMetadata)assertEquals("Isolated reading gestures must preserve default $key",value,current?.opt(key)?.toString())
        }
    }
    private fun retained() {
        assertEquals("gesture-thread",session.selectedThread);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
    }
    private fun scrollTo(tag: String) { ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).assertIsDisplayed() }
    private fun horizontal()=ui.onNode(SemanticsMatcher("Code horizontal scroll range") { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) } and hasAnyAncestor(hasTestTag("markdown-code-0-code")),useUnmergedTree=true)
    private fun horizontalValue()=horizontal().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
    private fun selectionHandles(endOnly: Boolean=false)=ui.onAllNodes(SemanticsMatcher("Visible owned native selection handle") { node ->
        node.config.any { entry -> entry.key.name=="SelectionHandleInfo"&&entry.value.toString().contains("visible=true")&&(!endOnly||entry.value.toString().contains("SelectionEnd")) }
    },useUnmergedTree=true)
    private fun selectionInfo()=selectionHandles(endOnly=true).fetchSemanticsNodes().single().config.joinToString { it.key.name+"="+it.value }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val window=ui.activity.window
        val frames=CountDownLatch(1);i.runOnMainSync { window.decorView.postOnAnimation { window.decorView.postOnAnimation { frames.countDown() } } };assertTrue(frames.await(5,TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());assertEquals(i.targetContext.packageName,window.decorView.context.packageName);bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val copied=CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,image,{ result=it;copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(copied.await(10,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-main-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).put("isolatedStorage",true).toString())
        }finally{image.recycle()}
    }
    @Test fun codeHorizontalScrollAtBothInteriorAndLeftEdgeDoesNotOpenDrawer() {
        scrollTo("message-code");ui.waitUntil(10_000) { ui.onAllNodesWithTag("markdown-code-0-code").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0f,horizontalValue(),1f);shot("code-before-horizontal-scroll")
        horizontal().performTouchInput { swipe(Offset(width*.85f,height*.35f),Offset(width*.2f,height*.35f),400) }
        ui.waitUntil(10_000) { horizontalValue()>80f };retained();val before=horizontalValue()
        // The code viewport begins inside the product's edge-drawer band.
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val edgeDownX=horizontal().fetchSemanticsNode().boundsInRoot.left+2f
        val productLeft=ui.onNodeWithTag("product-surface").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Exercise code scroll from the actual product edge band",edgeDownX>=productLeft&&edgeDownX-productLeft<24f*density)
        horizontal().performTouchInput { swipe(Offset(2f,height*.35f),Offset(width*.82f,height*.35f),400) }
        ui.waitUntil(10_000) { horizontalValue()<before };retained();shot("code-edge-return-keeps-original-target")
        File(folder(),"code-pointer-proof.json").writeText(JSONObject().put("scrolledBeforeReturn",before).put("afterReturn",horizontalValue()).put("edgeDownX",edgeDownX).put("productLeft",productLeft).put("edgeBandPx",24f*density).put("drawerOpened",false).put("newInputs",0).toString())
    }
    @Test fun actualLongPressAndSelectionHandleDragDoNotOpenDrawerOrChangeDraft() {
        scrollTo("message-selectable");ui.waitUntil(10_000) { ui.onAllNodesWithTag("markdown-selectable-0").fetchSemanticsNodes().isNotEmpty() }
        val layouts=mutableListOf<TextLayoutResult>();val text=ui.onNodeWithTag("markdown-selectable-0",useUnmergedTree=true)
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) };assertEquals(plain,layouts.single().layoutInput.text.text)
        val point=layouts.single().getBoundingBox(3).center
        text.performTouchInput { longClick(point) }
        ui.waitUntil(10_000) { selectionHandles().fetchSemanticsNodes().size==2 };retained();shot("plain-long-press-has-owned-selection")
        val before=selectionInfo();val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        selectionHandles(endOnly=true)[0].performTouchInput { swipe(center,center+Offset(55f*density,0f),400) }
        ui.waitUntil(10_000) { selectionHandles(endOnly=true).fetchSemanticsNodes().size==1&&selectionInfo()!=before };retained();shot("selection-end-drag-keeps-draft")
        File(folder(),"selection-pointer-proof.json").writeText(JSONObject().put("handles",2).put("endHandleChanged",true).put("drawerOpened",false).put("clipboardTouched",false).put("newInputs",0).toString())
    }
    @Test fun verticalListPointerScrollKeepsOriginalHistoryAndDoesNotOpenDrawer() {
        scrollTo("message-row-17")
        fun position()=ui.onNodeWithTag("message-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before=position();assertTrue(before>0f);val reads=server.requests.count { it.path=="/v1/history" };shot("list-before-vertical-scroll")
        ui.onNodeWithTag("message-list").performTouchInput { swipe(Offset(width*.65f,height*.3f),Offset(width*.65f,height*.82f),450) }
        ui.waitUntil(10_000) { position()<before };retained();assertEquals(18,session.messages.size);assertEquals(reads,server.requests.count { it.path=="/v1/history" });shot("list-vertical-scroll-keeps-draft")
        File(folder(),"vertical-pointer-proof.json").writeText(JSONObject().put("before",before).put("after",position()).put("extraHistoryReads",0).put("newInputs",0).toString())
    }
}
