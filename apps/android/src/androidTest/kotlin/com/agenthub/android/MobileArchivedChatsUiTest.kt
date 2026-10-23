package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileArchivedChatsUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val fixtures=mutableListOf<ChatManagementFixture>()
    private val activity: MainActivity get() { var result: MainActivity?=null;ui.activityRule.scenario.onActivity { result=it };return checkNotNull(result) }
    private val session get()=activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun fresh() { ui.runOnIdle { session.resetAcceptance() } }
    @After fun finish() {
        try { fixtures.forEach { fixture -> assertTrue("Only named management POSTs",fixture.posts.all { it.path=="/v1/threads/actions" }) } }
        finally { fixtures.forEach { it.release?.countDown() };ui.runOnIdle { session.leaveForeground() };fixtures.forEach { it.close() } }
    }
    private fun connect(state: String="unknown",prepare: (ChatManagementFixture)->Unit = {}): ChatManagementFixture {
        val fixture=ChatManagementFixture().also { it.state=state;prepare(it);fixtures+=it }
        main { session.connect("management UI",fixture.server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
        ui.waitUntil(10_000) { session.currentChat!=null&&!session.historyLoading }
        main { session.text="separate unsent draft" }
        return fixture
    }
    private fun menu() { ui.onNodeWithTag("chat-menu").performClick() }
    private fun confirmed(count: Int) { ui.waitUntil(10_000) { session.chatActionRecords.count { it.phase=="confirmed" }==count&&!session.loading } }
    private fun shot(name: String) {
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync()
        val frames=CountDownLatch(1);ui.runOnUiThread { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5,TimeUnit.SECONDS));instrumentation.uiAutomation.waitForIdle(300,5_000)
        assertEquals(instrumentation.targetContext.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val run=InstrumentationRegistry.getArguments().getString("managementRunId") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val folder=File(instrumentation.targetContext.filesDir,"acceptance/P3-management/$run").apply { mkdirs() }
        val image=instrumentation.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun archived() { menu();ui.onNodeWithTag("archived-chats").performClick();ui.onNodeWithTag("sheet-archived-chats").assertExists() }
    @Test fun archivedHistoryReadsWithoutPostAndRestorePreservesEachDraft() {
        val fixture=connect { it.archived=true;it.lastTurnStatus="completed" }
        main { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="other independent draft" }
        archived();ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").assertTextContains("已归档",substring=true).assertTextContains("已完成",substring=true)
        shot("archive-list-last-known-task");assertTrue(fixture.posts.isEmpty())
        ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").performClick()
        ui.waitUntil(10_000) { session.selectedThread==ChatManagementFixture.first&&!session.historyLoading }
        ui.onNodeWithTag("sheet-archived-chats").assertDoesNotExist();assertEquals("separate unsent draft",session.text)
        assertTrue(fixture.posts.isEmpty());assertFalse(session.canCompose)
        ui.onNodeWithTag("readonly-notice").assertTextContains("对话已归档",substring=true);shot("archive-history-read-only")
        menu();ui.onNodeWithTag("chat-archive").assertTextContains("恢复对话").performClick();confirmed(1)
        assertEquals("unarchive",org.json.JSONObject(String(fixture.posts.single().body)).getString("action"))
        assertEquals("separate unsent draft",session.text)
        archived();ui.onNodeWithTag("archived-empty").assertExists();shot("archive-empty-after-confirmed-restore")
        ui.onNodeWithTag("sheet-done").performClick()
        main { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other }) }
        assertEquals("other independent draft",session.text)
    }
    @Test fun archiveBrowseIsAvailableWithoutLifecycleCapabilityAndClosingDoesNotWrite() {
        val fixture=connect { it.supported=false;it.archived=true }
        archived();ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").assertExists()
        ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").performClick()
        ui.waitUntil(10_000) { !session.historyLoading }
        ui.onNodeWithTag("readonly-notice").assertTextContains("可在电脑端恢复",substring=true)
        menu();ui.onNodeWithTag("chat-archive").assertDoesNotExist()
        ui.onNodeWithTag("archived-chats").performClick();ui.onNodeWithTag("sheet-done").performClick()
        assertTrue(fixture.posts.isEmpty());assertEquals("separate unsent draft",session.text)
    }
    @Test fun pagedArchiveFailureKeepsRowsAndRetryFetchesOriginalCursorWithoutPost() {
        val fixture=connect { it.archived=true;it.archivePaging=true;it.rejectArchivePage=true }
        archived();ui.onNodeWithTag("archived-load-more").performClick()
        ui.waitUntil(10_000) { session.error!=null&&!session.loading }
        main { session.error=null }
        ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").assertExists()
        assertEquals("separate unsent draft",session.text);assertTrue(fixture.posts.isEmpty())
        assertFalse(session.selectedClient.online);ui.onNodeWithTag("archived-refresh").assertIsEnabled()
        fixture.rejectArchivePage=false;ui.onNodeWithTag("archived-load-more").assertIsEnabled().performClick()
        ui.waitUntil(10_000) { session.threads.any { it.id=="thread-archive-page-two" }&&!session.loading }
        ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").assertExists()
        ui.onNodeWithTag("archived-chat-thread-archive-page-two").assertTextContains("执行失败",substring=true)
        ui.onNodeWithTag("archived-load-more").assertDoesNotExist();shot("archive-loaded-older-page")
        val requests=fixture.server.requests.filter { it.pathWithQuery.contains("threadCursor=archive-page-two") }
        assertEquals(2,requests.size);assertTrue(requests.all { it.method=="GET" });assertTrue(fixture.posts.isEmpty())
    }
    @Test fun sameHostDifferentRuntimeCannotLeakArchivedRowsAcrossClients() {
        val first=connect { it.archived=true };archived();ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").assertExists()
        val second=ChatManagementFixture().also { fixtures+=it }
        main { session.connect("second runtime",second.server.endpoint,"") }
        ui.waitUntil(10_000) { session.selectedClient.scope?.runtimeId==second.target.runtimeId&&!session.loading }
        ui.onNodeWithTag("archived-empty").assertExists();ui.onNodeWithTag("archived-chat-${ChatManagementFixture.first}").assertDoesNotExist()
        shot("archive-new-runtime-no-old-rows");assertTrue(first.posts.isEmpty());assertTrue(second.posts.isEmpty())
    }
    @Test fun archivedRunningHistoryCannotGrantStopOrGenerationButRestoreStaysAvailable() {
        val fixture=connect("running") { it.archived=true;it.writableHistory=true }
        ui.waitUntil(10_000) { session.runtime?.canControl==true&&session.running&&!session.historyLoading }
        main { session.text="" }
        assertFalse("Archived history cannot grant execution even if the captured runtime still says canControl",session.decision.enabled)
        assertFalse(session.canConfigureGeneration);assertFalse(session.canSelectPlan);assertFalse(session.canCompose)
        main { session.performAction();session.text="separate unsent draft" };assertTrue(fixture.posts.isEmpty())
        menu();ui.onNodeWithTag("chat-archive").assertIsEnabled().assertTextContains("恢复对话").performClick();confirmed(1)
        ui.waitUntil(10_000) { session.currentChat?.archived==false&&session.decision.enabled }
        assertEquals("running",session.runtime?.state);assertEquals("unarchive",org.json.JSONObject(String(fixture.posts.single().body)).getString("action"))
        assertEquals("separate unsent draft",session.text)
    }

}
