package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** The current task's actual owner history, through an isolated GET-only Host gate. */
@RunWith(AndroidJUnit4::class)
class MobileLiveHistoryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun actualOwnerCompactionAppearsAtItsOriginalHistoryPosition() {
        val i=InstrumentationRegistry.getInstrumentation(); val p=InstrumentationRegistry.getArguments().getString("historyConfig") ?: error("Select the exact independent owner proof")
        val config=JSONObject(File(p).readText()); val thread=config.getString("threadId");val id=config.getString("compactionId");val session=ui.activity.session
        try {
        ui.runOnIdle { session.resetAcceptance() }
        runBlocking { withContext(Dispatchers.Main) { session.connect("原桌面只读压缩验收",config.getString("endpoint"),config.getString("token")) } }
        ui.waitUntil(90_000) { session.hasClient && !session.loading }
        val target=session.threads.single { it.id==thread };ui.runOnIdle { session.selectThread(target) }
        ui.waitUntil(90_000) { !session.historyLoading && session.messages.any { it.id==id && it.kind=="contextCompaction" && it.status=="completed" } }
        assertEquals(config.getString("turnId"),session.runtime!!.turnId)
        // Close only the local presentation; never answer or skip an actual owner question.
        if(ui.onAllNodesWithTag("close-async-questions").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithTag("close-async-questions").performClick()
            ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
        }
        for(attempt in 0 until 64) {
            if(session.timeline.rows.any { it.kind=="compaction" && it.id==id }) break
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("history-local-more"));ui.onNodeWithTag("history-local-more").performClick();ui.waitForIdle()
        }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("compaction-$id"))
        ui.onNodeWithTag("compaction-$id").assertIsDisplayed().assertTextEquals("已压缩上下文")
        ui.onNodeWithTag("message-input").performTextInput("仅保存在压缩验收模拟器的草稿")
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitForIdle();i.uiAutomation.waitForIdle(700,10_000)
        ui.onNodeWithTag("async-question-sheet").assertDoesNotExist()
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("compaction-$id"))
        ui.onNodeWithTag("compaction-$id").assertIsDisplayed()
        val dir=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-history").apply { mkdirs() };val bitmap=i.uiAutomation.takeScreenshot()
        try { File(dir,"actual-compaction-history.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        assertEquals("仅保存在压缩验收模拟器的草稿",session.text)
        // Release the live snapshot before an independent full disk decode,
        // matching a new session rather than retaining two large histories.
        val cachedTarget=session.targetKey
        ui.runOnIdle { session.resetAcceptance() };ui.waitForIdle()
        val cache=runBlocking(Dispatchers.IO) {
            val storage=NativeSessionStorage(i.targetContext); var value: MobileHistory?=null
            withTimeout(90_000) { while(value==null) { value=NativeHistoryCache(storage).read(cachedTarget); if(value==null) delay(300) } };value!!
        }
        assertTrue(cache.messages.any { it.id==id && it.kind=="contextCompaction" && it.status=="completed" });val cachedMessages=cache.messages.size
        assertTrue(cachedMessages>=config.getInt("minimumMessages"))
        File(dir,"native-proof.json").writeText(JSONObject().put("cachedMessages",cachedMessages).put("compactionPreserved",true).put("fullSnapshotPersisted",true).toString())
        } catch(failure: Throwable) {
            val dir=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-history").apply { mkdirs() }
            File(dir,"native-failure.json").writeText(JSONObject().put("error",session.error ?: JSONObject.NULL).put("messages",session.messages.size).put("loading",session.historyLoading).put("runtimeState",session.runtimeState).put("cause",failure.message).toString())
            val bitmap=i.uiAutomation.takeScreenshot();try { File(dir,"failed-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() };throw failure
        }
    }
}
