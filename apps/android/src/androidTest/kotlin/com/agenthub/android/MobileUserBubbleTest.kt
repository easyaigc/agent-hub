package com.agenthub.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual text layout and native control state; full touch/selection geometry remains P6. */
@RunWith(AndroidJUnit4::class)
class MobileUserBubbleTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private val session get()=ui.activity.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun lines(count: Int)=(1..count).joinToString("\n") { "line $it" }
    private fun setup(text: String=lines(12)) {
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId","bubble-fixture").put("name","synthetic user bubble").put("connectionMode","history-only").put("projects",JSONArray())
                .put("threads",JSONArray(listOf("bubble-a","bubble-b").map { JSONObject().put("id",it).put("hostId","bubble-fixture").put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject().put("hostId",client.id).put("threadId",threadId)
                .put("messages",JSONArray().put(JSONObject().put("id","shared-user").put("role","user").put("text",text).put("files",JSONArray().put("synthetic-note.txt")))).toString())
        }
        ui.runOnIdle { ui.activity.readNetworkMonitor.stop();session.resetAcceptance(reader) }
        main { session.connect("synthetic user bubble","https://fixture.invalid","") }
        ui.runOnIdle { session.selectThread(session.threads.first());session.text="unsubmitted bubble draft" }
        ui.waitUntil(5_000) { !session.historyLoading&&session.messages.size==1 }
    }
    private fun layout(text: String): TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
    private fun image(name: String) {
        ui.waitForIdle()
        val pixels=ui.onRoot().captureToImage().asAndroidBitmap()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.easyai.agenthub.android.acceptance")
        val folder=File(context.getExternalFilesDir(null),"acceptance/P2-bubbles").apply { mkdirs() }
        try { folder.resolve("$name.png").outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { pixels.recycle() }
    }
    private fun toggle() { ui.onNodeWithTag("user-message-toggle-shared-user").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) } }
    @After fun stop() { ui.runOnIdle { session.leaveForeground() } }
    @Test fun longUserBodyStartsWithSixRenderedLinesAndKeepsOriginalAndAttachments() {
        val original=lines(12);setup(original);assertEquals(6,layout(original).lineCount);assertTrue(layout(original).isLineEllipsized(5))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("user-message-toggle-shared-user").fetchSemanticsNodes().size==1 }
        ui.onNodeWithText("展开").assertExists();ui.onNodeWithText("文件 · synthetic-note.txt").assertExists()
        assertEquals(original,session.messages.single().text);assertEquals(listOf("synthetic-note.txt"),session.messages.single().files);assertEquals("unsubmitted bubble draft",session.text);image("collapsed-user-bubble")
    }
    @Test fun semanticExpandAndCollapseKeepExactOriginalTextAndDraft() {
        val original=lines(12);setup(original);toggle();assertEquals(12,layout(original).lineCount);ui.onNodeWithText("收起").assertExists();image("expanded-user-bubble")
        toggle();assertEquals(6,layout(original).lineCount);assertEquals(original,session.messages.single().text);assertEquals("unsubmitted bubble draft",session.text)
    }
    @Test fun exactlySixLinesNeedNoToggleAndSeventhLineRequiresOne() {
        val six=lines(6);setup(six);assertEquals(6,layout(six).lineCount);ui.onNodeWithTag("user-message-toggle-shared-user").assertDoesNotExist()
        val seven=lines(7);ui.runOnIdle { session.installAcceptanceHistory(listOf(MobileMessage("shared-user","user",seven))) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("user-message-toggle-shared-user").fetchSemanticsNodes().size==1 }
        assertEquals(6,layout(seven).lineCount);assertEquals(seven,session.messages.single().text)
    }
    @Test fun sameMessageIdOnAnotherTargetStartsCollapsedAndKeepsSeparateDraft() {
        val original=lines(12);setup(original);toggle();assertEquals(12,layout(original).lineCount)
        ui.runOnIdle { session.selectThread(session.threads.last()) };ui.waitUntil(5_000) { !session.historyLoading }
        assertEquals(6,layout(original).lineCount);ui.onNodeWithText("展开").assertExists();assertEquals("",session.text);assertEquals("bubble-b",session.selectedThread)
    }
    @Test fun largeUnicodeBodyKeepsCompleteSourceWithBoundedInitialLayout() {
        val original=buildString { repeat(4096) { append("原文🙂 line ") } };setup(original)
        assertEquals(6,layout(original).lineCount);assertTrue(layout(original).hasVisualOverflow)
        ui.onNodeWithTag("user-message-toggle-shared-user").assertExists()
        assertEquals(original,session.messages.single().text);assertEquals("unsubmitted bubble draft",session.text);assertTrue(session.operationRecords.isEmpty())
    }

}
