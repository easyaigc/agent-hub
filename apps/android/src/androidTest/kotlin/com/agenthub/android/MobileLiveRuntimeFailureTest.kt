package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File

/** Original desktop must fail a real Turn, then execute one explicitly submitted recovery. */
@RunWith(AndroidJUnit4::class)
class MobileLiveRuntimeFailureTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val limit = Timeout.seconds(300)

    @Test fun actualFailedTurnDismissalHistoryAndRecovery() {
        val i = InstrumentationRegistry.getInstrumentation()
        val config = JSONObject(File(InstrumentationRegistry.getArguments().getString("runtimeFailureConfig") ?: error("Controlled original-owner examiner required")).readText())
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var main: MainActivity? = null
        ui.waitUntil(20_000) { i.runOnMainSync { main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() }; main != null }
        val activity = main!!
        val session = activity.session
        val folder = File(i.targetContext.getExternalFilesDir(null), "acceptance/A4-runtime-failure").apply { mkdirs() }
        fun hideKeyboard() { i.runOnMainSync { androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }; ui.waitForIdle() }
        fun shot(name: String) { hideKeyboard(); i.uiAutomation.waitForIdle(500, 10_000); val bitmap = i.uiAutomation.takeScreenshot(); try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() } }
        fun dismissError() { ui.waitForIdle(); if (ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("dismiss-error").performClick(); ui.waitUntil(15_000) { session.error == null } }
        i.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            ui.runOnIdle { session.resetAcceptance() }
            runBlocking { withContext(Dispatchers.Main) { session.connect("真实任务失败验收", config.getString("endpoint"), config.getString("token")) } }
            val thread = config.getString("threadId")
            val nonce = config.getString("nonce")
            ui.waitUntil(60_000) { session.hasClient && !session.loading && session.threads.any { it.id == thread } }
            ui.runOnIdle { session.selectThread(session.threads.single { it.id == thread }); session.retryModels() }
            ui.waitUntil(60_000) { !session.historyLoading && session.runtime?.canControl == true && session.models.isNotEmpty() }
            val normal = session.models.single { it.isDefault }
            ui.runOnIdle { session.configure(GenerationSettings(model = normal.id, effort = "low", speed = "standard", permissionMode = "read-only", collaborationMode = "default")) }
            val text = "专用连接失败验收 $nonce：只回复 FAULT_$nonce，不调用工具或修改文件。"
            ui.onNodeWithTag("message-input").performTextReplacement(text)
            ui.waitUntil(15_000) { session.decision.enabled }
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(120_000) { !session.running && session.transferStatus == null && session.runtime?.receipts?.any { it.text == text && it.state in listOf("failed", "completed") } == true }
            val failed = session.runtime!!.receipts.single { it.text == text }
            File(folder, "native-fault.json").writeText(JSONObject().put("threadId", thread).put("operationId", failed.operationId).put("turnId", failed.turnId).put("state", failed.state).toString())
            assertFalse("Submission rejection cannot substitute for an executed Turn", failed.turnId.isNullOrBlank())
            assertEquals("Original owner must report a genuine failed Turn", "failed", failed.state)
            assertFalse(failed.error.isNullOrBlank())
            assertEquals("", session.text)
            dismissError(); hideKeyboard()
            ui.onNodeWithTag("receipt-${failed.operationId}").assertIsDisplayed()
            ui.onAllNodesWithText("任务执行失败").assertCountEquals(1)
            ui.onNodeWithTag("operation-${failed.operationId}").assertDoesNotExist()
            shot("actual-runtime-failed-turn")
            ui.onNodeWithTag("dismiss-receipt-${failed.operationId}").performClick()
            ui.onNodeWithTag("receipt-${failed.operationId}").assertDoesNotExist()
            val draft = "失败后的独立本地草稿🙂"
            ui.onNodeWithTag("message-input").performTextReplacement(draft)
            val replacement = MobileSession(NativeSessionStorage(i.targetContext))
            ui.runOnIdle { replacement.restore() }
            ui.waitUntil(60_000) { replacement.hasClient && replacement.runtime != null && !replacement.historyLoading }
            assertEquals(thread, replacement.selectedThread)
            assertEquals(draft, replacement.text)
            assertTrue(replacement.receiptNotices.none { it.operationId == failed.operationId })
            ui.runOnIdle { replacement.leaveForeground() }
            shot("actual-dismissed-failure-restored-draft")
            val marker = "RECOVERED_$nonce"
            val recoveryText = "专用连接恢复验收 $nonce：只回复 $marker，不调用工具或修改文件。"
            ui.onNodeWithTag("message-input").performTextReplacement(recoveryText)
            ui.waitUntil(15_000) { session.decision.enabled }
            ui.onNodeWithTag("send-message").performClick()
            ui.waitUntil(120_000) { !session.running && session.transferStatus == null && session.runtime?.receipts?.any { it.text == recoveryText && it.state in listOf("completed", "failed") } == true }
            assertEquals(thread, session.selectedThread)
            val recovered = session.runtime!!.receipts.single { it.text == recoveryText }
            assertEquals("Recovery must execute successfully: ${recovered.error}", "completed", recovered.state)
            assertTrue(session.messages.any { it.role == "assistant" && it.text.trim() == marker })
            assertFalse(recovered.turnId.isNullOrBlank())
            ui.waitUntil(30_000) { session.runtime?.receipts?.any { it.operationId == failed.operationId && it.state == "failed" && it.supersededByTurnId == recovered.turnId } == true }
            assertTrue(session.receiptNotices.none { it.operationId == failed.operationId })
            hideKeyboard()
            ui.onNodeWithTag("message-list").performScrollToNode(hasText(marker))
            ui.onNodeWithText(marker).assertIsDisplayed()
            shot("actual-runtime-recovered-reply")
            val tag = "task-failure-history-turn-failure-${failed.turnId}"
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag))
            ui.onNodeWithTag(tag).assertIsDisplayed()
            shot("actual-historical-failure-after-recovery")
            File(folder, "native-proof.json").writeText(JSONObject().put("threadId", thread).put("failedOperation", failed.operationId).put("failedTurn", failed.turnId).put("error", failed.error).put("recoveredOperation", recovered.operationId).put("recoveredTurn", recovered.turnId).put("recoveredMarker", marker).put("dismissedRestored", true).put("draftRestored", true).put("historicalFailurePreserved", true).toString())
        } catch (failure: Throwable) { runCatching { shot("failed-native") }.exceptionOrNull()?.let { failure.addSuppressed(it) }; throw failure }
        finally { i.runOnMainSync { session.leaveForeground(); if (!activity.isDestroyed) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    }
}
