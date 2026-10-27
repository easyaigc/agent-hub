package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MobileChatSearchUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val fixtures=mutableListOf<CommandFixture>()
    private val releases=mutableListOf<CountDownLatch>()
    private val target=TargetScope("codex",null,null,"search-ui","workspace","runtime")
    private val longText="original start "+"0123456789 中文🚀 ".repeat(4500)+" LONG_NEEDLE original end"
    private val activity: MainActivity get() { var value: MainActivity?=null;ui.activityRule.scenario.onActivity { value=it };return checkNotNull(value) }
    private lateinit var isolated: MobileSession
    private val session get()=isolated
    private var originalDraftMetadata: Map<String,String?> = emptyMap()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun fresh() {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !activity.session.restoring }
        ui.runOnIdle {
            activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
            val context=InstrumentationRegistry.getInstrumentation().targetContext;val prior=NativeSessionStorage(context).metadata()
            originalDraftMetadata=listOf("drafts","draftFiles","draftVersions").associateWith { prior?.opt(it)?.toString() }
            isolated=MobileSession(NativeSessionStorage(context,"P3-search-ui-"+UUID.randomUUID()));isolated.resetAcceptance()
            activity.setContent { AgentHubApp(isolated) { } }
        }
    }
    @After fun finish() {
        releases.forEach { it.countDown() }
        try { fixtures.forEach { server -> assertTrue("Search UI dispatches GET only",server.requests.all { it.method=="GET" }) } }
        finally {
            if(::isolated.isInitialized)ui.runOnIdle { session.leaveForeground() };fixtures.forEach { it.close() }
            val current=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).metadata()
            for((key,value)in originalDraftMetadata)assertEquals("Synthetic search UI cannot replace default real draft metadata: $key",value,current?.opt(key)?.toString())
        }
    }
    private fun message(index: Int): JSONObject {
        val result=JSONObject().put("id","m$index").put("role","assistant").put("text",if(index==79)"CACHE_NEEDLE latest original" else "original message $index")
        when(index) {
            0 -> result.put("role","tool").put("kind","commandExecution").put("text","tool header").put("detail","TOOL_NEEDLE exact original output")
            1 -> result.put("role","tool").put("kind","commandExecution")
            2 -> result.put("role","user").put("text",longText)
            3 -> result.put("role","tool").put("kind","contextCompaction").put("status","completed").put("text","COMPACTION_NEEDLE original status")
            4 -> result.put("role","user").put("files",JSONArray(listOf("ATTACHMENT_NEEDLE.pdf")))
            5 -> result.put("role","tool").put("kind","toolCall").put("blocks",JSONArray().put(JSONObject().put("type","diff").put("id","diff").put("text","+ DIFF_NEEDLE original")))
        }
        return result
    }
    private fun page(ids: IntRange,cursor: String?=null,thread: String="thread",window: Boolean=false): JSONObject =
        JSONObject().put("hostId",target.hostId).put("threadId",thread).put("readTarget",CodexCommandCodec.target(target))
            .put("messages",if(thread=="other")JSONArray().put(JSONObject().put("id","other").put("role","assistant").put("text","OTHER_TARGET_ONLY")) else JSONArray(ids.map(::message)))
            .put("nextCursor",cursor ?: JSONObject.NULL).also { if(window)it.put("historyWindow",JSONObject().put("version",1).put("kind","latest")).put("historyRevision",1) }
    private fun connect(fail: AtomicBoolean=AtomicBoolean(false),started: CountDownLatch?=null,release: CountDownLatch?=null) {
        if(release!=null)releases+=release
        val workspace=JSONObject().put("hostId",target.hostId).put("name","search UI").put("connectionMode","history-only")
            .put("readTarget",CodexCommandCodec.target(target)).put("capabilities",JSONArray()).put("projects",JSONArray())
            .put("threads",JSONArray(listOf("thread","other").map { JSONObject().put("id",it).put("hostId",target.hostId).put("title",it).put("state","idle") }))
        val server=CommandFixture { request -> when(request.path) {
            "/v1/workspace" -> WireResponse(body=workspace.toString())
            "/v1/history" -> WireResponse(body=page(64..79,"normal-older",if("threadId=other" in request.pathWithQuery)"other" else "thread",true).toString())
            "/v1/threads/search-history" -> {
                started?.countDown();if(release!=null)check(release.await(15,TimeUnit.SECONDS))
                if(fail.get())WireResponse(503) else WireResponse(body=if("cursor=" in request.pathWithQuery)page(0..40).toString() else page(40..79,"search-older").toString())
            }
            else -> WireResponse(404)
        } };fixtures+=server
        main { session.connect("search UI",server.endpoint,"");session.selectThread(session.threads.first { it.id=="thread" }) }
        ui.waitUntil(10_000) { session.messages.size==16&&!session.historyLoading }
        main { session.text="separate unsent draft" }
    }
    private fun search(query: String) {
        ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("chat-search-open").performClick()
        ui.onNodeWithTag("chat-search-query").performTextInput(query)
        ui.onNodeWithTag("chat-search-query").performImeAction()
        ui.waitUntil(5_000) {
            var hidden=false
            Espresso.onView(isRoot()).inRoot(isDialog()).check { view,error ->
                if(error!=null)throw error
                assertEquals(InstrumentationRegistry.getInstrumentation().targetContext.packageName,view.context.packageName)
                hidden=ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())!=true
            }
            hidden
        }
        ui.onNodeWithTag("chat-search-query").assertIsNotFocused()
    }
    private fun result(id: String) { ui.waitUntil(10_000) { ui.onAllNodesWithTag("chat-search-result-$id").fetchSemanticsNodes().isNotEmpty() } }
    private fun located(id: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("chat-search-located-$id").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sheet-chat-search").assertDoesNotExist();ui.onNodeWithTag("chat-search-located-$id").assertIsDisplayed()
        assertEquals(id,session.chatSearchJump?.message?.id);assertEquals("separate unsent draft",session.text)
    }
    private fun shot(name: String) {
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
        val frames=CountDownLatch(1);ui.runOnUiThread { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS));instrumentation.uiAutomation.waitForIdle(300,5_000)
        assertEquals(instrumentation.targetContext.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=InstrumentationRegistry.getArguments().getString("searchRunId") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(instrumentation.targetContext.filesDir,"acceptance/P3-search/$run").apply { mkdirs() }
        var window: android.view.Window?=null
        instrumentation.runOnMainSync { if(activity.window.decorView.hasWindowFocus())window=activity.window }
        if(window==null)Espresso.onView(isRoot()).inRoot(isDialog()).check { view,error ->
            if(error!=null)throw error
            assertEquals(instrumentation.targetContext.packageName,view.context.packageName)
            fun find(v: android.view.View) {
                if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window
                if(v is android.view.ViewGroup)for(index in 0 until v.childCount)find(v.getChildAt(index))
            }
            find(view.rootView);assertNotNull(window);assertTrue(window!!.decorView.rootView===view.rootView)
        }
        val owned=checkNotNull(window);var image: Bitmap?=null
        instrumentation.runOnMainSync { assertTrue(owned.decorView.hasWindowFocus());image=Bitmap.createBitmap(owned.decorView.width,owned.decorView.height,Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val copied=CountDownLatch(1);var result=-1
        try {
            instrumentation.runOnMainSync { android.view.PixelCopy.request(owned,bitmap,{ result=it;copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(copied.await(10,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-focused-window").put("window",if(owned===activity.window)"main" else "dialog").put("package",instrumentation.targetContext.packageName).put("pixelCopyResult",result).put("isolatedStorage",true).toString())
        }finally{bitmap.recycle()}

    }
    @Test fun completeHistoryToolAndCompactionHitsJumpOnlyAfterNativeSheetDismissal() {
        connect();search("TOOL_NEEDLE");result("m0")
        assertEquals(16,session.messages.size);assertNull(session.chatSearchJump);shot("complete-tool-result-before-selection")
        ui.onNodeWithTag("chat-search-result-m0").performClick();located("m0");assertEquals(80,session.messages.size);shot("located-original-grouped-tool")
        search("COMPACTION_NEEDLE");result("m3");ui.onNodeWithTag("chat-search-result-m3").performClick();located("m3");shot("located-original-compaction")
    }
    @Test fun longOriginalUserMessageHasHighlightedSnippetAndCompleteSelectableText() {
        connect();search("LONG_NEEDLE");result("m2");ui.onNodeWithTag("chat-search-result-m2").performClick();located("m2");shot("located-long-user-snippet")
        ui.onNodeWithTag("chat-search-full-message").performClick()
        ui.onNodeWithTag("chat-search-full-text").assertTextEquals(longText)
        assertTrue(longText.length>=64*1024);shot("long-original-expanded")
    }
    @Test fun failedCompleteReadKeepsCachedSearchAndRetryFindsAttachmentsAndDiff() {
        val failed=AtomicBoolean(true);connect(failed);search("CACHE_NEEDLE");result("m79")
        ui.onNodeWithTag("chat-search-retry").assertExists();assertTrue(session.selectedClient.online);assertEquals(16,session.messages.size)
        shot("failure-retains-cached-result")
        failed.set(false);ui.onNodeWithTag("chat-search-retry").performClick()
        ui.onNodeWithTag("chat-search-query").performTextReplacement("ATTACHMENT_NEEDLE");ui.onNodeWithTag("chat-search-query").performImeAction()
        ui.onNodeWithTag("chat-search-result-m79").assertDoesNotExist();result("m4")
        ui.onNodeWithTag("chat-search-result-m79").assertDoesNotExist()
        ui.onNodeWithTag("chat-search-result-m4").performClick();located("m4");shot("located-original-attachment-name")
        search("DIFF_NEEDLE");result("m5");ui.onNodeWithTag("chat-search-result-m5").performClick();located("m5");shot("located-original-diff-block")
        search("NO_SUCH_QUERY");ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-search-empty").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("chat-search-clear").performClick();ui.onNodeWithTag("chat-search-empty").assertDoesNotExist()
    }
    @Test fun closingSearchThenChangingThreadRejectsLateResultsAndKeepsNewDraft() {
        val started=CountDownLatch(1);val release=CountDownLatch(1);connect(started=started,release=release);search("TOOL_NEEDLE")
        assertTrue(started.await(3,TimeUnit.SECONDS));ui.onNodeWithTag("chat-search-close").performClick()
        ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("recent-thread-other").performClick()
        main { session.text="new target draft" };release.countDown()
        ui.waitUntil(5_000) { session.selectedThread=="other"&&session.messages.singleOrNull()?.id=="other" }
        ui.onNodeWithTag("sheet-chat-search").assertDoesNotExist();assertNull(session.chatSearchJump);assertEquals("new target draft",session.text)
        ui.onNodeWithTag("chat-search-located-m0").assertDoesNotExist();shot("closed-search-new-thread-keeps-draft")
    }
    @Test fun nativeDialogBackCallbackWithoutSelectingPreservesVisibleHistoryAndDraft() {
        connect()
        fun latestVisible()=runCatching { ui.onNodeWithTag("markdown-m79").assertIsDisplayed();true }.getOrDefault(false)
        ui.waitUntil(5_000) { latestVisible()||ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().isNotEmpty() }
        if(!latestVisible())ui.onNodeWithTag("scroll-latest").performClick()
        ui.waitUntil(5_000) { latestVisible() }
        search("CACHE_NEEDLE");result("m79")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assertEquals(instrumentation.targetContext.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        // Exercise the owned Dialog's native route without global input injection.
        // Physical system-key injection is a separate device acceptance item.
        Espresso.onView(isRoot()).inRoot(isDialog()).check { view,error ->
            if(error!=null)throw error
            assertEquals(instrumentation.targetContext.packageName,view.context.packageName)
            val owner=view.findViewTreeOnBackPressedDispatcherOwner()
            assertNotNull(owner);checkNotNull(owner).onBackPressedDispatcher.onBackPressed()
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("sheet-chat-search").fetchSemanticsNodes().isEmpty() }
        assertEquals(16,session.messages.size);assertNull(session.chatSearchJump);assertEquals("separate unsent draft",session.text)
        ui.onNodeWithTag("markdown-m79").assertIsDisplayed();shot("native-dialog-back-callback-keeps-original-window")
    }
}
