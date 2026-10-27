package com.agenthub.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Native product window, real session/HTTP/Keystore, only synthetic plan inputs. */
class MobilePlanUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val failure=newFailureWatcher()
    private fun newFailureWatcher()=object: TestWatcher() { override fun failed(error: Throwable,description: Description) { runCatching { shot("failed-${description.methodName}") } } }
    private val session get()=ui.activity.session
    private val body="完整计划正文 中文 😀\nInspect input, then implement."
    private fun proposal(text: String=body,turn: String="plan-turn")=JSONObject().put("id","plan-id").put("turnId",turn).put("text",text)
    private fun message(status: String="completed")=JSONObject().put("id","plan-message").put("role","assistant").put("kind","plan").put("text",body).put("status",status)
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() { ui.runOnIdle { session.resetAcceptance() } }
    private fun connect(f: ExecutionFixture) {
        main { session.connect("synthetic plan parity",f.server.endpoint,"") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.runtime!=null&&session.models.isNotEmpty() }
        main { allowSyntheticRegressionSharing(session) }
    }
    private fun read() { main { session.loadHistory() };ui.waitUntil(10_000) { !session.historyLoading } }
    private fun posts(f: ExecutionFixture)=f.server.requests.filter { it.method=="POST" }
    private fun sent(f: ExecutionFixture)=posts(f).filter { it.path=="/v1/replies" }.map { JSONObject(String(it.body)) }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync { val view=ui.activity.window.decorView;view.postOnAnimation(object: Runnable { var remaining=3;override fun run() { if(--remaining==0)frames.countDown() else view.postOnAnimation(this) } }) }
        assertTrue(frames.await(5,java.util.concurrent.TimeUnit.SECONDS));var bitmap: Bitmap?=null
        i.runOnMainSync { assertTrue(ui.activity.window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(ui.activity.window,image,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            val run=InstrumentationRegistry.getArguments().getString("planUiRunId") ?: "fixture"
            val folder=File(i.targetContext.filesDir,"acceptance/P4-plan-ui/$run").apply { mkdirs() }
            File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.window").put("package",i.targetContext.packageName)
                .put("width",image.width).put("height",image.height).put("pixelCopyResult",result).put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{image.recycle()}
    }
    @Test fun originalPlanMessageIsCollapsedAndCopiesItsFullOriginalOnlyAfterExpansion() {
        ExecutionFixture().use { f ->
            f.proposed=proposal();f.planMessages=JSONArray().put(message());connect(f)
            ui.runOnIdle { session.text="独立输入草稿" }
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-plan-message"))
            ui.onNodeWithTag("message-plan-message").assertExists()
            ui.onNodeWithTag("markdown-plan-message").assertDoesNotExist()
            ui.onNodeWithTag("review-plan-toggle").assertDoesNotExist()
            ui.onNodeWithTag("proposed-plan-toggle-plan-message").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已折叠")).performClick()
            ui.onNodeWithTag("markdown-plan-message").assertExists()
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("copy-plan-plan-message"))
            lateinit var clipboard: ClipboardManager
            ui.runOnUiThread { clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
            // Keep any previous opaque ClipData in memory; never print or persist user clipboard data.
            var previous: ClipData?=null
            ui.runOnUiThread { previous=clipboard.primaryClip }
            try {
                ui.onNodeWithTag("copy-plan-plan-message").assertIsDisplayed().performClick()
                ui.runOnUiThread { assertTrue("Copy publishes the full original plan",clipboard.primaryClip?.getItemAt(0)?.text?.toString()==body) }
            }finally{ui.runOnUiThread { val old=previous;if(old!=null)clipboard.setPrimaryClip(old) else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("","")) } }
            assertEquals("独立输入草稿",session.text);assertTrue(posts(f).isEmpty());shot("message-expanded-copy-and-separate-review")
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("proposed-plan-toggle-plan-message"))
            ui.onNodeWithTag("proposed-plan-toggle-plan-message").performClick();ui.onNodeWithTag("copy-plan-plan-message").assertDoesNotExist()
        }
    }
    @Test fun progressDisclosureCountsCompletedAndPreservesBothActiveStatusSpellings() {
        ExecutionFixture().use { f ->
            f.planSteps=JSONArray(listOf("completed","in_progress","inProgress","pending").mapIndexed { n,s->JSONObject().put("step","step-$n").put("status",s) });connect(f)
            ui.onNodeWithTag("runtime-plan").assertExists()
            ui.onNodeWithText("执行计划 · 1/4").assertExists();ui.onNodeWithTag("runtime-plan-step-0").assertDoesNotExist()
            ui.onNodeWithTag("runtime-plan-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("runtime-plan-status-0").assertContentDescriptionEquals("已完成")
            for(n in 1..2)ui.onNodeWithTag("runtime-plan-status-$n").assertContentDescriptionEquals("进行中")
            ui.onNodeWithTag("runtime-plan-status-3").assertContentDescriptionEquals("未完成")
            f.planSteps=JSONArray(listOf("completed","completed","inProgress","pending").mapIndexed { n,s->JSONObject().put("step","step-$n").put("status",s) });read()
            ui.onNodeWithText("执行计划 · 2/4").assertExists();ui.onNodeWithTag("runtime-plan-step-3").assertExists()
            assertTrue(posts(f).isEmpty());shot("progress-counts-expanded-statuses")
        }
    }
    @Test fun reviewFallbackFeedbackAndPendingKeepTheIndependentComposer() {
        ExecutionFixture().use { f ->
            f.proposed=proposal();connect(f);ui.runOnIdle { session.text="独立草稿不清除" }
            ui.onNodeWithText("实施此计划？").assertExists();ui.onNodeWithTag("proposed-plan-text").assertDoesNotExist()
            ui.onNodeWithTag("review-plan-toggle").performScrollTo().performClick();ui.onNodeWithTag("proposed-plan-text").assertExists()
            ui.onNodeWithTag("revise-plan").performScrollTo().assertTextContains("调整计划").performClick()
            ui.onNodeWithTag("plan-feedback").performScrollTo().performTextReplacement(" \n\t")
            ui.onNodeWithTag("send-plan-feedback").assertIsNotEnabled()
            val feedback="请保留完整步骤，增加 中文 😀"
            ui.onNodeWithTag("plan-feedback").performTextReplacement(feedback)
            f.holdReplyResponse=true
            try {
                ui.onNodeWithTag("send-plan-feedback").performScrollTo().assertIsEnabled().performClick()
                ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus!=null }
                ui.onNodeWithTag("implement-plan").assertIsNotEnabled();ui.onNodeWithTag("send-plan-feedback").assertIsNotEnabled()
                ui.runOnIdle { session.revisePlan(session.runtime!!.proposedPlan!!,feedback) };assertEquals(1,sent(f).size)
                assertEquals("plan-id",sent(f).single().getString("expectedPlanId"));assertEquals("plan",sent(f).single().getString("collaborationMode"));assertEquals(feedback,sent(f).single().getString("text"))
                assertEquals("独立草稿不清除",session.text);shot("feedback-pending-retains-composer")
            }finally{f.replyRelease.countDown()}
            ui.waitUntil(10_000) { session.transferStatus==null }
        }
    }
    @Test fun runningPlanKeepsTheMessageButHidesTheReviewAndNewVersionResetsFeedback() {
        ExecutionFixture().use { f ->
            f.proposed=proposal();f.planMessages=JSONArray().put(message("inProgress"));f.state="running";f.turn="active-turn";connect(f)
            assertTrue(session.running);assertNotNull(session.runtime!!.proposedPlan)
            ui.onNodeWithTag("implement-plan").assertDoesNotExist()
            ui.onNodeWithTag("message-list").performScrollToNode(hasText("正在制定计划…"));ui.onNodeWithText("正在制定计划…").assertExists()
            f.state="idle";f.turn=null;read();ui.onNodeWithTag("revise-plan").performScrollTo().performClick()
            ui.onNodeWithTag("plan-feedback").performScrollTo().performTextReplacement("旧版本的修改建议")
            f.proposed=proposal("新版本完整正文","new-plan-turn");read()
            ui.onNodeWithTag("plan-feedback").assertDoesNotExist();ui.onNodeWithTag("revise-plan").assertIsEnabled()
            assertTrue(posts(f).isEmpty());shot("running-review-hidden-and-version-reset")
        }
    }
    @Test fun capturedPlanActionsRejectAnotherTargetRevisitAndChangedBodyOrTurn() {
        ExecutionFixture().use { f ->
            f.proposed=proposal();f.additionalThread="another-thread";connect(f)
            val plan=session.runtime!!.proposedPlan!!;val destination=session.planActionDestination();val a=session.threads.first()
            ui.runOnIdle { session.text="A draft";session.selectThread(session.threads.single { it.id=="another-thread" }) }
            ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
            ui.runOnIdle { session.text="B draft";session.implementPlan(plan,destination);session.revisePlan(plan,"old A feedback",destination) }
            assertEquals("B draft",session.text);assertTrue(posts(f).isEmpty())
            ui.runOnIdle { session.selectThread(a) };ui.waitUntil(10_000) { !session.historyLoading&&session.canCompose }
            ui.runOnIdle { session.implementPlan(plan,destination);session.revisePlan(plan,"old A feedback",destination) }
            assertTrue(posts(f).isEmpty());assertEquals("A draft",session.text)
            val current=session.planActionDestination();f.proposed=proposal("changed body with reused id","new-turn");read()
            ui.runOnIdle { session.implementPlan(plan,current);session.revisePlan(plan,"old version feedback",current) };assertTrue(posts(f).isEmpty())
            ui.onNodeWithTag("implement-plan").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(10_000) { sent(f).size==1&&session.transferStatus==null }
            assertEquals("PLEASE IMPLEMENT THIS PLAN:\nchanged body with reused id",sent(f).single().getString("text"));assertEquals("default",sent(f).single().getString("collaborationMode"));assertEquals("A draft",session.text)
            shot("captured-target-version-guards-and-current-implementation")
        }
    }
    @Test fun staleReadOnlyReviewKeepsDisclosureAndFeedbackWithoutCommandAuthority() {
        ExecutionFixture().use { f ->
            f.proposed=proposal();connect(f);ui.onNodeWithTag("revise-plan").performScrollTo().performClick()
            ui.onNodeWithTag("plan-feedback").performScrollTo().performTextReplacement("可编辑本地修改建议")
            ui.runOnIdle { session.leaveForeground() }
            ui.onNodeWithTag("implement-plan").assertIsNotEnabled();ui.onNodeWithTag("send-plan-feedback").assertIsNotEnabled()
            ui.onNodeWithTag("plan-feedback").performTextReplacement("断线后仍保留建议")
            ui.onNodeWithTag("plan-feedback").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("断线后仍保留建议")))
            ui.runOnIdle { session.implementPlan(session.runtime!!.proposedPlan!!);session.revisePlan(session.runtime!!.proposedPlan!!,"must not send") }
            assertTrue(posts(f).isEmpty());shot("cached-review-disabled-local-feedback-kept")
        }
    }
}
