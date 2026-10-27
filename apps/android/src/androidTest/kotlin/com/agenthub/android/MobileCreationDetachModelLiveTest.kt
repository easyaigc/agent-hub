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

/** Actual original detach notification acknowledgement loss; no accepted input is replayed. */
class MobileCreationDetachModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-creation-detach-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","first-pending","detached-failure","query-ready","case-complete"))
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-creation-detach/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick();ui.selectNativeGeneration(fixture.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(fixture.getString("model"),session.generationSettings.model);assertEquals("xhigh",session.generationSettings.effort)
        assertEquals("fast",session.generationSettings.speed);assertEquals("read-only",session.generationSettings.permissionMode)
        if(session.selectedCollaboration!="plan") { ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick() }
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("toggle-plan-mode").assertIsEnabled().performClick()
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
        assertTrue(session.aiSharing.permits(scope));shot("native-plan-sharing-granted-unsent")
    }

    private val jobs=mutableMapOf<String,Job>()
    private val detachJobs=mutableMapOf<String,Job>()
    private val actualReturns=mutableMapOf<String,DesktopCreation>()
    private val releaseFirst=CompletableDeferred<Unit>()
    private var firstId: String?=null
    private fun claim(index: Int) {
        control("claim-input",JSONObject().put("index",index).put("text",fixture.getJSONArray("cases").getJSONObject(index).getString("text"))
            .put("threadId",session.selectedThread).put("targetKey",session.targetKey))
    }
    @After fun releaseObserver() { if(::activity.isInitialized)ui.runOnIdle { releaseFirst.complete(Unit);session.leaveForeground() } }
    @Test fun originalCreationDetachFailurePersistsAndOnlyReadsItsAcceptedReceipt() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        val storage=NativeSessionStorage(i.targetContext);val native=NativeCodexCommands { storage.token(it) }
        val observed=object: CodexCommands by native {
            override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation {
                check(firstId==null);firstId=operationId;jobs[operationId]=currentCoroutineContext().job
                val actual=native.submitCreation(client,operationId);actualReturns[operationId]=actual;releaseFirst.await();return actual
            }
            override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation {
                detachJobs[operationId]=currentCoroutineContext().job;return native.detachCreation(client,operationId)
            }
        }
        ui.runOnIdle { session.resetAcceptance(commandProvider=observed) }
        main { session.connect("Android detach notification DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();settings();shot("detach-preflight-unsent")
            assertTrue(session.operationRecords.isEmpty());control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()));return
        }
        claim(0);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { firstId!=null&&actualReturns.containsKey(firstId)&&session.operationRecords.any { it.id==firstId&&it.submissionAttempted } }
        val first=session.operationRecords.single { it.id==firstId };assertFalse(jobs.getValue(first.id).isCompleted)
        control("first-pending",JSONObject().put("record",first.body()).put("actualReturn",encodeCreation(actualReturns.getValue(first.id))));shot("01-first-actual-return-delayed")
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("creation-end-wait-dialog").assertIsDisplayed();assertTrue(detachJobs.isEmpty())
        assertFalse(session.operationRecords.single { it.id==first.id }.detached);shot("01a-original-end-wait-confirmation")
        ui.onNodeWithTag("creation-end-wait-cancel").performClick();ui.waitForIdle()
        assertTrue(detachJobs.isEmpty());assertFalse(session.operationRecords.single { it.id==first.id }.detached);assertFalse(jobs.getValue(first.id).isCompleted)
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("creation-end-wait-confirm").assertIsEnabled().performClick()
        val marker=fixture.getString("detachErrorMarker")
        ui.waitUntil(45_000) { detachJobs[first.id]?.isCompleted==true&&session.transferStatus==null&&session.operationRecords.any { it.id==first.id&&it.detached&&it.error?.contains(marker)==true } }
        assertFalse(jobs.getValue(first.id).isCompleted);assertNull(session.error)
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();settings();val chosen=session.generationSettings
        fun record()=session.operationRecords.single { it.id==first.id }
        val failed=record();assertEquals(first.intent,failed.intent);assertEquals(first.intentSha256,failed.intentSha256)
        val persisted=runBlocking { NativeOperationJournal(storage).records(first.target).single { it.id==first.id } };assertEquals(failed.body().toString(),persisted.body().toString())
        File(folder(),"02-original-detach-failure.json").writeText(failed.body().toString());shot("02-notification-failure-original-record-new-draft")
        val release=control("detached-failure",JSONObject().put("record",failed.body()).put("threadId",session.selectedThread));assertTrue(release.getBoolean("releaseOriginalReturn"))
        ui.runOnIdle { releaseFirst.complete(Unit) };ui.waitUntil(30_000) { jobs.getValue(first.id).isCompleted };ui.waitForIdle()
        assertEquals("",session.selectedThread);assertEquals(fixture.getString("independentDraft"),session.text);assertEquals(chosen,session.generationSettings);assertNull(session.error);assertNull(session.transferStatus)
        val late=record();assertTrue(late.detached);assertEquals(failed.error,late.error);assertEquals(first.intent,late.intent);assertEquals(first.intentSha256,late.intentSha256)
        val permit=control("query-ready",JSONObject().put("record",late.body()).put("threadId",session.selectedThread));assertTrue(permit.getBoolean("queryOnly"))
        shot("03-before-original-query-menu");ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("query-detached-${first.id}").assertIsEnabled().performClick()
        ui.waitUntil(45_000) { session.transferStatus==null&&record().phase=="confirmed"&&record().error==null }
        val recovered=record();assertTrue(recovered.detached);assertEquals(first.intent,recovered.intent);assertEquals(first.intentSha256,recovered.intentSha256)
        assertEquals("",session.selectedThread);assertEquals(fixture.getString("independentDraft"),session.text);assertEquals(chosen,session.generationSettings);assertNull(session.error)
        File(folder(),"04-original-read-only-confirmed.json").writeText(recovered.body().toString());control("case-complete",JSONObject().put("record",recovered.body()).put("threadId",session.selectedThread).put("pid",android.os.Process.myPid()));shot("04-original-confirmed-new-draft-retained")
    }
}
