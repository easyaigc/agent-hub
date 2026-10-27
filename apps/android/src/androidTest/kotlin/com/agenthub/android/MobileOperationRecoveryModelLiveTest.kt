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

/** Real accepted reply with a lost native confirmation, explicit detach and read-only process recovery. */
class MobileOperationRecoveryModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-operation-recovery-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","origin-complete","detached-complete","recovery-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original question witness",200,http.responseCode)
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-operation-recovery/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
        if(session.selectedCollaboration!="plan") { ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick() }
        ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick()
        assertEquals("default",session.generationSettings.collaborationMode)
    }
    private fun focusedWindow(): android.view.Window {
        var found: android.view.Window?=null
        i.runOnMainSync {
            val roots=android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow&&it.hasWindowFocus()&&it.context.packageName==i.targetContext.packageName }
            assertEquals("One focused window owned by this acceptance process",1,roots.size)
            val root=roots.single()
            fun find(v: android.view.View) {
                if(v is androidx.compose.ui.window.DialogWindowProvider) found=v.window
                if(v is android.view.ViewGroup) for(n in 0 until v.childCount) find(v.getChildAt(n))
            }
            find(root)
            if(found==null&&activity.window.decorView.rootView===root)found=activity.window
            assertTrue(checkNotNull(found).decorView.rootView===root)
        }
        return checkNotNull(found)
    }
    private fun shot(name: String) {
        hideKeyboard();ui.waitForIdle();i.waitForIdleSync();assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val window=focusedWindow();var image: Bitmap?=null
        i.runOnMainSync { assertTrue(window.decorView.hasWindowFocus());image=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(window,bitmap,{ result=it;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(folder(),"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(folder(),"$name-window.json").writeText(JSONObject().put("evidenceRun",fixture.getString("evidenceRun")).put("method","PixelCopy.owned-focused-window").put("window",if(window===activity.window)"main" else "dialog").put("package",i.targetContext.packageName).put("width",bitmap.width).put("height",bitmap.height)
                .put("pixelCopyResult",result).put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{bitmap.recycle()}
    }
    private fun hideKeyboard() {
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("composer-dismiss-keyboard").assertIsDisplayed().performClick()
        val window=focusedWindow()
        ui.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(window,window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        var stableSince=0L;var lastHeight=-1
        ui.waitUntil(15_000) {
            var hidden=false;var visibleHeight=0
            i.runOnMainSync {
                val view=window.decorView
                hidden=androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true
                val rect=android.graphics.Rect();view.getWindowVisibleDisplayFrame(rect);visibleHeight=rect.height()
            }
            val now=android.os.SystemClock.uptimeMillis()
            if(!hidden||lastHeight!=visibleHeight) { stableSince=now;lastHeight=visibleHeight }
            hidden&&now-stableSince>=700
        }
        ui.waitForIdle();i.waitForIdleSync()
    }
    private fun allowSharing() {
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model"));assertFalse(session.aiSharing.permits(scope))
        val text=session.text;val target=session.targetKey;val records=session.operationRecords.map { it.id }.toSet()
        hideKeyboard();shot("sharing-before-click")
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(10_000) { session.aiSharingRequest!=null } }
        catch(e: Throwable) { shot("sharing-request-absent");File(folder(),"sharing-diagnostic.txt").writeText("error=${session.error};canCompose=${session.canCompose};text=${session.text}\n"+ui.onNodeWithTag("composer").printToString());throw e }
        assertEquals(target,session.aiSharingRequest!!.targetKey);assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertEquals(text,session.text);assertEquals(target,session.targetKey);assertEquals(records,session.operationRecords.map { it.id }.toSet());assertNull(session.transferStatus)
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }

    private fun claim(index: Int) {
        control("claim-input",JSONObject().put("index",index).put("text",fixture.getJSONArray("cases").getJSONObject(index).getString("text"))
            .put("threadId",session.selectedThread).put("targetKey",session.targetKey))
    }
    private fun dismissError() { if(ui.onAllNodesWithTag("dismiss-error").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("dismiss-error").performClick() }
    @Test fun submittedUnknownReplyDetachesWithANewDraft() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        ui.runOnIdle { session.resetAcceptance() };main { session.connect("Android operation recovery DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            assertTrue(session.selectedThread.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()));shot("operation-recovery-preflight-unsent");return
        }
        val before=session.operationRecords.map { it.id }.toSet();claim(0);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        val originMarker=fixture.getJSONArray("cases").getJSONObject(0).getString("marker")
        ui.waitUntil(180_000) { session.transferStatus==null&&!session.running&&session.selectedThread.isNotEmpty()&&session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==originMarker } }
        val origin=session.operationRecords.single { it.id !in before&&it.kind=="creation" }
        control("origin-complete",JSONObject().put("threadId",session.selectedThread).put("record",origin.body()));shot("01-original-created-once")
        settings();ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(1).getString("text"));hideKeyboard()
        val beforeReply=session.operationRecords.map { it.id }.toSet();claim(1);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(45_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="reply"&&it.id !in beforeReply&&it.submissionAttempted&&it.phase in listOf("uncertain","outcome-unknown","received") } }
        val original=session.operationRecords.single { it.id !in beforeReply&&it.kind=="reply" }
        assertFalse(session.mayRetryReply(original));File(folder(),"02-original-unknown-record.json").writeText(original.body().toString())
        dismissError();ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();shot("02-reply-unknown-independent-draft")
        ui.onNodeWithTag("detach-operation-${original.id}").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(15_000) { session.detachedReplies.any { it.id==original.id }&&session.transferStatus==null }
        val detached=session.operationRecords.single { it.id==original.id };assertEquals(original.intent,detached.intent);assertEquals(original.intentSha256,detached.intentSha256)
        assertTrue(detached.submissionAttempted);assertTrue(detached.detached);assertEquals(fixture.getString("independentDraft"),session.text)
        ui.onNodeWithTag("retry-operation-${original.id}").assertDoesNotExist();ui.onNodeWithTag("detach-operation-${original.id}").assertDoesNotExist()
        val storage=NativeSessionStorage(i.targetContext)
        ui.waitUntil(15_000) { storage.metadata()?.optJSONObject("drafts")?.optString(session.targetKey)==fixture.getString("independentDraft") }
        File(folder(),"03-original-detached-record.json").writeText(detached.body().toString());shot("03-ended-waiting-original-retained")
        control("detached-complete",JSONObject().put("threadId",session.selectedThread).put("record",detached.body()).put("pid",android.os.Process.myPid()))
    }
    @Test fun sameOriginalRecordRecoversAfterAppAndHostRestart() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        // Restore the actual default acceptance installation; never reset, reconnect or copy metadata.
        val thread=fixture.getString("threadId");val id=fixture.getString("operationId")
        ui.waitUntil(60_000) { !session.restoring&&!session.loading&&session.hasClient&&session.selectedClient.id==fixture.getString("hostId")&&session.selectedThread==thread&&!session.historyLoading&&session.detachedReplies.any { it.id==id } }
        assertEquals(fixture.getString("independentDraft"),session.text);assertFalse(session.localRecoveryRequired)
        val original=session.detachedReplies.single { it.id==id };assertEquals(fixture.getString("originalIntentSha256"),original.intentSha256);assertTrue(original.submissionAttempted)
        dismissError();hideKeyboard();ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("reply-delivery-records").performClick()
        ui.onNodeWithTag("reply-record-text-$id").performScrollTo().assertTextEquals(fixture.getJSONArray("cases").getJSONObject(1).getString("text"))
        ui.onNodeWithTag("query-detached-reply-$id").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(45_000) { session.detachedReplies.singleOrNull { it.id==id }?.let { it.phase=="completed"&&!session.replyRecordQuerying(it) }==true }
        val confirmed=session.detachedReplies.single { it.id==id };assertTrue(confirmed.detached);assertEquals(original.intent,confirmed.intent);assertEquals(original.intentSha256,confirmed.intentSha256)
        ui.onNodeWithTag("reply-record-state-$id").assertTextEquals("已完成");shot("04-original-receipt-after-process-restart")
        assertNull(session.replyRecordQueryError(confirmed));assertEquals(fixture.getString("independentDraft"),session.text)
        File(folder(),"04-original-recovered-record.json").writeText(confirmed.body().toString())
        ui.onNodeWithTag("sheet-done").performClick();ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-reply-records").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("message-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString(fixture.getString("independentDraft"))))
        control("recovery-complete",JSONObject().put("threadId",thread).put("record",confirmed.body()).put("pid",android.os.Process.myPid()));shot("05-independent-draft-after-readonly-query")
    }
}
