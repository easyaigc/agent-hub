package com.agenthub.android

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@RunWith(AndroidJUnit4::class)
class MobileReadTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val arguments get()=InstrumentationRegistry.getArguments()
    private val endpoint get()=arguments.getString("readFixture") ?: error("A3 read fixture is required")
    private val activity: MainActivity get() {
        var current: MainActivity?=null
        ui.activityRule.scenario.onActivity { current=it }
        return checkNotNull(current)
    }
    private val session get()=activity.session
    @Before fun reset() {
        ui.runOnIdle { session.resetAcceptance() }
        // A preceding orientation case can replace the Activity before its new
        // Compose root has been attached. Require the real root before actions.
        ui.waitUntil(10_000) { runCatching { ui.onRoot().fetchSemanticsNode().size.width>0 }.getOrDefault(false) }
    }
    private fun main(block: suspend () -> Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun connect() { main { session.connect("合成历史电脑",endpoint,"") }; ui.waitUntil(10_000) { session.hasClient && !session.loading }; hideKeyboard() }
    private fun demo() { ui.onNodeWithTag("chat-menu").performClick(); ui.onNodeWithTag("open-settings").performClick(); ui.onNodeWithTag("settings-demo-toggle").performClick(); ui.onNodeWithTag("settings-done").performClick() }
    private fun shot(name: String) {
        ui.waitForIdle(); val i=InstrumentationRegistry.getInstrumentation(); i.waitForIdleSync(); i.uiAutomation.waitForIdle(200,5_000)
        awaitWindowFrames()
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A3").apply { mkdirs() }
        val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    // Compose semantics can settle before WindowManager presents a replacement
    // Activity or removes a Dialog surface, especially on Android 8.0.
    private fun awaitWindowFrames() {
        val drawn=CountDownLatch(1)
        ui.runOnUiThread { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { drawn.countDown() } } }
        assertTrue("Native window must present frames before capture",drawn.await(5,TimeUnit.SECONDS))
    }
    private fun openThread() { ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil(10_000) { session.messages.isNotEmpty() && !session.historyLoading } }
    private fun hideKeyboard() {
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(10_000) { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())!=true }
        ui.waitForIdle()
    }
    private fun manualMethod() { ui.onNodeWithTag("codex-connection-method").performClick(); ui.onNodeWithTag("codex-method-手动连接").performClick() }

    @Test fun drawerSearchFoldAndStatusIndicators() {
        demo(); ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("recent-thread-demo-thread-4").assertIsDisplayed()
        ui.onNodeWithTag("recent-thread-demo-thread-5").assertDoesNotExist()
        ui.onNodeWithTag("unread-demo-thread-3",useUnmergedTree=true).assertExists()
        shot("drawer-five-light")
        ui.onNodeWithTag("sidebar-more-recent").performClick()
        ui.onNodeWithTag("recent-thread-demo-thread-5").assertExists()
        ui.onNodeWithTag("sidebar-search-toggle").performClick()
        ui.onNodeWithTag("sidebar-search").performTextInput("配对")
        ui.onNodeWithTag("recent-thread-demo-thread-1").assertExists()
        ui.onNodeWithTag("recent-thread-demo-chat").assertDoesNotExist()
        shot("drawer-search-light")
        ui.onNodeWithTag("sidebar-search-clear").performClick()
        ui.onNodeWithTag("sidebar-search-toggle").performClick()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("project-mobile"))
        ui.onNodeWithTag("project-mobile").performClick()
        ui.onNodeWithTag("project-thread-mobile-demo-chat").assertExists()
        shot("drawer-project-expanded")
        ui.onNodeWithTag("project-mobile").performClick()
        ui.onNodeWithTag("project-thread-mobile-demo-chat").assertDoesNotExist()
    }
    @Test fun drawerMotionAndBackRetainDraft() {
        demo(); ui.onNodeWithTag("message-input").performTextInput("抽屉后保留的草稿🙂")
        hideKeyboard()
        ui.onNodeWithTag("product-surface").performTouchInput { swipe(Offset(3f,height*.35f),Offset(width*.8f,height*.35f),500) }
        shot("drawer-after-edge-swipe")
        ui.onNodeWithTag("sidebar-list").assertIsDisplayed()
        ui.onNodeWithTag("sidebar").performTouchInput { swipe(Offset(width*.8f,height*.3f),Offset(3f,height*.3f),500) }
        ui.onNodeWithTag("message-input").assertIsDisplayed().assertTextEquals("抽屉后保留的草稿🙂")
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-backdrop").assertIsDisplayed()
        shot("drawer-before-system-back")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
        ui.onNodeWithTag("message-input").assertIsDisplayed().assertTextEquals("抽屉后保留的草稿🙂")
    }
    @Test fun hiddenDrawerStopsIndeterminateIndicatorsAndReopeningResumesThem() {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=fixtureWorkspace(state="running")
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=fixtureHistory(threadId)
        }
        ui.runOnIdle { session.resetAcceptance(reader) }
        main { session.connect("合成运行目录","https://fixture.invalid","") }
        ui.waitUntil(10_000) { session.hasClient && !session.loading }
        // Keep the component mounted and measurable. A translated parent's
        // accessibility visibility must not hide an animation leak from this check.
        val active=androidx.compose.runtime.mutableStateOf(true)
        ui.runOnUiThread { activity.setContent {
            androidx.compose.material3.MaterialTheme {
                Sidebar(session,close={},clients={},newChat={},settings={},active=active.value)
            }
        } }
        ui.waitForIdle()
        val indeterminate=SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate) and hasAnyAncestor(hasTestTag("sidebar"))
        fun count()=ui.onAllNodes(indeterminate,useUnmergedTree=true).fetchSemanticsNodes().size
        val open=count();assertTrue("Visible running rows must animate",open>0)
        ui.runOnIdle { active.value=false };ui.waitForIdle()
        val hidden=count()
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"acceptance/A3").apply { mkdirs() }
        File(folder,"hidden-drawer-animations.json").writeText(JSONObject().put("openIndeterminate",open).put("hiddenIndeterminate",hidden).toString())
        assertEquals("A mounted hidden drawer must not retain infinite progress animations",0,hidden)
        ui.runOnIdle { active.value=true };ui.waitForIdle();assertEquals(open,count())
        ui.onNodeWithTag("recent-thread-old",useUnmergedTree=true).assertExists()
        shot("drawer-animation-resumes-after-reopen")
    }
    @Test fun sidebarFoldAndProjectStateStayWithClientAcrossRotation() {
        demo(); ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-more-recent").performClick()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("project-mobile"))
        ui.onNodeWithTag("project-mobile").performClick()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("sidebar-projects-toggle"))
        ui.onNodeWithTag("sidebar-projects-toggle").performClick()
        ui.onNodeWithTag("sidebar-backdrop").performClick()
        ui.onNodeWithTag("client-selector").performClick(); ui.onNodeWithTag("client-demo-pc").performClick()
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("project-web").assertExists()
        ui.onNodeWithTag("sidebar-backdrop").performClick()
        ui.onNodeWithTag("client-selector").performClick(); ui.onNodeWithTag("client-demo-mac").performClick()
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("recent-thread-demo-thread-5"))
        ui.onNodeWithTag("recent-thread-demo-thread-5").assertIsDisplayed()
        ui.onNodeWithTag("project-mobile").assertDoesNotExist()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("sidebar-projects-toggle"))
        ui.onNodeWithTag("sidebar-projects-toggle").performClick()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("project-thread-mobile-demo-chat"))
        ui.onNodeWithTag("project-thread-mobile-demo-chat").assertIsDisplayed()
        val original=activity
        ui.runOnIdle { activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10_000) { activity!==original && activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        awaitWindowFrames()
        ui.runOnIdle { activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10_000) { activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && activity.window.decorView.height>activity.window.decorView.width }
        awaitWindowFrames()
        ui.onNodeWithTag("open-sidebar").performClick()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("recent-thread-demo-thread-5"))
        ui.onNodeWithTag("recent-thread-demo-thread-5").assertIsDisplayed()
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("project-thread-mobile-demo-chat"))
        ui.onNodeWithTag("project-thread-mobile-demo-chat").assertIsDisplayed(); shot("drawer-client-state-restored")
    }
    @Test fun connectionFormCancelValidateConnectEditAndRemove() {
        ui.onNodeWithTag("client-selector").performClick(); ui.onNodeWithTag("add-client").performClick()
        manualMethod()
        ui.onNodeWithTag("client-name").performTextReplacement("取消的连接")
        ui.onNodeWithTag("cancel-client-form").performClick(); assertFalse(session.hasClient)
        ui.onNodeWithTag("add-client").performClick()
        manualMethod()
        ui.onNodeWithTag("client-address").performTextReplacement("http://public.invalid")
        ui.onNodeWithTag("client-address").performImeAction();hideKeyboard()
        ui.onNodeWithTag("connect-client").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("client-form-error").fetchSemanticsNodes().size==1 }
        ui.onNodeWithTag("client-form-error").assertExists()
        assertFalse(session.hasClient)
        ui.onNodeWithTag("client-address").performTextReplacement(endpoint)
        ui.onNodeWithTag("client-name").performTextReplacement("合成历史电脑")
        // The form is a Dialog: dispatch Done to its focused input rather than
        // asking the underlying Activity window to hide another window's IME.
        ui.onNodeWithTag("client-name").performImeAction()
        ui.waitUntil(10_000) { !session.connectionBusy }
        ui.onNodeWithTag("connect-client").performScrollTo().performClick()
        // This is an actual HTTP connection; allow the same bounded wait used by
        // native connection acceptance instead of a short window under build load.
        try { ui.waitUntil(45_000) { session.hasClient } }
        catch(failure: Exception) { shot("client-form-connection-failure"); throw failure }
        ui.onNodeWithTag("client-fixture-android-host").assertExists()
        shot("clients-connected")
        ui.onNodeWithTag("edit-client-fixture-android-host").performClick()
        ui.onNodeWithTag("client-name").performTextReplacement("修改后的电脑")
        ui.onNodeWithTag("save-client").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.selectedClient.name=="修改后的电脑" }
        ui.onNodeWithTag("remove-client-fixture-android-host").performClick()
        shot("clients-remove-confirmation")
        ui.onNodeWithTag("confirm-remove-client").performClick()
        assertFalse(session.hasClient)
    }
    @Test fun readonlyWorkspacePagesKeepMembershipAndRefreshRemovesOldEntries() {
        connect(); assertEquals(3,session.projects.size); assertEquals(7,session.threads.size)
        assertTrue(session.threads.first().belongs(session.projects.first()))
        ui.runOnIdle { session.refresh(more=true) }; ui.waitUntil(10_000) { session.projects.size==6 && !session.loading }
        assertEquals(10,session.threads.size)
        assertNull(session.workspace!!.nextThreadCursor)
        ui.runOnIdle { session.refresh() }; ui.waitUntil(10_000) { session.projects.size==3 && !session.loading }
        assertEquals(7,session.threads.size)
        ui.onNodeWithTag("open-sidebar").performClick()
        shot("readonly-sidebar-page")
    }
    @Test fun readonlyHistoryAndEarlierPageKeepDraftAndDisableWrites() {
        connect(); openThread()
        ui.onNodeWithTag("message-input").performTextInput("仅保存在手机的草稿")
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
        assertEquals("history-only",session.selectedClient.mode)
        ui.onNodeWithTag("readonly-notice").assertExists()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-more"))
        val anchorFolder=File(activity.getExternalFilesDir(null),"acceptance/A3").apply { mkdirs() }
        File(anchorFolder,"anchor-before.txt").writeText(session.timeline.rows.mapIndexed { i,row -> "$i:${row.kind}:${row.id}:${row.indices}" }.joinToString("\n")+"\n"+ui.onRoot().printToString())
        ui.onNodeWithTag("history-more").performClick()
        ui.waitUntil(10_000) { session.messages.firstOrNull()?.id=="older-user" && !session.historyLoading }
        assertNull(session.historyCursor); assertEquals(7,session.messages.size)
        try {
            ui.waitUntil(10_000) { runCatching { ui.onNodeWithTag("message-read-user").assertIsDisplayed() }.isSuccess }
        } finally {
            shot("readonly-anchor-after-page")
            File(activity.getExternalFilesDir(null),"acceptance/A3/anchor-semantics.txt").writeText(ui.onRoot().printToString())
            File(anchorFolder,"anchor-rows.txt").writeText(session.timeline.rows.mapIndexed { i,row -> "$i:${row.kind}:${row.id}:${row.indices}" }.joinToString("\n"))
        }
        ui.onNodeWithTag("message-input").assertTextEquals("仅保存在手机的草稿")
        shot("readonly-earlier-history")
    }
    @Test fun markdownNativeCodeTableAndReplyCopy() {
        connect(); openThread()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("markdown-read-assistant"))
        ui.waitUntil(10_000) { ui.onAllNodesWithText("原生 Markdown").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("markdown-read-assistant-4-table"))
        ui.onNodeWithTag("markdown-read-assistant-4-table").assertIsDisplayed()
        shot("markdown-table-light")
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("markdown-read-assistant-5-copy-code"))
        ui.onNodeWithTag("markdown-read-assistant-5-copy-code").performClick()
        val clipboard=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().startsWith("val answer ="))
        shot("markdown-code-copy-light")
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("copy-reply-read-assistant"))
        ui.onNodeWithTag("copy-reply-read-assistant").performClick()
        assertEquals(session.messages.last().text,clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
    @Test fun toolsAndDiffExpandIndependentlyAndCompactionStaysSeparate() {
        connect(); openThread()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-group-toggle-read-command"))
        ui.onNodeWithTag("tool-group-toggle-read-command").performClick()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-toggle-read-command"))
        ui.onNodeWithTag("tool-toggle-read-command").assertIsDisplayed()
        ui.onNodeWithTag("tool-toggle-read-command").performClick()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-detail-read-command"))
        ui.onNodeWithTag("tool-detail-read-command").assertIsDisplayed()
        ui.onNodeWithTag("tool-read-diff-code").assertDoesNotExist()
        shot("tool-detail-light")
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-toggle-read-diff"))
        ui.onNodeWithTag("tool-toggle-read-diff").performClick()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-read-diff-code"))
        ui.onNodeWithTag("tool-read-diff-code").assertIsDisplayed()
        shot("diff-expanded-light")
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-toggle-read-command"))
        ui.onNodeWithTag("tool-toggle-read-command").performClick()
        ui.onNodeWithTag("tool-detail-read-command").assertDoesNotExist()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("tool-read-diff-code"))
        ui.onNodeWithTag("tool-read-diff-code").assertExists()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("compaction-read-compaction"))
        ui.onNodeWithTag("compaction-read-compaction").assertIsDisplayed()
    }
    @Test fun localHistoryWindowExpandsWithoutMutatingOriginalMessages() {
        demo(); val messages=(0 until 125).map { MobileMessage("window-$it",if(it%2==0) "user" else "assistant","第 $it 条合成消息","completed",runId="run-${it/2}") }
        ui.runOnIdle { session.installAcceptanceHistory(messages) }
        assertTrue(session.timeline.hasEarlierMessages)
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("history-local-more").performClick(); assertEquals(125,session.messages.size)
        assertTrue(session.visibleMessageLimit==80)
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"))
        ui.onNodeWithTag("history-local-more").performClick(); assertFalse(session.timeline.hasEarlierMessages)
        assertEquals(messages,session.messages)
    }
    @Test fun historyPullRequiresTopThresholdAndRelease() {
        demo()
        // This case does not recreate or rotate the Activity. Reuse its verified
        // session for gesture-state assertions, without re-entering the platform
        // idle wait while Compose settles injected motion.
        val session=this.session
        val original=(0 until 125).map { MobileMessage("pull-$it",if(it%2==0) "user" else "assistant","第 $it 条合成消息","completed",runId="pull-run-${it/2}") }
        ui.runOnIdle { session.installAcceptanceHistory(original) }
        val list=ui.onNodeWithTag("message-list")
        list.performScrollToNode(hasTestTag("message-pull-115"))
        list.performTouchInput { swipe(Offset(width*.5f,height*.25f),Offset(width*.5f,height*.7f),400) }
        assertEquals(20,session.visibleMessageLimit)
        list.performScrollToIndex(0)
        list.assert(SemanticsMatcher("History is at the actual top") { it.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()==0f })
        list.performTouchInput { swipe(Offset(width*.5f,10f),Offset(width*.5f,35f),400) }
        assertEquals(20,session.visibleMessageLimit)
        shot("history-pull-before")
        list.performTouchInput { down(Offset(width*.5f,80f)); moveBy(Offset(0f,height*.1f),16); moveBy(Offset(0f,height*.3f),180) }
        assertEquals("Holding a pull must not load",20,session.visibleMessageLimit)
        list.performTouchInput { up() }
        ui.waitUntil(10_000) { session.visibleMessageLimit==80 }
        assertEquals(original,session.messages)
        ui.onNodeWithTag("message-pull-105").assertIsDisplayed()
        shot("history-pull-after-anchor")
    }
    @Test fun latestReadPreservesEarlierPrefixAndExhaustedCursorUntilDifferentTarget() {
        connect(); openThread()
        main { session.loadHistory(more=true)?.join() }
        assertEquals("older-user",session.messages.first().id); assertNull(session.historyCursor)
        main { session.loadHistory()?.join() }
        assertEquals("older-user",session.messages.first().id); assertEquals(7,session.messages.size); assertNull(session.historyCursor)
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertEquals("Same-chat navigation keeps earlier pages", "older-user",session.messages.first().id)
        assertNull(session.historyCursor)
        ui.runOnIdle { session.selectThread(session.threads[1]) }
        ui.waitUntil(10_000) { !session.historyLoading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading }
        assertEquals("read-user",session.messages.first().id); assertEquals("earlier-page",session.historyCursor)
    }
    @Test fun fullRuntimeHistoryRemainsAuthoritativeAfterPaging() {
        var full=false
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=fixtureWorkspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                val page=fixtureHistory(threadId)
                return if(full) page.copy(messages=listOf(MobileMessage("authoritative","assistant","完整运行时历史")),nextCursor=null,runtimePresent=true)
                else if(cursor!=null) page.copy(messages=listOf(MobileMessage("earlier","user","更早消息")),nextCursor=null)
                else page.copy(nextCursor="older")
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader) }; main { session.connect("合成","https://fixture.invalid","") }
        ui.waitUntil { !session.loading }; ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil { !session.historyLoading && session.messages.isNotEmpty() }
        main { session.loadHistory(more=true)?.join() }; assertEquals("earlier",session.messages.first().id)
        full=true; main { session.loadHistory()?.join() }
        assertEquals(listOf("authoritative"),session.messages.map { it.id }); assertNull(session.historyCursor)
    }
    @Test fun transientReconnectIsQuietButEarlierReadOffersRetry() {
        var fail=false; var holding=false
        val workspaceRequests=java.util.concurrent.atomic.AtomicInteger()
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                workspaceRequests.incrementAndGet()
                if(holding) { started.complete(Unit); release.await() }
                if(fail) throw HostReadError("合成网络中断",transient=true)
                return fixtureWorkspace()
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(fail) throw HostReadError("合成服务 503",transient=true)
                return fixtureHistory(threadId).copy(nextCursor="older")
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader) }; main { session.connect("合成","https://fixture.invalid","") }; ui.waitUntil { !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }; ui.waitUntil { !session.historyLoading && session.messages.isNotEmpty() }
        val cached=session.messages
        ui.runOnIdle { fail=true; session.refresh() }; ui.waitUntil { !session.loading }
        assertNull(session.error); assertEquals(ConnectionPhase.WAITING,session.selectedClient.connectionPhase)
        ui.onNodeWithTag("client-connecting").assertExists()
        ui.onNodeWithTag("message-input").performClick().performTextInput("refresh-keeps-this-draft")
        ui.onNodeWithTag("open-sidebar").performClick()
        val beforeManualRefresh=workspaceRequests.get()
        ui.onNodeWithTag("sidebar-refresh").performTouchInput { swipeDown(durationMillis=450) }
        ui.waitUntil(10_000) { workspaceRequests.get()>beforeManualRefresh && !session.loading && session.error!=null }
        ui.onNodeWithTag("session-error").assertTextContains("刷新失败，请重试。",substring=true)
        assertEquals("refresh-keeps-this-draft",session.text); assertEquals(cached,session.messages)
        shot("workspace-transient-manual-error")
        ui.onNodeWithTag("dismiss-error").performClick()
        ui.onNodeWithTag("close-sidebar").performClick()
        main { session.loadHistory(more=true)?.join() }
        assertTrue(session.error!!.startsWith("加载更早消息失败，请重试。")); assertEquals(cached,session.messages); assertEquals("older",session.historyCursor)
        shot("history-pull-network-retry")
        ui.runOnIdle { fail=false; holding=true; session.enterForeground() }; runBlocking { started.await() }
        assertEquals(ConnectionPhase.CONNECTING,session.selectedClient.connectionPhase)
        release.complete(Unit); ui.waitUntil { !session.loading && session.selectedClient.online }
        assertNull(session.error); assertEquals(cached,session.messages)
        ui.runOnIdle { session.leaveForeground() }
    }
    @Test fun keyboardKeepsHeaderComposerAndActionInsideViewport() {
        demo(); ui.onNodeWithTag("message-input").performTextInput("第一行🙂\n第二行\n第三行")
        ui.waitUntil(10_000) { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true }
        ui.onNodeWithTag("send-message").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("chat-header").assertIsDisplayed()
        val insets=ViewCompat.getRootWindowInsets(activity.window.decorView)!!
        val screen=android.util.DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }
        val composer=ui.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        val header=ui.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot
        val rootLocation=IntArray(2).also { activity.window.decorView.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(it) }
        shot("keyboard-multiline-visible")
        val folder=File(activity.getExternalFilesDir(null),"acceptance/A3")
        File(folder,"keyboard-geometry.json").writeText(JSONObject().put("screenHeight",screen.heightPixels).put("rootY",rootLocation[1]).put("imeBottom",insets.getInsets(WindowInsetsCompat.Type.ime()).bottom).put("composerBottom",composer.bottom).put("headerTop",header.top).toString())
        assertTrue("Composer $composer must be above IME",rootLocation[1]+composer.bottom <= screen.heightPixels-insets.getInsets(WindowInsetsCompat.Type.ime()).bottom+2)
        assertTrue("Header must stay in viewport",rootLocation[1]+header.top>=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top-2)
    }
    @Test fun darkLandscapeKeepsControlsAndDraft() {
        demo(); ui.onNodeWithTag("message-input").performTextInput("横屏保留🙂")
        ui.runOnIdle { session.changeAppearance("dark"); activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            ui.waitUntil(10_000) { activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            ui.waitUntil(10_000) {
                val screen=android.util.DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }
                screen.widthPixels>screen.heightPixels && activity.window.decorView.width>activity.window.decorView.height
            }
            awaitWindowFrames()
            ui.onNodeWithTag("message-input").assertIsDisplayed().assertTextEquals("横屏保留🙂")
            val viewport=ui.onNodeWithTag("message-list").fetchSemanticsNode().boundsInRoot
            ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().forEach { action ->
                assertTrue("Landscape action must fit the chat viewport",action.boundsInRoot.top>=viewport.top && action.boundsInRoot.bottom<=viewport.bottom)
            }
            ui.onNodeWithTag("send-message").assertIsDisplayed(); shot("dark-landscape-keyboard")
            hideKeyboard()
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.onNodeWithTag("sidebar-list").assertIsDisplayed(); shot("dark-landscape-drawer")
        } finally { ui.runOnIdle { activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
    }
    @Test fun httpRejectsRedirectWrongTargetMalformedAndFailure() {
        connect(); val reads=NativeHostReads { "" }; val client=session.selectedClient
        for (thread in listOf("redirect","wrong-target","malformed","failure")) {
            val error=runBlocking { runCatching { reads.history(client,thread) }.exceptionOrNull() }
            assertNotNull("$thread must fail",error)
        }
        assertThrows(IllegalArgumentException::class.java) { NativeHostReads.baseAddress("https://example.invalid/path") }
        assertThrows(IllegalArgumentException::class.java) { NativeHostReads.baseAddress("https://user:secret@example.invalid") }
        val url=NativeHostReads.url(endpoint,"v1/history",mapOf("threadId" to "thread /🙂&other", "hostId" to "host"))
        assertTrue(url.rawQuery.contains("%26other")); assertEquals(2,url.rawQuery.split('&').size)
    }
    @Test fun sharedSchemaAndProviderSpecificMachineIdentity() {
        val vectors=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("state-v1.json").bufferedReader().use { it.readText() }).getJSONArray("toolPresentation")
        for(index in 0 until vectors.length()) {
            val test=vectors.getJSONObject(index); val messages=test.getJSONArray("messages").objects().map { HostReadCodec.message(it).timelineMessage() }
            val actual=when(test.getString("operation")) {
                "summary"->ToolPresentation.summary(messages)
                "title"->ToolPresentation.title(messages.single(),test.getBoolean("flag"))
                "compaction"->ToolPresentation.compaction(test.optional("value"),test.getBoolean("flag"))
                "status"->ToolPresentation.status(test.optional("value"))
                "style"->ToolPresentation.style(test.optional("value")).let { "${it.category}|${it.verb}|${it.detailTitle}" }
                else->error("Unknown operation")
            }
            assertEquals(test.getString("operation"),test.getString("expected"),actual)
        }
        val scope=TargetScope("codex","authority","subject","host","workspace","runtime")
        val other=TargetScope("codex","authority","other","host","workspace","runtime")
        assertTrue(sameScope(scope,other)); assertTrue(sameScope(scope,scope))
        assertFalse(sameScope(TargetScope("easywork","authority","subject","host","workspace","runtime"),
            TargetScope("easywork","authority","other","host","workspace","runtime")))
        assertThrows(IllegalArgumentException::class.java) { HostReadCodec.history("""{"hostId":"host","threadId":"thread","messages":[{"id":"bad","role":"assistant","text":false}]}""") }
        assertEquals("42",HostReadCodec.message(JSONObject("""{"id":"42","role":"assistant","text":"42"}""")).text)
        val unsafe=NativeMarkdown.inline(NativeMarkdown.parse("[unsafe](javascript:alert%281%29)"),androidx.compose.ui.graphics.Color.Blue)
        assertEquals("unsafe",unsafe.text.trim()); assertTrue(unsafe.getLinkAnnotations(0,unsafe.length).isEmpty())
    }
    @Test fun lateHistoryFromOldThreadCannotReplaceSelectedThread() {
        val historyStarted=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=fixtureWorkspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                if(threadId=="old") withContext(NonCancellable) { historyStarted.complete(Unit); release.await() }
                return fixtureHistory(threadId)
            }
        }
        ui.runOnIdle { session.resetAcceptance(reader) }; main { session.connect("合成","https://fixture.invalid","") }
        ui.waitUntil { !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        runBlocking { historyStarted.await() }
        ui.runOnIdle { session.selectThread(session.threads.last()) }; ui.waitUntil { session.messages.firstOrNull()?.id=="new-message" }
        release.complete(Unit); ui.waitForIdle()
        assertEquals("new",session.selectedThread); assertEquals("new-message",session.messages.single().id)
    }
    @Test fun lateWorkspaceFromEditedConnectionCannotReplaceNewSnapshot() {
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var calls=0
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                calls++; if(calls==2) withContext(NonCancellable) { started.complete(Unit); release.await() }
                return fixtureWorkspace(if(client.name=="新连接") "new-workspace" else "old-workspace")
            }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=fixtureHistory(threadId)
        }
        ui.runOnIdle { session.resetAcceptance(reader) }; main { session.connect("旧连接","https://fixture.invalid","") }
        ui.runOnIdle { session.refresh() }
        runBlocking { started.await() }
        val old=session.selectedClient
        main { session.connect("新连接","https://fixture.invalid","",old) }
        release.complete(Unit); ui.waitUntil { !session.loading }
        assertEquals("新连接",session.selectedClient.name); assertEquals("new-workspace",session.workspace!!.name)
    }
    @Test fun failedRefreshRetainsCachedListsAndExplainsOffline() {
        var fail=false
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace { if(fail) throw HostReadError("合成读取失败"); return fixtureWorkspace() }
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=fixtureHistory(threadId)
        }
        ui.runOnIdle { session.resetAcceptance(reader) }; main { session.connect("合成","https://fixture.invalid","") }; ui.waitUntil { !session.loading }
        val before=session.threads
        ui.runOnIdle { fail=true; session.refresh() }; ui.waitUntil { !session.loading && session.error != null }
        assertEquals(before,session.threads); assertFalse(session.selectedClient.online)
        ui.onNodeWithTag("offline-cache").assertExists(); shot("offline-cache-error")
    }
    @Test fun encryptedCacheRestoresReadAndDraftWithoutRuntimeControl() {
        val namespace="acceptance-${UUID.randomUUID()}"
        val storage=NativeSessionStorage(ui.activity,namespace)
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=fixtureWorkspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=fixtureHistory(threadId)
        }
        lateinit var first: MobileSession
        main { first=MobileSession(storage,reader); first.restore() }
        ui.waitUntil { !first.restoring }
        main { first.connect("缓存电脑","https://fixture.invalid",""); first.selectThread(first.threads.last()); first.text="本地🙂草稿"; first.toggleSidebarRecent(); first.toggleSidebarProjects(); first.toggleSidebarList("project") }
        ui.waitUntil { !first.historyLoading && first.messages.isNotEmpty() }
        ui.waitUntil(10_000) {
            val metadata=storage.metadata()
            val sidebar=metadata?.optJSONObject("sidebar")?.optJSONObject(first.selectedClient.cacheKey(null))
            metadata?.getJSONObject("drafts")?.optString(first.targetKey)=="本地🙂草稿" && storage.read("history:${first.targetKey}") != null && sidebar?.optBoolean("recent")==true && sidebar.optBoolean("projects")==false && sidebar.getJSONArray("lists").strings()==listOf("project")
        }
        val offline=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace=throw HostReadError("离线")
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory=throw HostReadError("离线")
        }
        lateinit var second: MobileSession
        main { second=MobileSession(storage,offline); second.restore() }
        ui.waitUntil(10_000) { !second.restoring && second.messages.isNotEmpty() && !second.loading }
        assertEquals("本地🙂草稿",second.text); assertEquals("new-message",second.messages.single().id)
        assertFalse(second.selectedClient.online); assertFalse(second.decision.enabled)
        assertTrue(second.sidebarDisplay.recentExpanded); assertFalse(second.sidebarDisplay.projectsExpanded); assertEquals(setOf("project"),second.sidebarDisplay.expandedLists)
        storage.write("aad-a","private synthetic secret")
        assertEquals("private synthetic secret",storage.read("aad-a"))
        val files=File(activity.noBackupFilesDir,namespace).listFiles()!!
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { !it.readBytes().toString(Charsets.ISO_8859_1).contains("private synthetic secret") })
    }
    @Test fun clientRemovalInvalidatesLateReadAndKeepsDraftScope() {
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=fixtureWorkspace()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory { withContext(NonCancellable) { started.complete(Unit); release.await() }; return fixtureHistory(threadId) }
        }
        ui.runOnIdle { session.resetAcceptance(reader) }; main { session.connect("合成","https://fixture.invalid","") }; ui.waitUntil { !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.last()); session.text="原目标草稿" }; runBlocking { started.await() }
        ui.runOnIdle { session.removeClient("boundary-host") }; release.complete(Unit); ui.waitForIdle()
        assertFalse(session.hasClient); assertTrue(session.messages.isEmpty()); assertEquals("",session.text)
    }
    @Test fun nativeSettingsAppearanceAndNoSearchResults() {
        demo(); ui.onNodeWithTag("chat-menu").performClick(); ui.onNodeWithTag("open-settings").performClick(); ui.onNodeWithTag("settings-appearance").performClick(); ui.onNodeWithTag("appearance-dark").performClick(); shot("appearance-dark-native")
        ui.onNodeWithTag("appearance-back").performClick(); shot("settings-dark-native")
        ui.onNodeWithTag("settings-done").performClick(); ui.onNodeWithTag("open-sidebar").performClick(); ui.onNodeWithTag("sidebar-search-toggle").performClick(); ui.onNodeWithTag("sidebar-search").performTextInput("绝不存在的合成内容")
        ui.onNodeWithText("没有找到相关内容").assertIsDisplayed(); shot("drawer-empty-search-dark")
    }
    private fun fixtureWorkspace(name: String="boundary-workspace",state: String="idle"): MobileWorkspace=HostReadCodec.workspace(JSONObject().put("hostId","boundary-host").put("name",name).put("connectionMode","history-only").put("projects",org.json.JSONArray()).put("threads",org.json.JSONArray(listOf("old","new").map { JSONObject().put("id",it).put("hostId","boundary-host").put("title",it).put("state",state) })).toString())
    private fun fixtureHistory(thread: String): MobileHistory=HostReadCodec.history(JSONObject().put("hostId","boundary-host").put("threadId",thread).put("messages",org.json.JSONArray(listOf(JSONObject().put("id","$thread-message").put("role","assistant").put("text","$thread 回复").put("status","completed")))).toString())
}
