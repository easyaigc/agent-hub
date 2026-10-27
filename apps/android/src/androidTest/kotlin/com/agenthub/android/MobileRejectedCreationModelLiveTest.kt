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

/** Actual examiner-controlled rejection; local return cannot issue a command, then a distinct explicit new input runs once. */
class MobileRejectedCreationModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-creation-rejected-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","first-rejected","returned-locally","case-complete"))
        val base=URI(fixture.getString("control"));require(base.scheme=="http"&&base.host=="127.0.0.1"&&base.rawPath.isEmpty())
        val http=base.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Authorization","Bearer "+fixture.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Exact original creation witness",200,http.responseCode)
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-creation-rejected/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
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
        var latest=""
        try {
            ui.waitUntil(10_000) {
                i.runOnMainSync {
                    val owned=android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.context.packageName==i.targetContext.packageName }
                    latest=owned.joinToString("\n") { "attached=${it.isAttachedToWindow};focus=${it.hasWindowFocus()};shown=${it.isShown};type=${it.javaClass.name}" }
                    val roots=owned.filter { it.isAttachedToWindow&&it.hasWindowFocus() }
                    require(roots.size<=1) { "More than one focused acceptance window" }
                    found=null
                    roots.singleOrNull()?.let { root ->
                        fun find(v: android.view.View) {
                            if(v is androidx.compose.ui.window.DialogWindowProvider) found=v.window
                            if(v is android.view.ViewGroup) for(n in 0 until v.childCount) find(v.getChildAt(n))
                        }
                        find(root)
                        if(found==null&&activity.window.decorView.rootView===root)found=activity.window
                        if(found!=null)assertTrue(found!!.decorView.rootView===root)
                    }
                }
                found!=null
            }
        }catch(failure: Throwable) {
            File(folder(),"own-window-not-ready.txt").writeText(latest)
            throw failure
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
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }

    private fun claim(index: Int) {
        control("claim-input",JSONObject().put("index",index).put("text",fixture.getJSONArray("cases").getJSONObject(index).getString("text"))
            .put("threadId",session.selectedThread).put("targetKey",session.targetKey))
    }
    @After fun leave() { if(::activity.isInitialized)ui.runOnIdle { session.leaveForeground() } }
    @Test fun rejectedOriginalReturnsLocallyBeforeADistinctExplicitNewInput() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29);ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        ui.runOnIdle { session.resetAcceptance() }
        main { session.connect("Android rejected creation DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        val originalText=fixture.getJSONArray("cases").getJSONObject(0).getString("text")
        val newText=fixture.getJSONArray("cases").getJSONObject(1).getString("text")
        ui.onNodeWithTag("message-input").performTextReplacement(originalText);allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            ui.onNodeWithTag("message-input").performTextReplacement(newText);hideKeyboard();shot("rejected-preflight-independent-unsent")
            assertTrue(session.operationRecords.isEmpty());assertEquals(newText,session.text);control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()));return
        }
        claim(0);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="creation"&&it.submissionAttempted&&it.phase=="rejected" } }
        val first=session.operationRecords.single { it.kind=="creation" };assertFalse(first.detached);assertEquals(originalText,JSONObject(first.intent).getString("text"));assertEquals("",session.selectedThread)
        ui.onNodeWithTag("desktop-creation-message-status").assertTextEquals("未发送");ui.onAllNodesWithTag("desktop-creation-message-loading").assertCountEquals(0)
        ui.onNodeWithTag("user-message-text-${first.id}").assertTextEquals(originalText);ui.onNodeWithTag("send-message").assertIsNotEnabled();assertEquals("send",session.decision.action)
        File(folder(),"01-authoritative-rejected-original.json").writeText(first.body().toString());shot("01-real-host-controlled-rejected")
        val proof=control("first-rejected",JSONObject().put("record",first.body()).put("threadId",session.selectedThread));assertTrue(proof.getBoolean("rejectedWithoutBridgeDelegation"))
        ui.onNodeWithTag("message-input").performTextReplacement(newText);hideKeyboard();val chosen=session.generationSettings
        ui.onNodeWithTag("user-message-text-${first.id}").assertTextEquals(originalText);ui.onNodeWithTag("send-message").assertIsNotEnabled()
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("creation-end-wait-dialog").assertIsDisplayed();shot("02-rejected-end-wait-confirmation")
        ui.onNodeWithTag("creation-end-wait-cancel").performClick();ui.waitForIdle();assertFalse(session.operationRecords.single { it.id==first.id }.detached)
        ui.onNodeWithTag("return-rejected-creation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(45_000) { session.transferStatus==null&&session.operationRecords.any { it.id==first.id&&it.phase=="rejected"&&it.detached }&&session.decision.action=="send"&&session.decision.enabled }
        fun original()=session.operationRecords.single { it.id==first.id }
        val returned=original();assertEquals(first.intent,returned.intent);assertEquals(first.intentSha256,returned.intentSha256);assertEquals(first.sharingScope,returned.sharingScope)
        assertEquals("",session.selectedThread);assertEquals(newText,session.text);assertEquals(chosen,session.generationSettings);assertNull(session.error)
        ui.onAllNodesWithTag("desktop-creation-message").assertCountEquals(0)
        // Read-only observations of the locally detached rejection must not reopen waiting.
        repeat(2) {
            ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("query-detached-${first.id}").assertIsEnabled().performClick()
            ui.waitUntil(45_000) { session.transferStatus==null&&original().phase=="rejected"&&original().detached }
            assertEquals(first.intent,original().intent);assertEquals(newText,session.text);assertEquals(chosen,session.generationSettings)
        }
        val storage=NativeSessionStorage(i.targetContext)
        val cold=runBlocking { NativeOperationJournal(storage).records(first.target).single { it.id==first.id } };assertEquals(original().body().toString(),cold.body().toString())
        File(folder(),"03-local-return-original-kept.json").writeText(original().body().toString());shot("03-local-return-independent-draft-kept")
        val permit=control("returned-locally",JSONObject().put("record",original().body()).put("threadId",session.selectedThread).put("draft",session.text));assertTrue(permit.getBoolean("explicitNewInputAllowed"))
        claim(1);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.operationRecords.any { it.kind=="creation"&&it.id!=first.id&&it.phase=="confirmed" }&&session.selectedThread.isNotEmpty() }
        val current=session.operationRecords.single { it.kind=="creation"&&it.id!=first.id };assertNotEquals(first.id,current.id);assertFalse(current.detached);assertEquals(newText,JSONObject(current.intent).getString("text"))
        val result=CodexCommandCodec.creation(JSONObject(checkNotNull(current.result)));assertEquals(result.threadId,session.selectedThread);assertEquals("",session.text)
        assertNull(session.error)
        val old=runBlocking { NativeOperationJournal(storage).records(first.target).single { it.id==first.id } };val second=runBlocking { NativeOperationJournal(storage).records(current.target).single { it.id==current.id } }
        assertEquals("rejected",old.phase);assertTrue(old.detached);assertEquals(first.intent,old.intent);assertEquals(first.intentSha256,old.intentSha256);assertEquals(first.sharingScope,old.sharingScope);assertEquals(current.body().toString(),second.body().toString())
        File(folder(),"04-explicit-new-creation-confirmed.json").writeText(second.body().toString())
        control("case-complete",JSONObject().put("records",org.json.JSONArray(listOf(old.body(),second.body()))).put("threadId",session.selectedThread).put("pid",android.os.Process.myPid()));shot("04-distinct-new-request-original-desktop-confirmed")
    }
}
