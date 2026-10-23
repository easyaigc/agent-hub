package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

class MobileSidebarTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val fixtures=mutableListOf<CommandFixture>()
    private val activity: MainActivity get() { var value: MainActivity?=null;ui.activityRule.scenario.onActivity { value=it };return checkNotNull(value) }
    private val session get()=activity.session
    @Before fun fresh() { ui.runOnIdle { session.resetAcceptance() } }
    @After fun finish() {
        try { fixtures.forEach { server -> assertTrue("Sidebar reads must not dispatch writes",server.requests.all { it.method=="GET" }) } }
        finally { ui.runOnIdle { session.leaveForeground() };fixtures.forEach { it.close() } }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun connect(host: String="sidebar-host-a",writable: Boolean=false): MobileClient {
        val target=TargetScope("codex",null,null,host,"workspace-$host","runtime-$host")
        fun thread(id: String,project: String="p1",archived: Boolean=false)=JSONObject().put("id",id).put("hostId",host).put("title",id.uppercase())
            .put("state","idle").put("projectId",project).put("archived",archived).put("lastTurnStatus","completed")
        val catalog=JSONObject().put("hostId",host).put("name",host).put("connectionMode",if(writable) "managed-runtime" else "history-only")
            .put("capabilities",JSONArray(if(writable)listOf("threads:create","reply","commands:target-bound:v1")else emptyList<String>())).put("readTarget",CodexCommandCodec.target(target))
            .put("projects",JSONArray(listOf("p1","p2","p3").map { JSONObject().put("id",it).put("hostId",host).put("name","FOLDER $it").put("roots",JSONArray(listOf("/synthetic/shared-root"))) }))
            .put("threads",JSONArray((0..6).map { thread("row-$it") }+listOf(thread("pinned"),thread("archived",archived=true))))
            .put("pinnedThreadIds",JSONArray(listOf("pinned"))).put("sidebar",JSONObject().put("version",1)
                .put("pinned",JSONArray().put(JSONObject().put("kind","project").put("id","p2")).put(JSONObject().put("kind","thread").put("id","pinned")))
                .put("recentThreadIds",JSONArray(listOf("row-6","row-1","row-5","row-0","row-4","row-3","row-2","archived")))
                .put("projectIds",JSONArray(listOf("p3","p1"))).put("projectThreadIds",JSONObject().put("p1",JSONArray(listOf("pinned")+(0..6).map { "row-$it" }))
                    .put("p2",JSONArray(listOf("row-0"))).put("p3",JSONArray())))
        val server=CommandFixture { request -> when {
            request.method!="GET" -> WireResponse(405)
            request.path=="/v1/workspace" -> WireResponse(body=catalog.toString())
            request.path=="/v1/history" -> WireResponse(body=JSONObject().put("hostId",host).put("threadId","unused").put("messages",JSONArray()).toString())
            else -> WireResponse(404)
        } };fixtures+=server
        lateinit var client: MobileClient
        main { client=session.connect(host,server.endpoint,"") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading };return client
    }
    private fun open() { ui.onNodeWithTag("open-sidebar").performClick();ui.onNodeWithTag("sidebar-list").assertIsDisplayed() }
    private fun scroll(tag: String)=ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag(tag))
    private fun shot(name: String) {
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
        val frames=CountDownLatch(1)
        ui.runOnUiThread { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS));instrumentation.uiAutomation.waitForIdle(300,5_000)
        assertEquals(instrumentation.targetContext.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=InstrumentationRegistry.getArguments().getString("sidebarRunId") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(instrumentation.targetContext.filesDir,"acceptance/P3-sidebar/$run").apply { mkdirs() }
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @Test fun desktopMixedOrderExcludesArchivedAndPinsFromRecentAndProjectRows() {
        connect();open()
        val folder=ui.onNodeWithTag("project-p2").fetchSemanticsNode().boundsInRoot.top
        val pin=ui.onNodeWithTag("pinned-thread-pinned").fetchSemanticsNode().boundsInRoot.top
        assertTrue(folder<pin)
        assertTrue(ui.onNodeWithTag("recent-thread-row-6").fetchSemanticsNode().boundsInRoot.top<ui.onNodeWithTag("recent-thread-row-1").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithTag("recent-thread-pinned").assertDoesNotExist();ui.onNodeWithTag("recent-thread-archived").assertDoesNotExist()
        ui.onNodeWithTag("recent-thread-row-2").assertDoesNotExist();shot("mixed-pinned-and-recent-five")
        scroll("sidebar-more-recent");ui.onNodeWithTag("sidebar-more-recent").performClick()
        scroll("recent-thread-row-2");ui.onNodeWithTag("recent-thread-row-2").assertIsDisplayed()
        scroll("project-p1");ui.onNodeWithTag("project-p1").performClick()
        ui.onNodeWithTag("project-thread-p1-pinned").assertDoesNotExist()
        shot("ordered-folders-and-expanded-recent")
    }
    @Test fun searchTemporarilyExpandsCollapsedSectionsWithoutChangingSavedPreferences() {
        connect();open()
        scroll("sidebar-pinned-toggle");ui.onNodeWithTag("sidebar-pinned-toggle").performClick()
        scroll("sidebar-recent-toggle");ui.onNodeWithTag("sidebar-recent-toggle").performClick()
        scroll("sidebar-projects-toggle");ui.onNodeWithTag("sidebar-projects-toggle").performClick()
        ui.onNodeWithTag("sidebar-search-toggle").performClick();ui.onNodeWithTag("sidebar-search").performTextInput("ROW")
        ui.onNodeWithTag("sidebar-search").performImeAction()
        ui.waitUntil(5_000) { !ui.onNodeWithTag("sidebar-search").fetchSemanticsNode().config[SemanticsProperties.Focused] }
        ui.onNodeWithTag("sidebar-search").assertIsNotFocused()
        ui.waitUntil(5_000) { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())!=true }
        scroll("sidebar-pinned-toggle");ui.onNodeWithTag("sidebar-pinned-toggle").assertIsNotEnabled()
        assertFalse(session.sidebarDisplay.pinnedSectionExpanded);assertFalse(session.sidebarDisplay.recentSectionExpanded);assertFalse(session.sidebarDisplay.projectsExpanded)
        scroll("project-thread-p1-row-6");ui.onNodeWithTag("project-thread-p1-row-6").assertIsDisplayed();shot("search-temporary-expansion")
        ui.onNodeWithTag("sidebar-search-clear").performClick();ui.onNodeWithTag("sidebar-search-toggle").performClick()
        scroll("sidebar-pinned-toggle");ui.onNodeWithTag("pinned-thread-pinned").assertDoesNotExist()
        ui.onNodeWithTag("recent-thread-row-6").assertDoesNotExist();ui.onNodeWithTag("project-p1").assertDoesNotExist()
        shot("saved-collapsed-sections-restored")
    }
    @Test fun sectionAndListPreferencesStayWithClientAcrossActivityRecreation() {
        val first=connect();val second=connect("sidebar-host-b")
        ui.runOnIdle { session.select(first) };ui.waitUntil(10_000) { !session.loading };open()
        scroll("sidebar-more-recent");ui.onNodeWithTag("sidebar-more-recent").performClick()
        scroll("sidebar-pinned-toggle");ui.onNodeWithTag("sidebar-pinned-toggle").performClick()
        scroll("sidebar-recent-toggle");ui.onNodeWithTag("sidebar-recent-toggle").performClick()
        scroll("sidebar-projects-toggle");ui.onNodeWithTag("sidebar-projects-toggle").performClick()
        ui.runOnIdle { session.select(second) };ui.waitUntil(10_000) { !session.loading }
        assertTrue(session.sidebarDisplay.pinnedSectionExpanded);assertTrue(session.sidebarDisplay.recentSectionExpanded);assertTrue(session.sidebarDisplay.projectsExpanded);assertFalse(session.sidebarDisplay.recentExpanded)
        ui.runOnIdle { session.select(first) };ui.waitUntil(10_000) { !session.loading }
        ui.activityRule.scenario.recreate();ui.waitUntil(10_000) { !session.restoring }
        assertFalse(session.sidebarDisplay.pinnedSectionExpanded);assertFalse(session.sidebarDisplay.recentSectionExpanded);assertFalse(session.sidebarDisplay.projectsExpanded);assertTrue(session.sidebarDisplay.recentExpanded)
        if(!runCatching { ui.onNodeWithTag("sidebar-list").assertIsDisplayed() }.isSuccess)open()
        shot("client-section-state-after-recreation")
    }
    @Test fun existingProjectShortcutAndNativeBackKeepDraftWithoutSending() {
        connect(writable=true);open()
        assertTrue("Fixture must declare the complete current target-bound create prerequisites",session.canCreateThread)
        scroll("new-in-p1");ui.onNodeWithTag("new-in-p1").performClick()
        assertEquals(session.selectedClient.cacheKey(null,"p1"),session.targetKey)
        ui.onNodeWithTag("message-input").performTextInput("sidebar native unsent draft")
        ui.onNodeWithTag("composer-dismiss-keyboard").performClick();open()
        ui.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("message-input").assertIsDisplayed().assertTextEquals("sidebar native unsent draft")
        assertEquals(session.selectedClient.cacheKey(null,"p1"),session.targetKey);shot("existing-project-shortcut-and-unsent-draft")
    }
    @Test fun closingDrawerDisablesHiddenSearchAndKeepsQueryAndChatDraft() {
        connect(writable=true);open();scroll("new-in-p1");ui.onNodeWithTag("new-in-p1").performClick()
        ui.onNodeWithTag("message-input").performTextInput("separate unsent chat draft")
        ui.onNodeWithTag("composer-dismiss-keyboard").performClick();open()
        ui.onNodeWithTag("sidebar-search-toggle").performClick();ui.onNodeWithTag("sidebar-search").performTextInput("ROW")
        ui.onNodeWithTag("close-sidebar").performClick()
        ui.waitUntil(5_000) { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())!=true }
        ui.onNodeWithTag("sidebar-search").assertIsNotEnabled()
        val hidden=ui.onNodeWithTag("sidebar-search").fetchSemanticsNode().config
        // Disabled fields remove their focus target and may omit the Focused key.
        assertFalse(hidden.contains(SemanticsProperties.Focused)&&hidden[SemanticsProperties.Focused])
        ui.onNodeWithTag("message-input").assertIsDisplayed().assertTextEquals("separate unsent chat draft")
        assertEquals("ROW",session.search)
        open();ui.onNodeWithTag("sidebar-search").assertIsEnabled().assertTextEquals("ROW")
        ui.waitUntil(5_000) { !ui.onNodeWithTag("sidebar-search").fetchSemanticsNode().config[SemanticsProperties.Focused] }
        ui.waitUntil(5_000) { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())!=true }
        assertEquals("separate unsent chat draft",session.text);shot("reopened-query-keeps-separate-chat-draft")
    }
}
