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

class MobileWorkspaceArchiveUiTest {
    @get:Rule(order=0) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val fixtures=mutableListOf<ChatManagementFixture>()
    private val activity: MainActivity get() { var result: MainActivity?=null;ui.activityRule.scenario.onActivity { result=it };return checkNotNull(result) }
    private val session get()=activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun fresh() { ui.runOnIdle { session.resetAcceptance() } }
    @After fun finish() {
        try { fixtures.forEach { fixture -> assertTrue("Only named management POSTs",fixture.posts.all { it.path=="/v1/workspace-management" }) } }
        finally { fixtures.forEach { it.release?.countDown() };ui.runOnIdle { session.leaveForeground() };fixtures.forEach { it.close() } }
    }
    private fun connect(state: String="unknown",prepare: (ChatManagementFixture)->Unit = {}): ChatManagementFixture {
        val fixture=ChatManagementFixture().also { it.state=state;it.batchSupported=true;prepare(it);fixtures+=it }
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
    private fun page() { menu();ui.onNodeWithTag("archived-chats").performClick();ui.onNodeWithTag("batch-archive-open").performClick();ui.onNodeWithTag("sheet-workspace-archive").assertExists() }
    @Test fun searchSelectAllIsCappedAt100ConfirmationCancelWritesNothingAndExactBatchSubmitsOnce() {
        val fixture=connect { it.extraThreads=(1..103).map { n->"batch-extra-$n" } }
        page();ui.onNodeWithTag("management-select-all").performClick();ui.onNodeWithTag("management-selected-count").assertTextContains("100",substring=true)
        ui.onNodeWithTag("management-search").performTextInput("Original")
        ui.onNodeWithTag("management-select-all").performClick();ui.onNodeWithTag("management-selected-count").assertTextContains("99",substring=true)
        ui.onNodeWithTag("management-search").performTextClearance();ui.onNodeWithTag("management-select-all").performClick()
        ui.onNodeWithTag("management-selected-count").assertTextContains("100",substring=true);shot("batch-selection-limit100")
        ui.onNodeWithTag("management-submit").performClick();shot("batch-confirmation100")
        ui.onNodeWithTag("management-cancel").performClick();assertTrue(fixture.posts.isEmpty());assertEquals("separate unsent draft",session.text)
        ui.onNodeWithTag("management-submit").performClick();ui.onNodeWithTag("management-confirm").performClick()
        ui.waitUntil(10_000) { session.archiveBatchRecords.singleOrNull()?.phase=="completed"&&!session.archiveBatchBusy&&!session.loading }
        val body=org.json.JSONObject(String(fixture.posts.single().body));assertEquals(100,body.getJSONArray("ids").length())
        assertEquals(100,body.getJSONArray("ids").strings().distinct().size);assertEquals("threads:archive",body.getString("action"))
        assertTrue(session.selectedThread.isEmpty());assertEquals(100,fixture.batchArchived.size)
        val record=session.archiveBatchRecords.single();ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-result-${record.id}"))
        ui.onNodeWithTag("management-result-${record.id}").assertTextContains("成功 100 项 · 失败 0 项 · 待确认 0 项");shot("batch-exact100-result")
    }
    @Test fun mixedDesktopRefusalShowsEachOutcomeAndUnknownOnlyQueriesOriginalId() {
        val fixture=connect { it.batchStates[ChatManagementFixture.first]="outcome-unknown";it.batchStates[ChatManagementFixture.other]="rejected" }
        page();ui.onNodeWithTag("management-select-all").performClick();ui.onNodeWithTag("management-submit").performClick();ui.onNodeWithTag("management-confirm").performClick()
        ui.waitUntil(10_000) { session.archiveBatchRecords.singleOrNull()?.phase=="outcome-unknown"&&!session.archiveBatchBusy }
        val record=session.archiveBatchRecords.single()
        ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-result-${record.id}"))
        ui.onNodeWithTag("management-result-${record.id}").assertTextContains("成功 0 项 · 失败 1 项 · 待确认 1 项");shot("batch-mixed-refusal-and-unknown")
        fixture.batchStates.clear();ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-check-${record.id}"))
        ui.onNodeWithTag("management-check-${record.id}").performClick()
        ui.waitUntil(10_000) { session.archiveBatchRecords.single().phase=="completed"&&!session.archiveBatchBusy&&!session.loading }
        ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-result-${record.id}"))
        ui.onNodeWithTag("management-result-${record.id}").assertTextContains("成功 1 项 · 失败 1 项 · 待确认 0 项");shot("batch-query-terminal-refusal-retained")
        assertEquals(1,fixture.posts.size);assertEquals(record.intent,session.archiveBatchRecords.single().intent)
    }
    @Test fun pendingBatchClearsSelectionAndDisablesSubmitWhileKeepingOriginalQuery() {
        val fixture=connect { it.batchStates[ChatManagementFixture.first]="outcome-unknown" }
        page();ui.onNodeWithTag("management-item-${ChatManagementFixture.first}").performClick()
        ui.onNodeWithTag("management-submit").performClick();ui.onNodeWithTag("management-confirm").performClick()
        ui.waitUntil(10_000) { session.archiveBatchRecords.singleOrNull()?.phase=="outcome-unknown"&&!session.archiveBatchBusy }
        val original=session.archiveBatchRecords.single()
        ui.onNodeWithTag("management-selected-count").assertTextContains("0",substring=true)
        ui.onNodeWithTag("management-submit").assertIsNotEnabled()
        ui.onNodeWithTag("management-item-${ChatManagementFixture.first}").assertIsNotEnabled()
        ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-check-${original.id}"))
        ui.onNodeWithTag("management-check-${original.id}").assertIsEnabled();shot("pending-original-prunes-selection-and-disables-submit")
        assertEquals(1,fixture.posts.size);assertEquals(original.intent,session.archiveBatchRecords.single().intent)
        ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-item-${ChatManagementFixture.other}"))
        ui.onNodeWithTag("management-item-${ChatManagementFixture.other}").performClick()
        ui.onNodeWithTag("management-selected-count").assertTextContains("1",substring=true);ui.onNodeWithTag("management-submit").assertIsEnabled()
        assertEquals("separate unsent draft",session.text);assertEquals(1,fixture.posts.size)
    }
    @Test fun oldestOriginalAfterElevenRecordsRemainsReachableAndQueryNeverReplays() {
        val ids=(1..10).map { "record-extra-$it" }
        val fixture=connect { it.extraThreads=ids;it.batchStates[ChatManagementFixture.first]="outcome-unknown" }
        for(id in listOf(ChatManagementFixture.first)+ids) {
            main { checkNotNull(session.archiveWorkspace(session.captureWorkspaceArchive(),listOf(id))).join() }
            ui.waitUntil(10_000) { !session.archiveBatchBusy&&session.archiveBatchRecords.any { org.json.JSONObject(it.intent).getJSONArray("ids").getString(0)==id } }
        }
        ui.waitUntil(10_000) { session.archiveBatchRecords.size==11&&!session.loading }
        val original=session.archiveBatchRecords.single { org.json.JSONObject(it.intent).getJSONArray("ids").getString(0)==ChatManagementFixture.first }
        assertEquals("outcome-unknown",original.phase);assertEquals(11,fixture.posts.size)
        assertEquals(11,session.archiveBatchRecords.map { it.id }.distinct().size)
        page();ui.onNodeWithTag("management-list").performScrollToNode(hasTestTag("management-check-${original.id}"))
        ui.onNodeWithTag("management-check-${original.id}").assertIsDisplayed().assertIsEnabled();shot("oldest-original-visible-after-eleven-records")
        fixture.batchGetStatus=404;ui.onNodeWithTag("management-check-${original.id}").performClick()
        ui.waitUntil(10_000) { !session.archiveBatchBusy&&fixture.server.requests.any { it.method=="GET"&&it.path=="/v1/workspace-management" } }
        val query=fixture.server.requests.last { it.method=="GET"&&it.path=="/v1/workspace-management" }
        val params=java.net.URI(query.pathWithQuery).rawQuery.split('&').associate { pair -> val bits=pair.split('=',limit=2);java.net.URLDecoder.decode(bits[0],"UTF-8") to java.net.URLDecoder.decode(bits[1],"UTF-8") }
        assertEquals(original.id,params["id"]);assertEquals(fixture.target.hostId,params["hostId"])
        assertTrue(sameScope(HostReadCodec.target(org.json.JSONObject(params.getValue("target"))),fixture.target))
        assertEquals(11,fixture.posts.size);assertEquals(original.intent,session.archiveBatchRecords.single { it.id==original.id }.intent)
        assertEquals("separate unsent draft",session.text);ui.onNodeWithTag("management-check-${original.id}").assertExists();shot("oldest-original404-keeps-eleven-records-and-draft")
    }
    @Test fun changingRuntimeWhileConfirmationOpenCannotSendOldBatch() {
        val first=connect();page();ui.onNodeWithTag("management-select-all").performClick();ui.onNodeWithTag("management-submit").performClick()
        val second=ChatManagementFixture().also { it.batchSupported=true;fixtures+=it }
        main { session.connect("other runtime",second.server.endpoint,"") }
        ui.waitUntil(10_000) { session.selectedClient.scope?.runtimeId==second.target.runtimeId&&!session.loading }
        ui.onNodeWithTag("management-confirm").assertDoesNotExist();ui.onNodeWithTag("management-selected-count").assertTextContains("0",substring=true)
        assertTrue(first.posts.isEmpty());assertTrue(second.posts.isEmpty());shot("batch-runtime-change-discards-confirmation")
    }
}
