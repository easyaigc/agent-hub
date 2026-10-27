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
class MobileSidebarTransitionUiTest {
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
    private val run get()=(InstrumentationRegistry.getArguments().getString("sidebarTransitionRunId")?:"reading-gesture-local").also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private fun folder()=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P3-sidebar-transition/$run").apply { mkdirs() }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun isolate() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();ui.activity.session.discovery?.stop();ui.activity.session.easyWorkDiscovery?.stop();ui.activity.session.leaveForeground()
            val prior=NativeSessionStorage(context).metadata()
            originalDraftMetadata=listOf("drafts","draftFiles","draftVersions").associateWith { prior?.opt(it)?.toString() }
            session=MobileSession(NativeSessionStorage(context,"P3-sidebar-transition-"+UUID.randomUUID()));session.resetAcceptance()
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
    private fun tap(tag: String) {
        ui.waitForIdle();ui.waitUntil(5_000) { ui.activity.window.decorView.hasWindowFocus() }
        ui.onNodeWithTag(tag).performTouchInput { click(center) }
        if(tag=="open-sidebar")ui.waitUntil(5_000) { ui.onAllNodesWithTag("sidebar-backdrop").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun retained() {
        assertEquals("gesture-thread",session.selectedThread);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
        ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
    }
    private fun shot(name: String) {
        ui.waitForIdle();ui.waitUntil(5_000) { ui.activity.window.decorView.hasWindowFocus() };val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();val window=ui.activity.window
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
    @Test fun mainMenuRetapDuringClosingNeverActivatesDrawerClientAndCanReopenAfterSettlement() {
        val open=ui.onNodeWithTag("open-sidebar").fetchSemanticsNode().boundsInRoot.center
        tap("open-sidebar")
        ui.onNodeWithTag("sidebar-backdrop").assertExists();ui.waitForIdle();shot("drawer-settled-before-close")
        ui.mainClock.autoAdvance=false
        try {
            tap("close-sidebar")
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
            val closing=ui.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
            assertTrue("Retap must occur while closing pixels cover the original main menu",closing.right>open.x)
            shot("drawer-closing-before-main-menu-retap")
            val surface=ui.onNodeWithTag("product-surface").fetchSemanticsNode().boundsInRoot
            ui.onNodeWithTag("product-surface").performTouchInput { click(open-surface.topLeft) }
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame()
        } finally { ui.mainClock.advanceTimeBy(1_000);ui.mainClock.autoAdvance=true }
        ui.waitForIdle()
        val wrongClientSheet=ui.onAllNodesWithTag("sheet-clients").fetchSemanticsNodes().isNotEmpty()
        File(folder(),"retap-pointer-proof.json").writeText(JSONObject().put("clientSheetOpened",wrongClientSheet).put("originalMenuX",open.x).put("originalMenuY",open.y).put("newInputs",0).toString())
        ui.onNodeWithTag("sheet-clients").assertDoesNotExist()
        shot("main-menu-retap-result")
        // iOS disables panel interaction during settlement; an early main tap may be ignored.
        // Whichever timing wins, it must never activate the closing panel's client control.
        if(ui.onAllNodesWithTag("sidebar-backdrop").fetchSemanticsNodes().isEmpty()) {
            tap("open-sidebar")
        }
        ui.onNodeWithTag("sidebar-backdrop").assertExists()
        shot("main-menu-reopened-without-client-sheet")
        assertEquals("gesture-thread",session.selectedThread);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun closingPanelSearchHitDoesNotToggleSearchAndMountedQueryIsRetained() {
        tap("open-sidebar")
        tap("sidebar-search-toggle")
        // Setting the independent search query is setup; the transition itself uses SDK pointers.
        ui.runOnIdle { session.search="Original reading" }
        ui.onNodeWithTag("sidebar-search").assertExists();ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        try {
            tap("close-sidebar")
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame()
            val panel=ui.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
            val toggle=ui.onNodeWithTag("sidebar-search-toggle").fetchSemanticsNode().boundsInRoot.center
            assertTrue("Search hit uses its actual moving panel position",panel.right>=toggle.x)
            val surface=ui.onNodeWithTag("product-surface").fetchSemanticsNode().boundsInRoot
            ui.onNodeWithTag("product-surface").performTouchInput { click(toggle-surface.topLeft) }
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame()
        } finally { ui.mainClock.advanceTimeBy(1_000);ui.mainClock.autoAdvance=true }
        ui.waitForIdle();ui.onNodeWithTag("sidebar-backdrop").assertDoesNotExist()
        tap("open-sidebar")
        ui.onNodeWithTag("sidebar-search").assertExists();assertEquals("Original reading",session.search)
        ui.onNodeWithTag("sheet-clients").assertDoesNotExist();assertEquals(draft,session.text)
        shot("closing-search-hit-keeps-mounted-query")
    }
    @Test fun pointerDragOnClosingPanelCannotReopenItOrActivateOtherControls() {
        tap("open-sidebar");ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        try {
            tap("close-sidebar")
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame()
            val panel=ui.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot
            val surface=ui.onNodeWithTag("product-surface").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.width>surface.width*.5f)
            ui.onNodeWithTag("product-surface").performTouchInput {
                swipe(Offset(8f,height*.4f),Offset(width*.6f,height*.4f),400)
            }
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame()
        } finally { ui.mainClock.advanceTimeBy(1_000);ui.mainClock.autoAdvance=true }
        ui.waitForIdle();retained();ui.onNodeWithTag("sheet-clients").assertDoesNotExist()
        shot("closing-panel-drag-keeps-closed-original-target")
    }
    @Test fun closingBackdropFadesWithPanelAndFinishesAtOriginalMainPixels() {
        fun samples(name: String): List<Int> {
            val bitmap=checkNotNull(android.graphics.BitmapFactory.decodeFile(File(folder(),"$name.png").path))
            return try { (0..20).map { index -> android.graphics.Color.red(bitmap.getPixel(bitmap.width-8,(bitmap.height*(.25f+index*.02f)).toInt())) } }finally { bitmap.recycle() }
        }
        shot("backdrop-before-open");val before=samples("backdrop-before-open")
        tap("open-sidebar");ui.waitForIdle()
        shot("backdrop-open-settled");val opened=samples("backdrop-open-settled")
        val points=before.indices.filter { before[it]>=245 && opened[it]<before[it]-20 }
        assertTrue("Sample owned main-window white pixels visibly dimmed by the open backdrop",points.isNotEmpty())
        lateinit var middle: List<Int>
        ui.mainClock.autoAdvance=false
        try {
            tap("close-sidebar")
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(80)
            assertTrue("Sample during an unfinished visible close",ui.onNodeWithTag("sidebar").fetchSemanticsNode().boundsInRoot.right>0f)
            shot("backdrop-middle-close");middle=samples("backdrop-middle-close")
            File(folder(),"backdrop-pixel-proof.json").writeText(JSONObject().put("eligibleSampleIndices",JSONArray(points)).put("before",JSONArray(before)).put("opened",JSONArray(opened)).put("middle",JSONArray(middle)).put("newInputs",0).toString())
            assertTrue("Closing overlay must fade with the still-visible drawer rather than vanish",points.all { middle[it]>opened[it]+2 && middle[it]<before[it]-2 })
        }finally { ui.mainClock.advanceTimeBy(1_000);ui.mainClock.autoAdvance=true }
        ui.waitForIdle();retained();shot("backdrop-closed-original-pixels")
        val closed=samples("backdrop-closed-original-pixels")
        assertTrue("Settled closed backdrop leaves original main pixels unchanged",points.all { kotlin.math.abs(closed[it]-before[it])<=1 })
    }
}
