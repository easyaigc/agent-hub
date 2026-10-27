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
class MobileLivePlanApprovalTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val session get()=ui.activity.session
    private val fixture by lazy { JSONObject(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"mobile-plan-approval-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String) {
        require(route in listOf("initial-plan","revised-plan","implemented","denial-ready","denied","allow-ready","allowed"))
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
        val folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/A4-plan-approval-live").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
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
        ui.onAllNodesWithText(marker).onLast().assertIsDisplayed()
    }


    private fun marker(suffix: String)="PLAN_APPROVAL_${nonce}_$suffix"
    private fun settings(permission: String) {
        ui.waitUntil(45_000) { session.canConfigureGeneration }
        ui.onNodeWithTag("generation-settings").performClick()
        ui.selectNativeGeneration(fixture.getString("model"),fixture.getString("effort"),fixture.getString("speed"),permission);ui.onNodeWithTag("sheet-done").performClick()
    }
    private fun approvals()=session.runtime?.approvals.orEmpty().filter { it.kind!="question" }
    private fun approve(allowed: Boolean) {
        ui.waitUntil(180_000) { approvals().size==1 }
        val request=approvals().single();val choice=request.decisions.firstOrNull { it in (if(allowed) listOf("accept","approve") else listOf("decline","deny")) } ?: if(!allowed && "cancel" in request.decisions) "cancel" else null
        assertNotNull("Actual original owner offers the expected decision",choice)
        assertTrue(session.canAnswer(request));control(if(allowed) "allow-ready" else "denial-ready")
        shot(if(allowed) "05-real-permission-allow" else "04-real-permission-deny")
        ui.onNodeWithTag("approval-$choice").performScrollTo().assertIsEnabled().performClick()
        if(choice=="cancel") ui.waitUntil(45_000) { !session.running && session.transferStatus==null && session.decision.action=="resume" }
        else answer(marker(if(allowed) "ALLOW_DONE" else "DENY_DONE"))
        assertTrue(approvals().isEmpty())
        control(if(allowed) "allowed" else "denied")
    }
    @Test fun actualPlanRevisionImplementationAndToolApprovalDecisions() {
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("原桌面计划审批验收",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient && !session.loading && session.threads.any { it.id==fixture.getString("threadId") } }
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
        ui.waitUntil(45_000) { !session.historyLoading && session.runtime!=null && session.models.isNotEmpty() }
        if(fixture.optBoolean("pendingOnly")) {
            approve(false);settings("read-only")
            input("专属安卓允许审批验收：调用 exec_command 执行 printf '${marker("ALLOW_TOOL")}'，必须设置 sandbox_permissions=require_escalated，justification=验证安卓允许审批。命令仅打印，不读写文件。等用户审批，批准后执行；最终仅回复 ${marker("ALLOW_DONE")}。")
            approve(true);showAnswer(marker("ALLOW_DONE"));shot("06-permission-outcomes-completed")
            ui.onNodeWithTag("send-message").assertIsNotEnabled();return
        }
        settings("full-access")
        ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick()
        assertEquals("plan",session.selectedCollaboration)
        input("专属安卓真实计划验收：制定仅含一步的计划，正文要求最终仅回复 ${marker("IMPLEMENTED")}，不要调用工具、修改文件或提问。本轮仅提出计划，不能实施。使用 proposed_plan 格式使原桌面显示待实施计划，正文包含 ${marker("INITIAL")}。")
        ui.waitUntil(180_000) { session.runtime?.proposedPlan!=null && !session.running && session.transferStatus==null }
        val initial=session.runtime!!.proposedPlan!!;assertTrue(initial.text.contains(marker("INITIAL")))
        ui.onNodeWithTag("implement-plan").performScrollTo().assertIsEnabled();control("initial-plan");shot("01-real-proposed-plan")
        ui.onNodeWithTag("message-input").performClick().performTextReplacement("另一份可编辑草稿 $nonce")
        hideKeyboard();ui.onNodeWithTag("revise-plan").performScrollTo().performClick()
        ui.onNodeWithTag("plan-feedback").performScrollTo().performTextInput("请修订计划：保留最终只回复 ${marker("IMPLEMENTED")} 这一步，删除旧版标识，改为正文包含 ${marker("REVISED")}。仍然只提出待实施计划，不调用工具、不实施。")
        hideKeyboard();ui.onNodeWithTag("send-plan-feedback").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.runtime?.proposedPlan?.id?.let { it!=initial.id }==true && !session.running && session.transferStatus==null }
        val revised=session.runtime!!.proposedPlan!!;assertTrue(revised.text.contains(marker("REVISED")));assertEquals("另一份可编辑草稿 $nonce",session.text)
        control("revised-plan");shot("02-real-revised-plan")
        ui.onNodeWithTag("implement-plan").performScrollTo().assertIsEnabled().performClick()
        answer(marker("IMPLEMENTED"));assertEquals("另一份可编辑草稿 $nonce",session.text)
        assertEquals("default",session.selectedCollaboration);assertNull(session.runtime!!.proposedPlan)
        control("implemented");showAnswer(marker("IMPLEMENTED"));shot("03-implemented-retains-native-draft")
        ui.onNodeWithTag("message-input").performClick().performTextClearance();hideKeyboard()
        settings("read-only")
        input("专属安卓拒绝审批验收：调用 exec_command 执行 printf '${marker("DENY_TOOL")}'，必须设置 sandbox_permissions=require_escalated，justification=验证安卓拒绝审批。命令仅打印，不读写文件。等用户审批；若用户拒绝，不得重试或改用其他工具，最终仅回复 ${marker("DENY_DONE")}。")
        approve(false)
        settings("read-only")
        input("专属安卓允许审批验收：调用 exec_command 执行 printf '${marker("ALLOW_TOOL")}'，必须设置 sandbox_permissions=require_escalated，justification=验证安卓允许审批。命令仅打印，不读写文件。等用户审批，批准后执行；最终仅回复 ${marker("ALLOW_DONE")}。")
        approve(true);showAnswer(marker("ALLOW_DONE"));shot("06-permission-outcomes-completed")
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
    }
}
