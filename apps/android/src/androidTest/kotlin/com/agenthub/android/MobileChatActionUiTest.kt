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

class MobileChatActionUiTest {
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
    private fun connect(state: String="running",prepare: (ChatManagementFixture)->Unit = {}): ChatManagementFixture {
        val fixture=ChatManagementFixture().also { it.state=state;prepare(it);fixtures+=it }
        main { session.connect("management UI",fixture.server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
        ui.waitUntil(10_000) { session.canManageChat(session.currentChat)&&!session.historyLoading }
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
    @Test fun runningAndApprovalDoNotPreDisablePinOrLifecycleActions() {
        val fixture=connect()
        menu();ui.onNodeWithTag("chat-lifecycle-status").assertTextContains("执行中",substring=true)
        ui.onNodeWithTag("chat-pin").assertIsEnabled();ui.onNodeWithTag("chat-archive").assertIsEnabled();ui.onNodeWithTag("chat-delete").assertIsEnabled()
        for(icon in listOf("chat-pin-icon","chat-search-icon","chat-archive-icon","chat-delete-icon"))ui.onNodeWithTag(icon,useUnmergedTree=true).assertExists()
        val order=listOf("chat-pin","chat-search-open","chat-archive","chat-delete").map { ui.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Same semantic action order as iOS",order.zipWithNext().all { it.first<it.second })
        shot("running-menu-all-actions-available")
        ui.onNodeWithTag("chat-pin").performClick();confirmed(1)
        ui.waitUntil(10_000) { session.currentChatPinned }
        menu();ui.onNodeWithTag("chat-pin").assertTextContains("取消置顶");ui.onNodeWithTag("chat-pin").performClick();confirmed(2)
        fixture.state="waiting-for-approval";main { session.refresh(userInitiated=true) }
        ui.waitUntil(10_000) { session.currentChat?.state=="waiting-for-approval"&&!session.loading }
        menu();ui.onNodeWithTag("chat-lifecycle-status").assertTextContains("等待审批",substring=true)
        ui.onNodeWithTag("chat-archive").assertIsEnabled();ui.onNodeWithTag("chat-delete").assertIsEnabled();shot("approval-menu-lifecycle-available")
        assertEquals(2,fixture.posts.size);assertEquals("separate unsent draft",session.text)
    }
    @Test fun archivedStateAndLastTaskOutcomeAreSeparateAndRestoreKeepsDraft() {
        val fixture=connect("unknown");fixture.lastTurnStatus="completed"
        menu();ui.onNodeWithTag("chat-archive").performClick();confirmed(1)
        menu();ui.onNodeWithTag("chat-lifecycle-status").assertTextContains("已归档",substring=true).assertTextContains("已完成",substring=true)
        ui.onNodeWithTag("chat-pin").assertIsNotEnabled();ui.onNodeWithTag("chat-archive").assertIsEnabled().assertTextContains("恢复对话")
        assertFalse(session.canCompose);shot("archived-confirmed-last-task-status")
        ui.onNodeWithTag("chat-archive").performClick();confirmed(2)
        menu();ui.onNodeWithTag("chat-lifecycle-status").assertTextContains("未归档",substring=true).assertTextContains("状态未确认",substring=true)
        shot("restored-lifecycle-without-invented-completion")
        assertEquals("separate unsent draft",session.text);assertEquals(2,fixture.posts.size)
    }
    @Test fun deleteRequiresChildWarningCancelHasNoPostAndConfirmTargetsExactChat() {
        val fixture=connect();val originalKey=session.targetKey
        ui.onNodeWithTag("message-input").performClick()
        menu();ui.onNodeWithTag("chat-delete").performClick()
        ui.onNodeWithText("将同步删除电脑端的原始聊天及其子对话，无法恢复。仅想保留历史时请选择归档。").assertIsDisplayed()
        ui.waitUntil(5_000) {
            var hidden=false
            Espresso.onView(isRoot()).inRoot(isDialog()).check { view,error ->
                if(error!=null)throw error
                assertEquals(InstrumentationRegistry.getInstrumentation().targetContext.packageName,view.context.packageName)
                hidden=ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())!=true
            };hidden
        }
        shot("delete-child-warning-keyboard-hidden")
        ui.onNodeWithTag("chat-delete-cancel").performClick();assertTrue(fixture.posts.isEmpty());assertEquals("separate unsent draft",session.text)
        menu();ui.onNodeWithTag("chat-delete").performClick();ui.onNodeWithTag("chat-delete-confirm").performClick();confirmed(1)
        assertEquals(1,fixture.posts.size)
        val request=org.json.JSONObject(String(fixture.posts.single().body));assertEquals("delete",request.getString("action"));assertEquals(ChatManagementFixture.first,request.getString("threadId"))
        ui.waitUntil(10_000) { session.selectedThread.isEmpty() }
        assertEquals("",session.selectedThread)
        val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        ui.waitUntil(5_000) { storage.metadata()?.optJSONObject("drafts")?.optString(originalKey)=="separate unsent draft" }
        shot("confirmed-delete-current-chat-exits-draft-retained")
    }
    @Test fun unknownReceiptEndWaitingAndQueryNeverReplayOrLockDraft() {
        val fixture=connect();fixture.phase="outcome-unknown"
        menu();ui.onNodeWithTag("chat-archive").performClick()
        ui.waitUntil(10_000) { session.chatActionRecords.any { it.phase=="outcome-unknown" } }
        val original=session.chatActionRecords.single()
        menu();ui.onNodeWithTag("chat-archive").assertIsNotEnabled();ui.onNodeWithTag("chat-delete").assertIsNotEnabled();ui.onNodeWithTag("chat-action-end-${original.id}").performClick()
        ui.onNodeWithText("仅解除本机等待，原操作仍可查询，不会取消电脑端操作或重新提交。").assertIsDisplayed();shot("unknown-operation-end-waiting-confirmation")
        ui.onNodeWithTag("chat-action-end-confirm").performClick();ui.waitUntil(5_000) { session.chatActionRecords.single().detached }
        main { session.text="editable newer draft" }
        fixture.getStatus=404;menu();ui.onNodeWithTag("chat-action-query-${original.id}").performClick()
        ui.waitUntil(10_000) { session.error!=null };ui.onNodeWithTag("session-error").assertTextContains("结果仍未确认；不会重新提交。",substring=true);shot("original-receipt-404-stays-unknown")
        ui.onNodeWithTag("dismiss-error").performClick();fixture.getStatus=200;fixture.phase="confirmed"
        menu();ui.onNodeWithTag("chat-action-query-${original.id}").performClick();confirmed(1)
        assertEquals(1,fixture.posts.size);assertEquals(original.intent,session.chatActionRecords.single().intent)
        assertEquals("editable newer draft",session.text);assertTrue(session.chatActionRecords.single().detached)
        shot("late-confirmed-original-operation-keeps-new-draft")
    }
    @Test fun allPendingOriginalRecordsRemainReachableBeyondFiveRows() {
        val extras=(3..6).map { "thread-actions-0$it" }
        val fixture=connect(prepare={ it.extraThreads=extras;it.phase="outcome-unknown" })
        val ids=listOf(ChatManagementFixture.first,ChatManagementFixture.other)+extras
        for((index,id)in ids.withIndex()) {
            main { session.selectThread(session.threads.first { it.id==id });session.text="editable draft $index" }
            ui.waitUntil(10_000) { session.canManageChat(session.currentChat) }
            main { session.manageChat("archive") }
            ui.waitUntil(10_000) { session.pendingChatActions().size==index+1&&session.chatActionRecords.all { it.phase=="outcome-unknown" } }
        }
        val records=session.pendingChatActions()
        // Unknown operations are also read by background receipt observers. End only
        // their local waiting, then establish a quiet read boundary so an unrelated
        // observer GET cannot be mistaken for the menu action being measured.
        main { records.forEach(session::endChatActionWaiting) }
        ui.waitUntil(10_000) { session.pendingChatActions().size==6&&session.pendingChatActions().all { it.detached } }
        var lastReads=fixture.server.requests.count { it.method=="GET"&&it.path=="/v1/threads/actions" }
        var quietSince=android.os.SystemClock.uptimeMillis()
        ui.waitUntil(10_000) {
            val count=fixture.server.requests.count { it.method=="GET"&&it.path=="/v1/threads/actions" }
            if(count!=lastReads) { lastReads=count;quietSince=android.os.SystemClock.uptimeMillis() }
            android.os.SystemClock.uptimeMillis()-quietSince>=1800
        }
        menu()
        assertEquals(records.size,records.map { session.chatActionRecordLabel(it) }.distinct().size)
        for(record in records)ui.onNodeWithTag("chat-action-query-${record.id}").performScrollTo().assertIsDisplayed()
            .assertTextContains(session.chatActionRecordLabel(record),substring=true).assertContentDescriptionEquals(session.chatActionRecordDescription(record))
        shot("all-six-original-pending-records-reachable")
        val original=records.first();val expected=ChatActionCodec.request(org.json.JSONObject(original.intent))
        val beforeQueryReads=fixture.server.requests.count { it.method=="GET"&&it.path=="/v1/threads/actions" }
        ui.onNodeWithTag("chat-action-query-${original.id}").performScrollTo()
        ui.waitForIdle();shot("first-original-query-target-after-scroll")
        if(InstrumentationRegistry.getArguments().getString("nativePointerDenied")=="1") {
            // Direct native coordinate injection was explicitly denied on this physical device.
            // Keep that touch gate unverified; dispatch only this app-owned semantics action.
            ui.onNodeWithTag("chat-action-query-${original.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        } else ui.onNodeWithTag("chat-action-query-${original.id}").performClick()
        ui.waitUntil(10_000) { fixture.server.requests.count { it.method=="GET"&&it.path=="/v1/threads/actions" }>beforeQueryReads }
        val observed=fixture.server.requests.filter { it.method=="GET"&&it.path=="/v1/threads/actions" }.drop(beforeQueryReads)
        assertEquals("One explicit query after all background waiters were detached",1,observed.size)
        val query=observed.single()
        assertTrue("Expected original thread ${expected.threadId}, actual ${query.pathWithQuery}",query.pathWithQuery.contains("threadId="+expected.threadId))
        assertTrue("Expected original operation ${expected.operationId}, actual ${query.pathWithQuery}",query.pathWithQuery.contains("id="+expected.operationId))
        assertEquals(6,fixture.posts.size);assertEquals("editable draft 5",session.text)
    }
    @Test fun authoritativeRefusalIsVisibleAndChangingTargetDismissesDeleteConfirmation() {
        val fixture=connect();menu();ui.onNodeWithTag("chat-delete").performClick()
        main { session.selectThread(session.threads.first { it.id==ChatManagementFixture.other });session.text="new target draft" }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-delete-confirm").fetchSemanticsNodes().isEmpty() }
        assertTrue(fixture.posts.isEmpty());fixture.postStatus=409
        menu();ui.onNodeWithTag("chat-archive").performClick()
        ui.waitUntil(10_000) { session.chatActionRecords.any { it.phase=="rejected" }&&session.error!=null }
        ui.onNodeWithTag("session-error").assertTextContains("请在桌面端操作",substring=true);shot("authoritative-rejected-operation-no-success")
        assertFalse(fixture.archived);assertEquals("new target draft",session.text);assertEquals(ChatManagementFixture.other,session.selectedThread)
        assertEquals(1,fixture.posts.size)
    }
}
