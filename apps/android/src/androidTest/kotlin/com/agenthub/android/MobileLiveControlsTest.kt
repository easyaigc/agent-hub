package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Opt-in native examiner. All responses and model turns come from the original desktop. */
@RunWith(AndroidJUnit4::class)
class MobileLiveControlsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val fixture by lazy { JSONObject(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"mobile-controls-live-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String) {
        require(route in listOf("queue-ready","queue-done","stop-ready","stopped","resumed","restarted"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http" && base.host=="127.0.0.1" && base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=JSONObject().put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Independent original owner gate",200,http.responseCode);http.inputStream.close()
        } finally { http.disconnect() }
    }
    private fun hideKeyboard() {
        ui.waitForIdle()
        if(androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        ui.waitUntil(10_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
    }
    private fun shot(name: String) {
        hideKeyboard();ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync();i.uiAutomation.waitForIdle(1000,10_000)
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-controls-live").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun input(text: String,mode: String?=null) {
        ui.waitUntil(45_000) {
            !session.historyLoading && session.canCompose &&
                ui.onAllNodesWithTag("message-input").fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled)==false
        }
        ui.onNodeWithTag("message-input").assertIsEnabled().performClick()
        ui.onNodeWithTag("message-input").assertIsFocused()
        ui.onNodeWithTag("message-input").performTextReplacement(text)
        ui.runOnIdle { assertEquals("Native field must retain the entered draft before sending",text,session.text) }
        if(mode!=null) {
            chooseMode(mode)
            ui.runOnIdle { assertEquals("Mode selection must preserve the exact unsent draft",text,session.text) }
        }
        try { ui.waitUntil(15_000) { session.decision.action=="send" && session.decision.enabled } }
        catch(failure: ComposeTimeoutException) {
            shot("failure-input")
            throw AssertionError("Native input unavailable: textMatches=${session.text==text}, action=${session.decision.action}, enabled=${session.decision.enabled}, online=${session.selectedClient.online}, loading=${session.historyLoading}, running=${session.running}, canCompose=${session.canCompose}, pending=${session.transferStatus}, error=${session.error}",failure)
        }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
    }
    private fun answer(marker: String) {
        try { ui.waitUntil(240_000) { session.messages.any { it.role=="assistant" && it.text.trim()==marker } && !session.running && session.transferStatus==null } }
        catch(failure: ComposeTimeoutException) {
            shot("failure-answer")
            val phases=session.operationRecords.map { it.kind+":"+it.phase }
            throw AssertionError("Native answer unavailable: canControl=${session.runtime?.canControl}, state=${session.runtimeState}, pending=${session.transferStatus}, operationPhases=$phases, error=${session.error}",failure)
        }
    }
    private fun showAnswer(marker: String) {
        hideKeyboard()
        if(ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("scroll-latest").performClick()
        ui.onAllNodesWithText(marker).onLast().assertIsDisplayed()
    }

    private fun marker(suffix: String)="CONTROLS_${nonce}_$suffix"
    private fun receipts()=session.runtime?.receipts.orEmpty()
    private fun chooseMode(mode: String) {
        hideKeyboard()
        try { ui.waitUntil(45_000) { session.running && session.canCompose && session.canSelectReplyMode && session.transferStatus==null && mode in session.replyModes && ui.onAllNodesWithTag("reply-mode").fetchSemanticsNodes().isNotEmpty() } }
        catch(failure: ComposeTimeoutException) { shot("failure-mode");throw AssertionError("Native mode unavailable: running=${session.running}, canCompose=${session.canCompose}, online=${session.selectedClient.online}, state=${session.runtimeState}, error=${session.error}",failure) }
        ui.onNodeWithTag("reply-mode").assertIsDisplayed()
        ui.onNodeWithTag("reply-mode-$mode").assertIsDisplayed().assertIsEnabled().performClick();assertEquals(mode,session.replyMode)
        ui.onNodeWithTag("reply-mode-$mode").assertIsSelected()
    }
    private fun queue(index: Int) {
        input("仅原样回复 ${marker("QUEUE_$index")}，不加格式，不调用工具。",mode="queue")
        ui.waitUntil(30_000) { session.queuedReplies.size==index && session.transferStatus==null }
    }
    @Test fun originalOwnerQueueSteerCancelStopResumeAndExplicitRestart() {
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("原桌面任务控制验收",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient && !session.loading && session.threads.any { it.id==fixture.getString("threadId") } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
        ui.waitUntil(45_000) { !session.historyLoading && session.runtime!=null && session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick()
        ui.selectNativeGeneration(fixture.getString("model"),fixture.getString("effort"),fixture.getString("speed"),"full-access");ui.onNodeWithTag("sheet-done").performClick()
        input("专用安卓原生输入框验收。调用 exec_command 执行 sleep 120，yield_time_ms=1000，不读写文件。允许用 write_stdin 等待这一个命令自然结束，然后最终仅回复 ${marker("ORIGIN")}。期间收到追加指令时采纳它，但仍等待原命令自然结束。")
        ui.waitUntil(45_000) { session.running && session.runtime?.turnId!=null && session.transferStatus==null }
        val originalTurn=session.runtime!!.turnId
        queue(1);queue(2);queue(3)
        val queued=session.queuedReplies.toList();assertEquals(3,queued.size)
        queued.forEachIndexed { index,receipt -> ui.onNodeWithTag("queued-number-${receipt.operationId}").performScrollTo().assertTextEquals("${index+1}") }
        shot("01-numbered-native-queue");control("queue-ready")
        ui.onNodeWithTag("cancel-queued-${queued[1].operationId}").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(30_000) { session.queuedReplies.size==2 && receipts().any { it.operationId==queued[1].operationId && it.state=="cancelled" } }
        ui.onNodeWithTag("queued-number-${queued[2].operationId}").performScrollTo().assertTextEquals("2");shot("02-middle-cancel-renumbered")
        input("追加指令：本轮最后的回复改为 ${marker("STEER")}。允许继续用 write_stdin 等待当前 sleep 命令自然结束；完成后仅回复指定字符串。不停止命令，不执行其他命令。",mode="steer")
        ui.waitUntil(30_000) { receipts().any { it.text?.contains(marker("STEER"))==true && it.turnId==originalTurn && it.state in listOf("submitted","completed") } }
        shot("03-same-turn-native-steer")
        answer(marker("QUEUE_3"));control("queue-done")
        assertTrue(session.queuedReplies.isEmpty());ui.onNodeWithTag("stop-turn").assertDoesNotExist()
        ui.onNodeWithTag("send-message").assertIsNotEnabled();ui.onNodeWithTag("resume-task").assertDoesNotExist()
        showAnswer(marker("QUEUE_3"));shot("04-fifo-completed-empty-input")

        input("专用安卓停止和恢复验收。先调用 exec_command 执行 sleep 120，yield_time_ms=1000，不读写文件；允许等待这一个命令。自然结束最终只回复 ${marker("STOP_ORIGIN")}。若此任务被中断，后续收到继续完成上一个任务或重新运行上一个任务时，只回复 ${marker("RECOVER")}，无需再调用工具。不要创建其他聊天。")
        ui.waitUntil(45_000) { session.running && session.runtime?.turnId!=null && session.transferStatus==null }
        control("stop-ready");hideKeyboard();ui.onNodeWithTag("stop-turn").assertIsEnabled().performClick()
        ui.waitUntil(45_000) { !session.running && session.transferStatus==null && session.decision.action=="resume" }
        control("stopped");shot("05-interrupted-native-resume")
        ui.onNodeWithTag("resume-task").assertIsEnabled().performClick();answer(marker("RECOVER"));control("resumed")
        assertTrue(session.canRunAgain);shot("06-resumed-native-context")
        ui.onNodeWithTag("send-message").performTouchInput { longClick() }
        ui.onNodeWithTag("action-restart").assertIsDisplayed().performClick()
        ui.waitUntil(240_000) { session.messages.count { it.role=="assistant" && it.text.trim()==marker("RECOVER") }==2 && !session.running && session.transferStatus==null }
        ui.waitUntil(30_000) { session.operationRecords.filter { it.kind=="reply" && it.phase=="interrupted" }.all { saved -> session.activeOperationRecords.none { it.id==saved.id } } }
        control("restarted");showAnswer(marker("RECOVER"));shot("07-explicit-native-restart")
        ui.onNodeWithTag("send-message").assertIsNotEnabled();assertTrue(session.queuedReplies.isEmpty())
    }
}
