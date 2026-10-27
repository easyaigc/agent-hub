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

/** Real independent creations; a transparent observer delays only the first actual native return. */
class MobileCreationIsolationModelLiveTest {
    @get:Rule val limit=org.junit.rules.Timeout.seconds(600)
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val session get()=activity.session
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val fixture by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-creation-isolation-config.json").readText()) }
    private val nonce get()=fixture.getString("nonce")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("preflight-complete","claim-input","first-pending","first-detached","second-confirmed","case-complete"))
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
    private fun folder()=File(i.targetContext.filesDir,"acceptance/P4-creation-isolation/$nonce/${fixture.getString("evidenceRun")}").apply { mkdirs() }
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
    @Test fun twoOriginalCreationsKeepTheSecondTargetAndDraftAfterTheFirstActualReturn() {
        bind();require(android.os.Build.VERSION.SDK_INT>=29)
        ui.waitUntil(45_000) { !session.restoring };assertFalse(session.localRecoveryRequired)
        val storage=NativeSessionStorage(i.targetContext);val native=NativeCodexCommands { storage.token(it) }
        // The decorator forwards every production call unchanged and only delays this return.
        // Capturing the enclosing Job lets the assertion wait for the whole flow and its finally.
        val observed=object: CodexCommands by native {
            override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation {
                if(firstId==null)firstId=operationId
                jobs[operationId]=currentCoroutineContext().job
                val actual=native.submitCreation(client,operationId);actualReturns[operationId]=actual
                if(operationId==firstId)releaseFirst.await()
                return actual
            }
            override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation {
                detachJobs[operationId]=currentCoroutineContext().job
                return native.detachCreation(client,operationId)
            }
        }
        ui.runOnIdle { session.resetAcceptance(commandProvider=observed) }
        main { session.connect("Android creation isolation DEV",fixture.getString("endpoint"),fixture.getString("token")) }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==fixture.getString("hostId")&&session.projects.any { it.id==fixture.getString("projectId") } }
        ui.runOnIdle { session.newChat(fixture.getString("projectId")) };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(0).getString("text"));allowSharing()
        if(fixture.getBoolean("preflightOnly")) {
            assertTrue(session.selectedThread.isEmpty())
            // Exercise both owned sheet/keyboard transitions without any operation submission.
            ui.onNodeWithTag("new-chat").assertIsEnabled().performClick();ui.onNodeWithTag("choose-project-${fixture.getString("projectId")}").performScrollTo().performClick()
            settings();ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();settings()
            shot("creation-isolation-preflight-second-sheet-unsent")
            assertEquals(fixture.getString("independentDraft"),session.text);assertTrue(session.operationRecords.isEmpty())
            control("preflight-complete",JSONObject().put("pid",android.os.Process.myPid()));shot("creation-isolation-preflight-unsent");return
        }
        val before=session.operationRecords.map { it.id }.toSet();claim(0);ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { firstId!=null&&actualReturns.containsKey(firstId)&&session.operationRecords.any { it.id==firstId&&it.submissionAttempted } }
        val first=session.operationRecords.single { it.id==firstId };assertFalse(jobs.getValue(first.id).isCompleted);assertNotNull(session.transferStatus)
        control("first-pending",JSONObject().put("record",first.body()).put("actualReturn",encodeCreation(actualReturns.getValue(first.id))))
        shot("01-first-actual-result-delayed")
        ui.onNodeWithTag("detach-operation-${first.id}").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("creation-end-wait-confirm").assertIsEnabled().performClick()
        ui.waitUntil(30_000) { session.operationRecords.any { it.id==first.id&&it.detached&&it.phase=="confirmed" }&&session.transferStatus==null&&detachJobs[first.id]?.isCompleted==true }
        val detached=session.operationRecords.single { it.id==first.id };assertEquals(first.intent,detached.intent);assertEquals(first.intentSha256,detached.intentSha256)
        control("first-detached",JSONObject().put("record",detached.body()));assertFalse(jobs.getValue(first.id).isCompleted)
        ui.onNodeWithTag("new-chat").assertIsEnabled().performClick();ui.onNodeWithTag("choose-project-${fixture.getString("projectId")}").performScrollTo().performClick()
        ui.waitUntil(30_000) { session.selectedThread.isEmpty()&&session.canConfigureGeneration };settings()
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getJSONArray("cases").getJSONObject(1).getString("text"));hideKeyboard();claim(1)
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        ui.waitUntil(180_000) { session.transferStatus==null&&session.selectedThread.isNotEmpty()&&session.operationRecords.any { it.id !in before&&it.id!=first.id&&it.kind=="creation"&&it.phase=="confirmed" } }
        val second=session.operationRecords.single { it.id !in before&&it.id!=first.id&&it.kind=="creation" };val thread=session.selectedThread
        ui.waitUntil(30_000) { jobs[second.id]?.isCompleted==true }
        assertNotEquals(first.id,second.id);assertEquals(thread,JSONObject(second.result!!).getString("threadId"))
        ui.onNodeWithTag("message-input").performTextReplacement(fixture.getString("independentDraft"));hideKeyboard();settings()
        val chosen=session.generationSettings;assertFalse("The original flow is still held by this observer",jobs.getValue(first.id).isCompleted)
        val permission=control("second-confirmed",JSONObject().put("threadId",thread).put("record",second.body()).put("firstRecord",session.operationRecords.single { it.id==first.id }.body()))
        assertTrue(permission.getBoolean("releaseOriginalReturn"));shot("02-second-created-with-independent-draft")
        ui.runOnIdle { releaseFirst.complete(Unit) };ui.waitUntil(30_000) { jobs.getValue(first.id).isCompleted };ui.waitForIdle()
        assertEquals(thread,session.selectedThread);assertEquals(fixture.getString("independentDraft"),session.text);assertEquals(chosen,session.generationSettings);assertNull(session.transferStatus)
        val old=session.operationRecords.single { it.id==first.id };val current=session.operationRecords.single { it.id==second.id }
        assertTrue(old.detached);assertEquals(first.intent,old.intent);assertEquals(first.intentSha256,old.intentSha256);assertEquals(second.intent,current.intent);assertEquals(second.intentSha256,current.intentSha256)
        val records=org.json.JSONArray(listOf(old.body(),current.body()));File(folder(),"03-two-original-records.json").writeText(records.toString())
        control("case-complete",JSONObject().put("threadId",thread).put("records",records).put("pid",android.os.Process.myPid()))
        shot("03-old-flow-finished-second-target-preserved")
    }
}
