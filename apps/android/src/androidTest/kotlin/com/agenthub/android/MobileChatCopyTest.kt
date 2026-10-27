package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Display language never changes original named action identity, receipt or raw title. */
class MobileChatCopyTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val args get()=InstrumentationRegistry.getArguments()
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"chat-copy");session.resetAcceptance()
            session.changeLanguage("en");session.changeAppearance(args.getString("viewportTheme") ?: "light") }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    private fun connect(f: ChatManagementFixture) {
        f.firstTitle="设置 100% %@"
        main { session.connect("管理 100% %@",f.server.endpoint,"");session.selectThread(session.threads.first { it.id==ChatManagementFixture.first }) }
        ui.waitUntil(10_000) { session.canManageChat(session.currentChat)&&!session.historyLoading }
        main { session.text="Independent original draft 100% %@" }
    }
    /** Native SDK composite is cropped to the same exact owned active AX window before/after. */
    private fun menuShot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val root=checkNotNull(i.uiAutomation.rootInActiveWindow)
        assertEquals(i.targetContext.packageName,root.packageName?.toString())
        val rect=Rect();root.getBoundsInScreen(rect);val windowId=root.windowId
        val whole=checkNotNull(i.uiAutomation.takeScreenshot())
        try {
            val after=checkNotNull(i.uiAutomation.rootInActiveWindow);val afterRect=Rect();after.getBoundsInScreen(afterRect)
            assertEquals(i.targetContext.packageName,after.packageName?.toString());assertEquals(windowId,after.windowId);assertEquals(rect,afterRect)
            assertTrue(rect.left>=0&&rect.top>=0&&rect.right<=whole.width&&rect.bottom<=whole.height&&!rect.isEmpty)
            val image=Bitmap.createBitmap(whole,rect.left,rect.top,rect.width(),rect.height())
            try { File(owned.folder,name+".png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { if(image!==whole)image.recycle() }
            File(owned.folder,name+"-window.json").writeText(JSONObject().put("method","UiAutomation.screenshot.owned-active-root-crop")
                .put("package",i.targetContext.packageName).put("windowId",windowId).put("bounds",rect.toShortString()).toString())
        } finally { whole.recycle() }
    }
    @Test fun pendingActionLanguageKeepsOriginalTitleIdsAndReadOnlyQuery() {
        ChatManagementFixture().use { f ->
            f.phase="outcome-unknown";connect(f);val draft=session.text
            main { session.manageChat("archive") }
            ui.waitUntil(10_000) { session.chatActionRecords.any { it.phase=="outcome-unknown" } }
            val record=session.chatActionRecords.single();val request=ChatActionCodec.request(JSONObject(record.intent))
            main { session.endChatActionWaiting(record) }
            ui.waitUntil(10_000) { session.chatActionRecords.single().detached }
            ui.onNodeWithTag("chat-menu").performClick()
            val query=ui.onNodeWithTag("chat-action-query-"+record.id).performScrollTo()
            query.assertTextContains("Archive · "+request.operationId.take(8)+" · 设置 100% %@")
                .assertContentDescriptionEquals("Query conversation action receipt, Archive · "+request.operationId.take(8)+" · 设置 100% %@, conversation "+request.threadId+", operation "+request.operationId)
            menuShot("english-original-action")
            main { session.changeLanguage("zh-Hans") }
            query.performScrollTo().assertTextContains("归档 · "+request.operationId.take(8)+" · 设置 100% %@")
                .assertContentDescriptionEquals("查询聊天操作记录，归档 · "+request.operationId.take(8)+" · 设置 100% %@，对话 "+request.threadId+"，操作 "+request.operationId)
            val reads=f.server.requests.count { it.method=="GET"&&it.path=="/v1/threads/actions" }
            query.performClick();ui.waitUntil(10_000) { f.server.requests.count { it.method=="GET"&&it.path=="/v1/threads/actions" }>reads }
            assertEquals(1,f.posts.size);assertEquals(record.intent,session.chatActionRecords.single().intent)
            assertEquals(record.intentSha256,session.chatActionRecords.single().intentSha256);assertEquals(draft,session.text)
            File(owned.folder,"original-action-proof.json").writeText(JSONObject().put("originalIntentPreserved",true).put("rawTitlePreserved",true)
                .put("oneSyntheticOriginalPost",true).put("queryReadOnly",true).put("languageDoesNotReplay",true).toString())
        }
    }
    @Test fun archivedSavedFailureAndRestoredUnknownStaySeparateInBothLanguages() {
        ChatManagementFixture().use { f ->
            f.archived=true;f.state="unknown";f.lastTurnStatus="failed";connect(f)
            ui.onNodeWithTag("chat-menu").performClick()
            ui.onNodeWithTag("chat-lifecycle-status").assertTextEquals("Archived · Task status: Failed")
            ui.onNodeWithTag("archived-chats").performClick()
            val row=ui.onNodeWithTag("archived-chat-"+ChatManagementFixture.first).performScrollTo().assertIsDisplayed().assertTextContains("设置 100% %@")
            row.assertTextContains("Archived · Task status: Failed")
            owned.dialogShot("english-archived-prior-failure")
            main { session.changeLanguage("zh-Hans") }
            row.assertTextContains("设置 100% %@")
            row.assertTextContains("已归档 · 任务状态：执行失败")
            ui.onNodeWithTag("sheet-done").performClick()
            f.archived=false;f.lastTurnStatus=null
            main { session.refresh() };ui.waitUntil(10_000) { !session.loading&&session.currentChat?.archived==false }
            ui.onNodeWithTag("chat-menu").performClick()
            ui.onNodeWithTag("chat-lifecycle-status").assertTextContains("未归档 · 任务状态：状态未确认")
            assertTrue(f.posts.isEmpty());menuShot("restored-is-not-completed")
        }
    }
}
