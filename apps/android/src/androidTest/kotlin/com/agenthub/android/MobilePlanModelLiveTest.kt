package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Fresh original-desktop plan inputs, one immutable independent lease per native action. */
class MobilePlanModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(360)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-plan-model-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-case","case-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original plan witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use { it.readBytes() }
        var resumed: MainActivity?=null
        ui.waitUntil(20_000) { i.runOnMainSync { resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() };resumed!=null }
        activity=checkNotNull(resumed)
    }
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-plan-model/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
    private fun planJson(plan: MobileProposedPlan)=JSONObject().put("id",plan.id).put("turnId",plan.turnId).put("text",plan.text)
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
    }
    private fun shot(name: String) {
        ui.waitForIdle();i.waitForIdleSync();assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        var image: Bitmap?=null
        i.runOnMainSync { assertTrue(activity.window.decorView.hasWindowFocus());image=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(activity.window,bitmap,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("evidenceRun",fixture.getString("evidenceRun")).put("method","PixelCopy.window").put("package",i.targetContext.packageName).put("width",bitmap.width).put("height",bitmap.height)
                .put("pixelCopyResult",result).put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{bitmap.recycle()}
    }
    private fun hideKeyboard() {
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("composer-dismiss-keyboard").assertIsDisplayed().performClick()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        var stableSince=0L;var lastHeight=-1
        ui.waitUntil(15_000) {
            var hidden=false;var visibleHeight=0
            i.runOnMainSync {
                val view=activity.window.decorView
                hidden=androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
                val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);visibleHeight=rect.height()
            }
            val now=android.os.SystemClock.uptimeMillis()
            if(!hidden||lastHeight!=visibleHeight) { stableSince=now;lastHeight=visibleHeight }
            hidden&&now-stableSince>=700
        }
        ui.waitForIdle();i.waitForIdleSync()
    }
    private fun showOriginalPlan(plan: MobileProposedPlan) {
        hideKeyboard()
        val message=session.messages.lastOrNull { it.kind=="plan"&&it.text==plan.text }
        if(message==null) {
            if(ui.onAllNodesWithTag("proposed-plan-text").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("review-plan-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("proposed-plan-text").assertExists()
        } else {
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("proposed-plan-toggle-${message.id}"))
            if(ui.onAllNodesWithTag("markdown-${message.id}").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("proposed-plan-toggle-${message.id}").performClick()
            ui.onNodeWithTag("markdown-${message.id}").assertExists()
        }
    }
    private fun allowSharing() {
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertFalse(session.aiSharing.permits(scope))
        val text=session.text;val target=session.targetKey;val records=session.operationRecords.map { it.id }.toSet()
        hideKeyboard();shot("sharing-before-click")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(10_000) { session.aiSharingRequest!=null } }
        catch(e: Throwable) { shot("sharing-request-absent");File(folder(),"sharing-diagnostic.txt").writeText("error=${session.error};canCompose=${session.canCompose};text=${session.text}\n"+ui.onNodeWithTag("plan-review").printToString());throw e }
        assertEquals(target,session.aiSharingRequest!!.targetKey);assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertEquals(text,session.text);assertEquals(target,session.targetKey);assertEquals(records,session.operationRecords.map { it.id }.toSet());assertNull(session.transferStatus)
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }
    @Test fun originalPlanModelCase() {
        bind();val phase=InstrumentationRegistry.getArguments().getString("planModelCase")!!.toInt();assertEquals(phase,fixture.getInt("activeCase"));require(phase in -2..2)
        val index=if(phase==-2)1 else phase
        if(index==1&&fixture.optBoolean("resumeRemaining",false)) {
            ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android original plan continuation",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.threads.any { it.id==fixture.getString("threadId") } }
            ui.runOnIdle { session.selectThread(session.threads.single { it.id==fixture.getString("threadId") }) }
        }
        if(index<=0) {
            ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android plan model DEV",fixture.getString("endpoint"),fixture.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
            ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
            ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick();assertEquals("plan",session.selectedCollaboration)
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
            if(index<0) { assertTrue(session.selectedThread.isEmpty());assertEquals("plan",session.selectedCollaboration);control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));shot("native-plan-preflight-unsent");return }
        } else {
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.selectedThread==fixture.getString("threadId")&&session.runtime?.proposedPlan!=null&&session.canCompose }
            settings()
            if(index==1&&fixture.optBoolean("resumeRemaining",false)) {
                ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));allowSharing()
            }
        }
        val row=fixture.getJSONArray("cases").getJSONObject(index)
        val originalPlan=if(index>0)checkNotNull(session.runtime?.proposedPlan) else null
        if(index==1) {
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard()
            ui.onNodeWithTag("revise-plan").performScrollTo().assertIsEnabled();shot("adjustment-before-click")
            ui.onNodeWithTag("revise-plan").performClick()
            try { ui.waitUntil(10_000) { ui.onAllNodesWithTag("plan-feedback").fetchSemanticsNodes().isNotEmpty() } }
            catch(e: Throwable) { shot("adjustment-feedback-absent");File(folder(),"adjustment-semantics.txt").writeText(ui.onNodeWithTag("plan-review").printToString());throw e }
            ui.onNodeWithTag("plan-feedback").performScrollTo().performTextReplacement(row.getString("text"));hideKeyboard()
        }
        if(phase==-2) { shot("native-existing-plan-adjust-unsent");control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey));return }
        if(index==2)assertEquals(fixture.getString("independentDraft"),session.text)
        val target=session.targetKey;val before=session.operationRecords.map { it.id }.toSet()
        control("claim-case",JSONObject().put("index",index).put("targetKey",target).put("threadId",session.selectedThread).put("text",if(index==2)"PLEASE IMPLEMENT THIS PLAN:\n"+originalPlan!!.text else row.getString("text"))
            .put("plan",originalPlan?.let(::planJson) ?: JSONObject.NULL))
        when(index) {
            0->ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
            1->ui.onNodeWithTag("send-plan-feedback").performScrollTo().assertIsEnabled().performClick()
            2->ui.onNodeWithTag("implement-plan").performScrollTo().assertIsEnabled().performClick()
        }
        try {
            ui.waitUntil(180_000) {
                session.transferStatus==null&&!session.running&&if(index<2)session.runtime?.proposedPlan?.let { it.id!=originalPlan?.id&&it.text.contains(row.getString("marker")) }==true
                else session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==fixture.getString("finalMarker") }
            }
        }catch(failure: ComposeTimeoutException) { shot("case-$index-unresolved");throw AssertionError("Original plan input unresolved; never replay: ${session.runtimeState}; ${session.error}",failure) }
        val record=session.operationRecords.single { it.id !in before&&it.kind==if(index==0)"creation" else "reply" }
        assertTrue(record.submissionAttempted);assertTrue(session.selectedThread.isNotEmpty())
        if(index>0)assertEquals(fixture.getString("independentDraft"),session.text)
        val plan=session.runtime?.proposedPlan
        if(index==2) { assertNull(plan);assertEquals("default",session.selectedCollaboration) }
        control("case-complete",JSONObject().put("index",index).put("threadId",session.selectedThread).put("record",record.body()).put("plan",plan?.let(::planJson) ?: JSONObject.NULL).put("pid",android.os.Process.myPid()))
        if(index<2) { showOriginalPlan(checkNotNull(plan));ui.onNodeWithTag("implement-plan").performScrollTo().assertIsEnabled();ui.onNodeWithTag("revise-plan").assertIsEnabled() }
        else { ui.onNodeWithTag("message-list").performScrollToNode(hasText(fixture.getString("finalMarker")));ui.onAllNodesWithText(fixture.getString("finalMarker")).onLast().assertIsDisplayed() }
        shot("case-$index-actual-plan")
    }
}
