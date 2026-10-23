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
class MobileLiveQuestionsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val fixture by lazy { JSONObject(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"mobile-async-live-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String) {
        require(route in listOf("settings","continued","restart"))
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
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-async-live").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun input(text: String) {
        ui.waitUntil(45_000) {
            !session.historyLoading && session.canCompose &&
                ui.onAllNodesWithTag("message-input").fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled)==false
        }
        ui.onNodeWithTag("message-input").assertIsEnabled().performClick()
        ui.onNodeWithTag("message-input").assertIsFocused()
        ui.onNodeWithTag("message-input").performTextReplacement(text)
        ui.runOnIdle { assertEquals("Native field must retain the entered draft before sending",text,session.text) }
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
        ui.onNodeWithText(marker).assertIsDisplayed()
    }
    private fun asyncApprovals()=session.runtime?.approvals.orEmpty().filter { it.kind=="question" && it.interactionMode=="async" }
    private fun editable(tag: String,value: String) { ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(value))) }

    @Test fun unloadedOwnerSettingsAndActualAsyncAnswersSurviveHostRestart() {
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("原桌面异步验收",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient && !session.loading && session.threads.any { it.id==fixture.getString("threadId") } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
        ui.waitUntil(45_000) { !session.historyLoading && session.runtime!=null && session.models.isNotEmpty() }
        assertFalse("Persisted metadata must not imply a loaded owner",session.runtime!!.canControl==true)
        ui.onNodeWithTag("generation-settings").assertIsEnabled().performClick()
        ui.selectNativeGeneration(fixture.getString("model"),fixture.getString("effort"),fixture.getString("speed"),"full-access");shot("01-native-generation")
        ui.onNodeWithTag("sheet-done").performClick()
        input("安卓原生设置和主动打开验收：仅原样回复 SETTINGS_$nonce。不要调用工具、更改文件或其他聊天。")
        answer("SETTINGS_$nonce");assertTrue(session.runtime!!.canControl==true);control("settings");shot("02-unloaded-owner-opened")

        input("真实安卓异步问题验收。必须调用 request_user_input_async 一次提出三个问题：第一个标题为请选择交付格式，选项为简洁版和详细版；第二个标题为请输入验收暗号，不提供选项；第三个标题为可选的发布渠道，不提供选项。提问后不要等待回答，立即调用 exec_command 执行 printf 'ASYNC_CONTINUE_$nonce\\n'; sleep 100。独立工作完成后再读取异步回答；收到手机选择简洁版以及暗号 PHONE_$nonce 后仅回复 ASYNC_DONE_$nonce。第三题可以跳过，不要求回答；不要调用同步 request_user_input，不要修改项目文件，不要创建其他聊天。")
        ui.waitUntil(180_000) { asyncApprovals().size==3 }
        val original=asyncApprovals();val format=original[0].questions.single();val code=original[1].questions.single();val channel=original[2].questions.single()
        assertEquals("请选择交付格式",format.question);assertEquals("请输入验收暗号",code.question);assertEquals("可选的发布渠道",channel.question)
        ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 3")
        ui.onNodeWithTag("answer-question").assertIsNotEnabled();control("continued")
        ui.onNodeWithTag("async-question-next").performClick()
        val field="question-custom-${code.id}"
        ui.onNodeWithTag(field).performScrollTo().performTextInput("PHONE_$nonce");hideKeyboard()
        ui.onNodeWithTag("async-question-next").performClick();ui.onNodeWithTag("async-question-page").assertTextEquals("3 / 3")
        ui.onNodeWithTag("question-title-${channel.id}").performTouchInput { swipeRight() }
        ui.onNodeWithTag("async-question-page").assertTextEquals("2 / 3");editable(field,"PHONE_$nonce")
        ui.onNodeWithTag("async-question-prev").performClick();ui.onNodeWithTag("close-async-questions").performClick()
        ui.onNodeWithTag("answer-question").assertDoesNotExist();shot("03-collapsed-running-question")
        ui.onNodeWithTag("open-async-questions").performClick();ui.onNodeWithTag("question-option-${format.id}-简洁版").performClick()
        ui.onNodeWithTag("answer-question").performScrollTo().performClick()
        ui.waitUntil(30_000) { asyncApprovals().size==2 }
        ui.onNodeWithTag("async-question-page").assertTextEquals("1 / 2");editable(field,"PHONE_$nonce");shot("04-retained-real-question-draft")
        ui.onNodeWithTag("answer-question").performScrollTo().performClick();ui.waitUntil(30_000) { asyncApprovals().size==1 }
        ui.onNodeWithTag("question-title-${channel.id}").assertIsDisplayed()
        ui.onNodeWithTag("skip-question").performScrollTo().performClick();answer("ASYNC_DONE_$nonce")
        assertTrue(asyncApprovals().isEmpty());ui.onNodeWithTag("async-question-sheet").assertDoesNotExist();showAnswer("ASYNC_DONE_$nonce");shot("05-active-answers-completed")

        input("第二轮安卓异步专项：必须调用 request_user_input_async 提一个问题，标题为结束后继续对话，选项为继续验收和稍后处理。不要等回答，立即最终回复 ASYNC_IDLE_$nonce。之后收到回答继续验收时仅回复 ASYNC_FOLLOWUP_$nonce。")
        answer("ASYNC_IDLE_$nonce");ui.waitUntil(30_000) { asyncApprovals().size==1 }
        ui.onNodeWithTag("async-question-status").assertTextEquals("任务已结束，回答将继续此对话");shot("06-idle-followup-before-restart")
        val pending=asyncApprovals().single();control("restart")
        ui.runOnIdle { session.refresh(userInitiated=true) };ui.waitUntil(45_000) { !session.loading }
        ui.runOnIdle { session.loadHistory(userInitiated=true) };ui.waitUntil(45_000) { !session.historyLoading && asyncApprovals().singleOrNull()?.id==pending.id && session.canAnswer(pending) }
        ui.onNodeWithTag("question-option-${pending.questions.single().id}-继续验收").performClick()
        ui.onNodeWithTag("answer-question").performScrollTo().performClick();answer("ASYNC_FOLLOWUP_$nonce")
        assertTrue(asyncApprovals().isEmpty());showAnswer("ASYNC_FOLLOWUP_$nonce");shot("07-restart-followup-completed")
    }
}
